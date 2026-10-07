package com.vinicius741.webnovelarchiver.data.storage

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * Disposable, device-local cover-evidence state: content-addressed decision-model score responses plus the
 * last automatic chapter selection per story (a display hint for the cover context picker, not a
 * manual override). Contains no API keys or chapter text; excluded from backups. Loss just means
 * the picker shows the pre-scan empty state until the next generation.
 */
class CoverEvidenceStore(
    cacheRoot: File,
) {
    private val directory = File(cacheRoot, "cover_evidence")
    private val selectionsFile = File(cacheRoot, "cover_evidence_selections.json")
    private val selectionLock = Any()

    // Construction runs on AppContainer's IO setup; render-time reads never touch disk or wait
    // for score-cache maintenance. Published maps and their lists are never mutated in place.

    @Volatile
    private var selections: Map<String, List<Int>> = loadSelections()

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

    fun selection(storyId: String): List<Int>? = selections[storyId]?.toList()

    fun record(
        storyId: String,
        chapterIndices: List<Int>,
    ) {
        synchronized(selectionLock) {
            selections = selections + (storyId to chapterIndices.toList())
            runCatching { AtomicFileWrites.writeText(selectionsFile, serialize(selections)) }
        }
    }

    private fun serialize(selections: Map<String, List<Int>>): String {
        val root = JsonObject()
        selections.forEach { (storyId, indices) ->
            val array = JsonArray()
            indices.forEach(array::add)
            root.add(storyId, array)
        }
        return root.toString()
    }

    private fun loadSelections(): MutableMap<String, List<Int>> =
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
