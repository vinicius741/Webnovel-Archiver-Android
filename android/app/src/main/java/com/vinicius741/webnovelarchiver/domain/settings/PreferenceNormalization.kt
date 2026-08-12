package com.vinicius741.webnovelarchiver.domain.settings

import com.vinicius741.webnovelarchiver.domain.model.AppSettings
import com.vinicius741.webnovelarchiver.domain.model.ChapterFilterSettings
import com.vinicius741.webnovelarchiver.domain.model.DisplayPreferences
import com.vinicius741.webnovelarchiver.domain.model.SourceDownloadSettings
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

    private val chapterFilterModes = setOf("all", "hideNonDownloaded", "hideAboveBookmark")
    private val foldLayoutModes = setOf("auto", "cover", "inner")
    private val screenLayoutModes = setOf("auto", "cover", "inner")
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
            activeThemeId = preferences.activeThemeId.ifBlank { DisplayPreferences().activeThemeId },
            foldLayoutMode = preferences.foldLayoutMode.takeIf { it in foldLayoutModes } ?: DisplayPreferences().foldLayoutMode,
            screenLayoutMode = preferences.screenLayoutMode.takeIf { it in screenLayoutModes } ?: DisplayPreferences().screenLayoutMode,
            readerFontScale = preferences.readerFontScale.coerceIn(READER_FONT_SCALE_MIN, READER_FONT_SCALE_MAX),
            libraryTabId = preferences.libraryTabId?.takeIf { it.isNotBlank() },
            librarySortOption =
                preferences.librarySortOption
                    .takeIf { it.isNotBlank() && it in librarySortOptions }
                    ?.let { if (it == "updated") "lastUpdated" else it }
                    ?: DisplayPreferences().librarySortOption,
        )

    fun ttsSettings(settings: TtsSettings): TtsSettings =
        settings.copy(
            pitch = settings.pitch.coerceIn(TTS_MIN, TTS_MAX),
            rate = settings.rate.coerceIn(TTS_MIN, TTS_MAX),
        )
}
