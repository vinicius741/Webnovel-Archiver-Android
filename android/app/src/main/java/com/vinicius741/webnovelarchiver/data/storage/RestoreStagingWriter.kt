package com.vinicius741.webnovelarchiver.data.storage

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.vinicius741.webnovelarchiver.cleanup.RegexRuleCleanup
import com.vinicius741.webnovelarchiver.domain.model.AiSettings
import com.vinicius741.webnovelarchiver.domain.model.AiUsageLedger
import com.vinicius741.webnovelarchiver.domain.model.AppSettings
import com.vinicius741.webnovelarchiver.domain.model.ChapterFilterSettings
import com.vinicius741.webnovelarchiver.domain.model.DisplayPreferences
import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule
import com.vinicius741.webnovelarchiver.domain.model.SourceDownloadSettings
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.Tab
import com.vinicius741.webnovelarchiver.domain.model.TtsSession
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import com.vinicius741.webnovelarchiver.domain.model.TtsStoryPosition
import com.vinicius741.webnovelarchiver.domain.settings.PreferenceNormalization
import java.io.File
import java.lang.reflect.Type

internal class RestoreStagingWriter(
    private val storage: AppStorage,
) {
    private val gson: Gson get() = storage.gson

    fun writeLibrary(
        stagedRoot: File,
        stories: List<Story>,
    ) {
        val storyDirectory = File(stagedRoot, "stories").apply { mkdirs() }
        writeEnvelope(File(stagedRoot, "library_index.json"), stories.map { it.id })
        val names = stories.map { "${storage.safeName(it.id)}.json" }
        check(names.distinct().size == names.size) { "Invalid full backup: story IDs collide after filename normalization" }
        stories.forEach { story ->
            story.totalChapters = story.chapters.size
            story.downloadedChapters = story.chapters.count { it.downloaded }
            writeEnvelope(File(storyDirectory, "${storage.safeName(story.id)}.json"), story)
        }
    }

    fun writeConfig(
        stagedRoot: File,
        payload: JsonObject,
    ) {
        writePrimaryPreferences(stagedRoot, payload)
        writeCollections(stagedRoot, payload)
        writeTts(stagedRoot, payload)
    }

    /** Carries device-local secrets across a root swap without adding them to the backup ZIP. */
    fun writeDeviceLocalAiSettings(
        stagedRoot: File,
        settings: AiSettings,
    ) {
        writeEnvelope(File(stagedRoot, "ai_settings.json"), PreferenceNormalization.aiSettings(settings))
    }

    /** Carries the local AI spend ledger across a same-device root swap without exporting it. */
    fun writeDeviceLocalAiUsage(
        stagedRoot: File,
        ledger: AiUsageLedger,
    ) {
        writeEnvelope(File(stagedRoot, "ai_usage.json"), ledger)
    }

    private fun writePrimaryPreferences(
        root: File,
        payload: JsonObject,
    ) {
        payload.value("settings")?.let {
            writeEnvelope(File(root, "settings.json"), PreferenceNormalization.appSettings(normalize(it, AppSettings::class.java)))
        }
        payload.value("sourceDownloadSettings")?.let {
            val type = object : TypeToken<MutableMap<String, SourceDownloadSettings>>() {}.type
            writeEnvelope(
                File(root, "source_download_settings.json"),
                PreferenceNormalization.sourceDownloadSettings(normalize(it, type)),
            )
        }
        payload.value("chapterFilterSettings")?.let {
            writeEnvelope(
                File(root, "chapter_filter_settings.json"),
                PreferenceNormalization.chapterFilterSettings(normalize(it, ChapterFilterSettings::class.java)),
            )
        }
        payload.value("displayPreferences")?.let {
            writeEnvelope(
                File(root, "display_preferences.json"),
                PreferenceNormalization.displayPreferences(normalize(it, DisplayPreferences::class.java)),
            )
        }
    }

    private fun writeCollections(
        root: File,
        payload: JsonObject,
    ) {
        payload.value("tabs")?.let {
            val tabs = normalize<List<Tab>>(it, object : TypeToken<MutableList<Tab>>() {}.type)
            writeEnvelope(File(root, "tabs.json"), tabs.sortedBy { tab -> tab.order })
        }
        payload.value("sentenceRemovalList")?.let {
            val sentences = normalize<List<String>>(it, object : TypeToken<MutableList<String>>() {}.type)
            writeEnvelope(File(root, "sentence_removal.json"), sentences)
        }
        payload.value("regexCleanupRules")?.let {
            val rules = normalize<List<RegexCleanupRule>>(it, object : TypeToken<MutableList<RegexCleanupRule>>() {}.type)
            writeEnvelope(File(root, "regex_cleanup_rules.json"), RegexRuleCleanup.sanitizeRegexRules(rules))
        }
    }

    private fun writeTts(
        root: File,
        payload: JsonObject,
    ) {
        payload.value("ttsSettings")?.let {
            writeEnvelope(
                File(root, "tts_settings.json"),
                PreferenceNormalization.ttsSettings(normalize(it, TtsSettings::class.java)),
            )
        }
        payload
            .value(
                "ttsSession",
            )?.let { writeEnvelope(File(root, "tts_session.json"), normalize<TtsSession>(it, TtsSession::class.java)) }
        // R11: per-story resume positions; older backups omit the key and restore with an empty map.
        payload.value("ttsPositions")?.let {
            val positions =
                normalize<MutableMap<String, TtsStoryPosition>>(
                    it,
                    object : TypeToken<MutableMap<String, TtsStoryPosition>>() {}.type,
                )
            writeEnvelope(File(root, "tts_positions.json"), positions)
        }
    }

    private fun writeEnvelope(
        file: File,
        value: Any,
    ) = AtomicFileWrites.writeAtomically(
        file,
    ) { out -> DurableJson.writeEnvelope(out, gson, DurableJson.envelope(value, storage.appVersion)) }

    /** Decodes straight from the manifest's JSON tree; no serialize-and-reparse round trip. */
    private fun <T> normalize(
        payload: JsonElement,
        type: Type,
    ): T = gson.fromJson(payload, type)

    /** A config value, with JSON null read as absent (as the former Map parse did). */
    private fun JsonObject.value(key: String): JsonElement? = get(key)?.takeUnless(JsonElement::isJsonNull)
}
