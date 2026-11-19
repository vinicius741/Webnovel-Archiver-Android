package com.vinicius741.webnovelarchiver.data.storage

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.vinicius741.webnovelarchiver.domain.model.LifeStage
import com.vinicius741.webnovelarchiver.domain.model.StoryPhase
import java.io.File

/** Which generator an automatic chapter selection was made for; each keeps its own last selection. */
enum class EvidenceSelectionKind(
    internal val fileName: String,
) {
    COVER("cover_evidence_selections.json"),
    DESCRIPTION("description_evidence_selections.json"),
}

/**
 * Disposable, device-local chapter-evidence state: content-addressed decision-model score responses
 * (cover and description requests hash differently, so they share one directory) plus the last
 * automatic chapter selection per story and [EvidenceSelectionKind] (a display hint for the context
 * picker, not a manual override) and the story phases the last cover scan found (for the Story phase row). Contains no API keys or chapter text; excluded from backups. Loss
 * just means the picker shows the pre-scan state until the next generation.
 */
class ChapterEvidenceStore(
    private val cacheRoot: File,
) {
    // The directory keeps its original name so cover scores cached before descriptions joined stay valid.
    private val directory = File(cacheRoot, "cover_evidence")
    private val selectionLock = Any()

    // Construction runs on AppContainer's IO setup; render-time reads never touch disk or wait
    // for score-cache maintenance. Published maps and their lists are never mutated in place.

    @Volatile
    private var selections: Map<EvidenceSelectionKind, Map<String, List<Int>>> =
        EvidenceSelectionKind.entries.associateWith { loadSelections(selectionsFile(it)) }

    private val phasesFile = File(cacheRoot, "cover_phases.json")

    @Volatile
    private var coverPhases: Map<String, List<StoryPhase>> = loadPhases(phasesFile)

    @Synchronized
    fun read(key: String): JsonObject? =
        runCatching {
            val file = File(directory, key)
            if (!file.exists() || file.length() > 64_000) return null
            JsonParser.parseString(file.readText()).asJsonObject
        }.getOrNull()

    @Synchronized
    fun write(
        key: String,
        value: JsonObject,
    ) {
        AtomicFileWrites.writeText(File(directory, key), value.toString())
    }

    @Synchronized
    fun trim() {
        directory
            .listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(20_000)
            ?.forEach { it.delete() }
    }

    fun selection(
        storyId: String,
        kind: EvidenceSelectionKind = EvidenceSelectionKind.COVER,
    ): List<Int>? = selections[kind]?.get(storyId)?.toList()

    fun record(
        storyId: String,
        chapterIndices: List<Int>,
        kind: EvidenceSelectionKind = EvidenceSelectionKind.COVER,
    ) {
        synchronized(selectionLock) {
            val updated = selections[kind].orEmpty() + (storyId to chapterIndices.toList())
            selections = selections + (kind to updated)
            runCatching { AtomicFileWrites.writeText(selectionsFile(kind), serialize(updated)) }
        }
    }

    /** Story phases the last automatic cover scan found; empty before the first scan or when it found none. */
    fun phases(storyId: String): List<StoryPhase> = coverPhases[storyId].orEmpty()

    fun recordPhases(
        storyId: String,
        phases: List<StoryPhase>,
    ) {
        synchronized(selectionLock) {
            coverPhases = coverPhases + (storyId to phases.toList())
            val root = JsonObject()
            coverPhases.forEach { (id, list) ->
                root.add(
                    id,
                    JsonArray().apply {
                        list.forEach { phase ->
                            add(
                                JsonObject().apply {
                                    addProperty("stage", phase.stage.key)
                                    addProperty("first", phase.firstChapter)
                                    addProperty("last", phase.lastChapter)
                                    addProperty("downloaded", phase.downloadedChapters)
                                },
                            )
                        }
                    },
                )
            }
            runCatching { AtomicFileWrites.writeText(phasesFile, root.toString()) }
        }
    }

    private fun selectionsFile(kind: EvidenceSelectionKind) = File(cacheRoot, kind.fileName)

    private fun serialize(selections: Map<String, List<Int>>): String {
        val root = JsonObject()
        selections.forEach { (storyId, indices) ->
            val array = JsonArray()
            indices.forEach(array::add)
            root.add(storyId, array)
        }
        return root.toString()
    }

    private fun loadSelections(selectionsFile: File): Map<String, List<Int>> =
        runCatching {
            if (!selectionsFile.exists()) return mutableMapOf()
            val root = JsonParser.parseString(selectionsFile.readText()).takeIf { it.isJsonObject }?.asJsonObject
            val parsed = root ?: return mutableMapOf()
            val result = mutableMapOf<String, List<Int>>()
            for ((storyId, value) in parsed.entrySet()) {
                val indices =
                    value
                        .takeIf { it.isJsonArray }
                        ?.asJsonArray
                        ?.mapNotNull { element -> runCatching { element.asInt }.getOrNull() }
                        .orEmpty()
                if (indices.isNotEmpty()) result[storyId] = indices
            }
            result
        }.getOrDefault(mutableMapOf())
}

private fun loadPhases(phasesFile: File): Map<String, List<StoryPhase>> =
    runCatching {
        if (!phasesFile.exists()) return emptyMap()
        val root = JsonParser.parseString(phasesFile.readText()).asJsonObject
        root.entrySet().associate { (storyId, value) ->
            // One malformed story entry loses only its own phases.
            storyId to
                value.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty().mapNotNull { element ->
                    runCatching {
                        val json = element.asJsonObject
                        StoryPhase(
                            stage = LifeStage.fromKey(json.get("stage").asString) ?: return@mapNotNull null,
                            firstChapter = json.get("first").asInt,
                            lastChapter = json.get("last").asInt,
                            downloadedChapters = json.get("downloaded").asInt,
                        )
                    }.getOrNull()
                }
        }
    }.getOrDefault(emptyMap())
