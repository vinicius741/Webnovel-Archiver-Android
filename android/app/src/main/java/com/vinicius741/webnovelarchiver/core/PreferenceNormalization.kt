package com.vinicius741.webnovelarchiver.core

object PreferenceNormalization {
    private val chapterFilterModes = setOf("all", "hideNonDownloaded", "hideAboveBookmark")
    private val foldLayoutModes = setOf("auto", "cover", "inner")

    fun appSettings(settings: AppSettings): AppSettings = settings.copy(
        downloadConcurrency = settings.downloadConcurrency.coerceIn(
            SettingsValidation.CONCURRENCY_MIN,
            SettingsValidation.CONCURRENCY_MAX,
        ),
        downloadDelay = settings.downloadDelay.takeIf { it >= 0 } ?: AppSettings().downloadDelay,
        maxChaptersPerEpub = settings.maxChaptersPerEpub.coerceIn(
            SettingsValidation.MAX_CHAPTERS_PER_EPUB_MIN,
            SettingsValidation.MAX_CHAPTERS_PER_EPUB_MAX,
        ),
    )

    fun sourceDownloadSettings(settings: Map<String, SourceDownloadSettings>): MutableMap<String, SourceDownloadSettings> =
        settings.mapValues { (_, value) ->
            value.copy(
                concurrency = value.concurrency.coerceIn(
                    SettingsValidation.CONCURRENCY_MIN,
                    SettingsValidation.CONCURRENCY_MAX,
                ),
                delay = value.delay.takeIf { it >= 0 } ?: SourceDownloadSettings().delay,
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
        )

    fun ttsSettings(settings: TtsSettings): TtsSettings = settings.copy(
        pitch = settings.pitch.coerceIn(SettingsValidation.TTS_MIN, SettingsValidation.TTS_MAX),
        rate = settings.rate.coerceIn(SettingsValidation.TTS_MIN, SettingsValidation.TTS_MAX),
        chunkSize = settings.chunkSize.coerceAtLeast(SettingsValidation.TTS_CHUNK_SIZE_MIN),
    )
}
