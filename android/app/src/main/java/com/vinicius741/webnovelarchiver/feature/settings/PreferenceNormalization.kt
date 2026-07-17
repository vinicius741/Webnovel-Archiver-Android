package com.vinicius741.webnovelarchiver.feature.settings

import com.vinicius741.webnovelarchiver.domain.model.AppSettings
import com.vinicius741.webnovelarchiver.domain.model.ChapterFilterSettings
import com.vinicius741.webnovelarchiver.domain.model.DisplayPreferences
import com.vinicius741.webnovelarchiver.domain.model.SourceDownloadSettings
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import com.vinicius741.webnovelarchiver.ui.layout.ScreenLayoutPlanning

object PreferenceNormalization {
    private val chapterFilterModes = setOf("all", "hideNonDownloaded", "hideAboveBookmark")
    private val foldLayoutModes = setOf("auto", "cover", "inner")

    /** Allowed Library sort option keys — mirrors the Sort dialog option list in LibraryFilters.
     *  Includes the legacy "updated" alias so old persisted values normalize to "lastUpdated". */
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
            downloadConcurrency =
                settings.downloadConcurrency.coerceIn(
                    SettingsValidation.CONCURRENCY_MIN,
                    SettingsValidation.CONCURRENCY_MAX,
                ),
            downloadDelay = minDelay,
            downloadDelayMax = maxDelay,
            maxChaptersPerEpub =
                settings.maxChaptersPerEpub.coerceIn(
                    SettingsValidation.MAX_CHAPTERS_PER_EPUB_MIN,
                    SettingsValidation.MAX_CHAPTERS_PER_EPUB_MAX,
                ),
        )
    }

    fun sourceDownloadSettings(settings: Map<String, SourceDownloadSettings>): MutableMap<String, SourceDownloadSettings> =
        settings
            .mapValues { (_, value) ->
                val minDelay = value.delay.takeIf { it >= 0 } ?: SourceDownloadSettings().delay
                val maxDelay = value.delayMax.takeIf { it >= minDelay } ?: minDelay
                value.copy(
                    concurrency =
                        value.concurrency.coerceIn(
                            SettingsValidation.CONCURRENCY_MIN,
                            SettingsValidation.CONCURRENCY_MAX,
                        ),
                    delay = minDelay,
                    delayMax = maxDelay,
                )
            }.toMutableMap()

    fun chapterFilterSettings(settings: ChapterFilterSettings): ChapterFilterSettings =
        settings.copy(
            filterMode = settings.filterMode.takeIf { it in chapterFilterModes } ?: ChapterFilterSettings().filterMode,
        )

    fun displayPreferences(preferences: DisplayPreferences): DisplayPreferences =
        preferences.copy(
            activeThemeId = preferences.activeThemeId.ifBlank { DisplayPreferences().activeThemeId },
            foldLayoutMode = preferences.foldLayoutMode.takeIf { it in foldLayoutModes } ?: DisplayPreferences().foldLayoutMode,
            screenLayoutMode =
                preferences.screenLayoutMode.takeIf { it in ScreenLayoutPlanning.screenLayoutModes }
                    ?: DisplayPreferences().screenLayoutMode,
            readerFontScale = preferences.readerFontScale.coerceIn(READER_FONT_SCALE_MIN, READER_FONT_SCALE_MAX),
            // A blank persisted value means "never set" (resolves to All on render); normalize all
            // whitespace to that same state rather than carrying it through.
            libraryTabId = preferences.libraryTabId?.takeIf { it.isNotBlank() },
            // Map the legacy "updated" key onto the canonical "lastUpdated"; fall back to the default
            // for any unknown/blank value so a corrupted or hand-edited pref can't break the Library.
            librarySortOption =
                preferences.librarySortOption
                    .takeIf { it.isNotBlank() && it in librarySortOptions }
                    ?.let { if (it == "updated") "lastUpdated" else it }
                    ?: DisplayPreferences().librarySortOption,
        )

    fun ttsSettings(settings: TtsSettings): TtsSettings =
        settings.copy(
            pitch = settings.pitch.coerceIn(SettingsValidation.TTS_MIN, SettingsValidation.TTS_MAX),
            rate = settings.rate.coerceIn(SettingsValidation.TTS_MIN, SettingsValidation.TTS_MAX),
            chunkSize = settings.chunkSize.coerceAtLeast(SettingsValidation.TTS_CHUNK_SIZE_MIN),
        )
}
