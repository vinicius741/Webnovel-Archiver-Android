package com.vinicius741.webnovelarchiver.feature.settings

import com.vinicius741.webnovelarchiver.domain.model.AppSettings
import com.vinicius741.webnovelarchiver.domain.model.ChapterFilterSettings
import com.vinicius741.webnovelarchiver.domain.model.DisplayPreferences
import com.vinicius741.webnovelarchiver.domain.model.SourceDownloadSettings
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class PreferenceNormalizationTest {
    @Test
    fun appSettingsFillInvalidLegacyValuesWithDefaults() {
        val settings =
            PreferenceNormalization.appSettings(
                AppSettings(downloadConcurrency = 0, downloadDelay = -1, downloadDelayMax = -1, maxChaptersPerEpub = 0),
            )

        assertEquals(AppSettings(downloadConcurrency = 1, downloadDelay = 500, downloadDelayMax = 500, maxChaptersPerEpub = 10), settings)
        assertEquals(
            AppSettings(downloadDelay = 1200, downloadDelayMax = 1200),
            PreferenceNormalization.appSettings(AppSettings(downloadDelay = 1200, downloadDelayMax = 0)),
        )
        assertEquals(
            10,
            PreferenceNormalization.appSettings(AppSettings(downloadConcurrency = 500)).downloadConcurrency,
        )
        assertEquals(
            1000,
            PreferenceNormalization.appSettings(AppSettings(maxChaptersPerEpub = 5000)).maxChaptersPerEpub,
        )
    }

    @Test
    fun sourceDownloadSettingsNormalizePerProviderValues() {
        val settings =
            PreferenceNormalization.sourceDownloadSettings(
                mapOf(
                    "RoyalRoad" to SourceDownloadSettings(concurrency = 0, delay = -100, delayMax = -1),
                    "ScribbleHub" to SourceDownloadSettings(concurrency = 500, delay = 20, delayMax = 40),
                    "Legacy" to SourceDownloadSettings(concurrency = 1, delay = 1200, delayMax = 0),
                ),
            )

        assertEquals(SourceDownloadSettings(concurrency = 1, delay = 500, delayMax = 500), settings["RoyalRoad"])
        assertEquals(SourceDownloadSettings(concurrency = 10, delay = 20, delayMax = 40), settings["ScribbleHub"])
        assertEquals(SourceDownloadSettings(concurrency = 1, delay = 1200, delayMax = 1200), settings["Legacy"])
    }

    @Test
    fun invalidChapterFilterAndFoldModesFallbackToDefaults() {
        assertEquals(
            ChapterFilterSettings(filterMode = "all"),
            PreferenceNormalization.chapterFilterSettings(ChapterFilterSettings(filterMode = "legacy")),
        )
        assertEquals(
            DisplayPreferences(activeThemeId = "obsidian", foldLayoutMode = "auto"),
            PreferenceNormalization.displayPreferences(DisplayPreferences(activeThemeId = "", foldLayoutMode = "legacy")),
        )
    }

    @Test
    fun ttsSettingsFillInvalidLegacyValuesWithDefaults() {
        val settings =
            PreferenceNormalization.ttsSettings(
                TtsSettings(pitch = 0f, rate = -1f, chunkSize = 0),
            )

        assertEquals(TtsSettings(pitch = 0.5f, rate = 0.5f, chunkSize = 100), settings)
        assertEquals(
            TtsSettings(pitch = 2.0f, rate = 2.0f, chunkSize = 1000),
            PreferenceNormalization.ttsSettings(TtsSettings(pitch = 10f, rate = 10f, chunkSize = 1000)),
        )
    }
}
