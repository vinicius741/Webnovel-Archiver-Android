package com.vinicius741.webnovelarchiver.data.storage

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.vinicius741.webnovelarchiver.cleanup.TextCleanup
import com.vinicius741.webnovelarchiver.domain.model.AppSettings
import com.vinicius741.webnovelarchiver.domain.model.ChapterFilterSettings
import com.vinicius741.webnovelarchiver.domain.model.DisplayPreferences
import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule
import com.vinicius741.webnovelarchiver.domain.model.SourceDownloadSettings
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.Tab
import com.vinicius741.webnovelarchiver.domain.model.TtsSession
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import com.vinicius741.webnovelarchiver.feature.settings.PreferenceNormalization
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
        payload: Map<String, Any>,
    ) {
        writePrimaryPreferences(stagedRoot, payload)
        writeCollections(stagedRoot, payload)
        writeTts(stagedRoot, payload)
    }

    private fun writePrimaryPreferences(
        root: File,
        payload: Map<String, Any>,
    ) {
        payload["settings"]?.let {
            writeEnvelope(File(root, "settings.json"), PreferenceNormalization.appSettings(normalize(it, AppSettings::class.java)))
        }
        payload["sourceDownloadSettings"]?.let {
            val type = object : TypeToken<MutableMap<String, SourceDownloadSettings>>() {}.type
            writeEnvelope(
                File(root, "source_download_settings.json"),
                PreferenceNormalization.sourceDownloadSettings(normalize(it, type)),
            )
        }
        payload["chapterFilterSettings"]?.let {
            writeEnvelope(
                File(root, "chapter_filter_settings.json"),
                PreferenceNormalization.chapterFilterSettings(normalize(it, ChapterFilterSettings::class.java)),
            )
        }
        payload["displayPreferences"]?.let {
            writeEnvelope(
                File(root, "display_preferences.json"),
                PreferenceNormalization.displayPreferences(normalize(it, DisplayPreferences::class.java)),
            )
        }
    }

    private fun writeCollections(
        root: File,
        payload: Map<String, Any>,
    ) {
        payload["tabs"]?.let {
            val tabs = normalize<List<Tab>>(it, object : TypeToken<MutableList<Tab>>() {}.type)
            writeEnvelope(File(root, "tabs.json"), tabs.sortedBy { tab -> tab.order })
        }
        payload["sentenceRemovalList"]?.let {
            val sentences = normalize<List<String>>(it, object : TypeToken<MutableList<String>>() {}.type)
            writeEnvelope(File(root, "sentence_removal.json"), sentences)
        }
        payload["regexCleanupRules"]?.let {
            val rules = normalize<List<RegexCleanupRule>>(it, object : TypeToken<MutableList<RegexCleanupRule>>() {}.type)
            writeEnvelope(File(root, "regex_cleanup_rules.json"), TextCleanup.sanitizeRegexRules(rules))
        }
        payload["updateFollowedStoryIds"]?.let {
            val ids = normalize<List<String>>(it, object : TypeToken<MutableList<String>>() {}.type)
            writeEnvelope(File(root, "update_followed_story_ids.json"), ids.filter(String::isNotBlank).distinct())
        }
    }

    private fun writeTts(
        root: File,
        payload: Map<String, Any>,
    ) {
        payload["ttsSettings"]?.let {
            writeEnvelope(
                File(root, "tts_settings.json"),
                PreferenceNormalization.ttsSettings(normalize(it, TtsSettings::class.java)),
            )
        }
        payload["ttsSession"]?.let { writeEnvelope(File(root, "tts_session.json"), normalize<TtsSession>(it, TtsSession::class.java)) }
    }

    private fun writeEnvelope(
        file: File,
        value: Any,
    ) = AtomicFileWrites.writeText(file, gson.toJson(DurableJson.envelope(value, storage.appVersion)))

    private fun <T> normalize(
        payload: Any,
        type: Type,
    ): T = gson.fromJson(gson.toJson(payload), type)
}
