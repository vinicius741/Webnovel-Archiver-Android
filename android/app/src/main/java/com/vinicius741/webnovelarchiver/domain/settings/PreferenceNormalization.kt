package com.vinicius741.webnovelarchiver.domain.settings

import com.vinicius741.webnovelarchiver.domain.model.AiSettings
import com.vinicius741.webnovelarchiver.domain.model.AppSettings
import com.vinicius741.webnovelarchiver.domain.model.ChapterFilterSettings
import com.vinicius741.webnovelarchiver.domain.model.DisplayPreferences
import com.vinicius741.webnovelarchiver.domain.model.SourceDownloadSettings
import com.vinicius741.webnovelarchiver.domain.model.TtsProfile
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings

/** Canonicalizes persisted settings at every storage boundary. */
object PreferenceNormalization {
    private const val CONCURRENCY_MIN = 1
    private const val CONCURRENCY_MAX = 10
    const val DEFAULT_MAX_PARALLEL_SOURCES = 2
    private const val MAX_CHAPTERS_PER_EPUB_MIN = 10
    private const val MAX_CHAPTERS_PER_EPUB_MAX = 1000
    private const val TTS_MIN = 0.5f
    private const val TTS_MAX = 2.0f

    private val chapterFilterModes = setOf("all", "hideNonDownloaded", "hideAboveBookmark", "removedFromSource")
    private val foldLayoutModes = setOf("auto", "cover", "inner")
    private val screenLayoutModes = setOf("auto", "cover", "inner")
    private val libraryViewModes = setOf("list", "covers")
    private val librarySortOptions =
        setOf(
            "default",
            "title",
            "lastUpdated",
            "updated",
            "dateAdded",
            "totalChapters",
            "score",
            "patreonMonthly",
            "patreonMembers",
        )

    const val READER_FONT_SCALE_MIN = 0.8f
    const val READER_FONT_SCALE_MAX = 1.6f

    fun appSettings(settings: AppSettings): AppSettings {
        val minDelay = settings.downloadDelay.takeIf { it >= 0 } ?: AppSettings().downloadDelay
        val maxDelay = settings.downloadDelayMax.takeIf { it >= minDelay } ?: minDelay
        return settings.copy(
            downloadConcurrency = settings.downloadConcurrency.coerceIn(CONCURRENCY_MIN, CONCURRENCY_MAX),
            maxParallelSources =
                (settings.maxParallelSources ?: DEFAULT_MAX_PARALLEL_SOURCES)
                    .coerceIn(CONCURRENCY_MIN, CONCURRENCY_MAX),
            downloadDelay = minDelay,
            downloadDelayMax = maxDelay,
            maxChaptersPerEpub = settings.maxChaptersPerEpub.coerceIn(MAX_CHAPTERS_PER_EPUB_MIN, MAX_CHAPTERS_PER_EPUB_MAX),
        )
    }

    fun sourceDownloadSettings(settings: Map<String, SourceDownloadSettings>): MutableMap<String, SourceDownloadSettings> =
        settings
            .mapValues { (_, value) ->
                val minDelay = value.delay.takeIf { it >= 0 } ?: SourceDownloadSettings().delay
                val maxDelay = value.delayMax.takeIf { it >= minDelay } ?: minDelay
                value.copy(
                    concurrency = value.concurrency.coerceIn(CONCURRENCY_MIN, CONCURRENCY_MAX),
                    delay = minDelay,
                    delayMax = maxDelay,
                )
            }.toMutableMap()

    fun migrateSourceDownloadSettingKeys(
        settings: Map<String, SourceDownloadSettings>,
        stableIdForKey: (String) -> String?,
    ): MutableMap<String, SourceDownloadSettings> {
        val migrated = linkedMapOf<String, SourceDownloadSettings>()
        settings.entries
            .sortedByDescending { (key, _) -> stableIdForKey(key) == key }
            .forEach { (key, value) -> migrated.putIfAbsent(stableIdForKey(key) ?: key, value) }
        return sourceDownloadSettings(migrated)
    }

    fun chapterFilterSettings(settings: ChapterFilterSettings): ChapterFilterSettings =
        settings.copy(filterMode = settings.filterMode.takeIf { it in chapterFilterModes } ?: ChapterFilterSettings().filterMode)

    fun displayPreferences(preferences: DisplayPreferences): DisplayPreferences =
        preferences.copy(
            foldLayoutMode = preferences.foldLayoutMode.takeIf { it in foldLayoutModes } ?: DisplayPreferences().foldLayoutMode,
            screenLayoutMode = preferences.screenLayoutMode.takeIf { it in screenLayoutModes } ?: DisplayPreferences().screenLayoutMode,
            readerFontScale = preferences.readerFontScale.coerceIn(READER_FONT_SCALE_MIN, READER_FONT_SCALE_MAX),
            libraryTabId = preferences.libraryTabId?.takeIf { it.isNotBlank() },
            librarySortOption =
                preferences.librarySortOption
                    .takeIf { it.isNotBlank() && it in librarySortOptions }
                    ?.let { if (it == "updated") "lastUpdated" else it }
                    ?: DisplayPreferences().librarySortOption,
            libraryViewMode = preferences.libraryViewMode.takeIf { it in libraryViewModes } ?: DisplayPreferences().libraryViewMode,
            removedChaptersNoticeHiddenThrough = preferences.removedChaptersNoticeHiddenThrough?.takeIf { it.isNotEmpty() },
        )

    fun ttsSettings(settings: TtsSettings): TtsSettings {
        // Gson leaves a list null when a file says so explicitly, whatever the Kotlin type claims.
        @Suppress("USELESS_ELVIS")
        val profiles =
            (settings.profiles ?: emptyList())
                .filter { it.id.isNotBlank() && it.name.isNotBlank() }
                .distinctBy { it.id }
                .map(::ttsProfile)
        return settings.copy(
            pitch = settings.pitch.coerceIn(TTS_MIN, TTS_MAX),
            rate = settings.rate.coerceIn(TTS_MIN, TTS_MAX),
            phrasePauseMs = settings.phrasePauseMs.coerceIn(0L, TtsSettings.MAX_PHRASE_PAUSE_MS),
            sentencePauseMs = settings.sentencePauseMs.coerceIn(0L, TtsSettings.MAX_SENTENCE_PAUSE_MS),
            paragraphPauseMs = settings.paragraphPauseMs.coerceIn(0L, TtsSettings.MAX_PARAGRAPH_PAUSE_MS),
            scenePauseMs = settings.scenePauseMs.coerceIn(0L, TtsSettings.MAX_SCENE_PAUSE_MS),
            enginePackage = settings.enginePackage?.trim()?.takeIf { it.isNotBlank() },
            profiles = profiles,
            activeProfileId = settings.activeProfileId?.takeIf { id -> profiles.any { it.id == id } },
        )
    }

    private fun ttsProfile(profile: TtsProfile): TtsProfile =
        profile.copy(
            name = profile.name.trim(),
            enginePackage = profile.enginePackage?.trim()?.takeIf { it.isNotBlank() },
            pitch = profile.pitch.coerceIn(TTS_MIN, TTS_MAX),
            rate = profile.rate.coerceIn(TTS_MIN, TTS_MAX),
            phrasePauseMs = profile.phrasePauseMs.coerceIn(0L, TtsSettings.MAX_PHRASE_PAUSE_MS),
            sentencePauseMs = profile.sentencePauseMs.coerceIn(0L, TtsSettings.MAX_SENTENCE_PAUSE_MS),
            paragraphPauseMs = profile.paragraphPauseMs.coerceIn(0L, TtsSettings.MAX_PARAGRAPH_PAUSE_MS),
            scenePauseMs = profile.scenePauseMs.coerceIn(0L, TtsSettings.MAX_SCENE_PAUSE_MS),
        )

    fun aiSettings(settings: AiSettings): AiSettings =
        settings.copy(
            apiKey = settings.apiKey?.trim()?.takeIf { it.isNotBlank() },
            descriptionModel =
                settings.descriptionModel
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?: AiSettings.DEFAULT_DESCRIPTION_MODEL,
            imageModel =
                settings.imageModel
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?: AiSettings.DEFAULT_IMAGE_MODEL,
            decisionModel =
                settings.decisionModel
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?: AiSettings.DEFAULT_DECISION_MODEL,
            reasoningEfforts = AiReasoningEffort.normalize(settings.reasoningEfforts),
            coverEvidenceChapters =
                settings.coverEvidenceChapters.coerceIn(
                    AiSettings.MIN_COVER_EVIDENCE_CHAPTERS,
                    AiSettings.MAX_COVER_EVIDENCE_CHAPTERS,
                ),
        )
}
