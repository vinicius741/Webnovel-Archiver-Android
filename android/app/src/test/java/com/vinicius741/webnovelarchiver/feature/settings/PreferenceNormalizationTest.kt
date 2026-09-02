package com.vinicius741.webnovelarchiver.feature.settings

import com.google.gson.Gson
import com.vinicius741.webnovelarchiver.domain.model.AiSettings
import com.vinicius741.webnovelarchiver.domain.model.AppSettings
import com.vinicius741.webnovelarchiver.domain.model.ChapterFilterSettings
import com.vinicius741.webnovelarchiver.domain.model.DisplayPreferences
import com.vinicius741.webnovelarchiver.domain.model.SourceDownloadSettings
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import com.vinicius741.webnovelarchiver.domain.model.UpdateFollowSettings
import com.vinicius741.webnovelarchiver.domain.settings.PreferenceNormalization
import org.junit.Assert.assertEquals
import org.junit.Test

class PreferenceNormalizationTest {
    @Test
    fun legacySettingsWithoutParallelSourceFieldMigrateToTwoLanes() {
        val legacy =
            Gson().fromJson(
                """{"downloadConcurrency":1,"downloadDelay":500,"downloadDelayMax":500,"maxChaptersPerEpub":150}""",
                AppSettings::class.java,
            )

        assertEquals(2, PreferenceNormalization.appSettings(legacy).maxParallelSources)
    }

    @Test
    fun appSettingsFillInvalidLegacyValuesWithDefaults() {
        val settings =
            PreferenceNormalization.appSettings(
                AppSettings(downloadConcurrency = 0, downloadDelay = -1, downloadDelayMax = -1, maxChaptersPerEpub = 0),
            )

        assertEquals(
            AppSettings(
                downloadConcurrency = 1,
                maxParallelSources = 2,
                downloadDelay = 500,
                downloadDelayMax = 500,
                maxChaptersPerEpub = 10,
            ),
            settings,
        )
        assertEquals(
            AppSettings(maxParallelSources = 2, downloadDelay = 1200, downloadDelayMax = 1200),
            PreferenceNormalization.appSettings(AppSettings(downloadDelay = 1200, downloadDelayMax = 0)),
        )
        assertEquals(
            10,
            PreferenceNormalization.appSettings(AppSettings(downloadConcurrency = 500)).downloadConcurrency,
        )
        assertEquals(
            10,
            PreferenceNormalization.appSettings(AppSettings(maxParallelSources = 500)).maxParallelSources,
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
    fun sourceSettingKeyMigrationPrefersExistingStableIdsAndPreservesUnknownKeys() {
        val stable = SourceDownloadSettings(concurrency = 2)
        val legacyDuplicate = SourceDownloadSettings(concurrency = 3)
        val unknown = SourceDownloadSettings(delay = 900, delayMax = 900)

        val migrated =
            PreferenceNormalization.migrateSourceDownloadSettingKeys(
                mapOf(
                    "Scribble Hub" to legacyDuplicate,
                    "scribble_hub" to stable,
                    "future_source" to unknown,
                ),
            ) { key ->
                when (key) {
                    "Scribble Hub", "scribble_hub" -> "scribble_hub"
                    else -> null
                }
            }

        assertEquals(stable, migrated["scribble_hub"])
        assertEquals(unknown, migrated["future_source"])
        assertEquals(2, migrated.size)
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
    fun updateFollowSettingsClampThresholdIntoPickerRange() {
        assertEquals(5, PreferenceNormalization.updateFollowSettings(UpdateFollowSettings()).thresholdChapters)
        assertEquals(1, PreferenceNormalization.updateFollowSettings(UpdateFollowSettings(thresholdChapters = 0)).thresholdChapters)
        assertEquals(25, PreferenceNormalization.updateFollowSettings(UpdateFollowSettings(thresholdChapters = 99)).thresholdChapters)
        val legacyJson = Gson().fromJson("""{"thresholdChapters":10}""", UpdateFollowSettings::class.java)
        assertEquals(10, PreferenceNormalization.updateFollowSettings(legacyJson).thresholdChapters)
    }

    @Test
    fun ttsSettingsFillInvalidLegacyValuesWithDefaults() {
        val settings =
            PreferenceNormalization.ttsSettings(
                TtsSettings(pitch = 0f, rate = -1f),
            )

        assertEquals(TtsSettings(pitch = 0.5f, rate = 0.5f), settings)
        assertEquals(
            TtsSettings(pitch = 2.0f, rate = 2.0f),
            PreferenceNormalization.ttsSettings(TtsSettings(pitch = 10f, rate = 10f)),
        )
    }

    @Test
    fun aiSettingsTrimKeyBlankModelFallsBackToDefaultAndLegacyJsonDeserializes() {
        assertEquals(
            AiSettings(),
            PreferenceNormalization.aiSettings(AiSettings(apiKey = "  ", descriptionModel = "   ")),
        )
        assertEquals(
            AiSettings(apiKey = "sk-or-v1-x", descriptionModel = "a/b"),
            PreferenceNormalization.aiSettings(AiSettings(apiKey = " sk-or-v1-x ", descriptionModel = " a/b ")),
        )
        // Persisted documents written before the feature existed keep deserializing.
        val legacy = Gson().fromJson("""{"apiKey":"sk-or-v1-old"}""", AiSettings::class.java)
        assertEquals("sk-or-v1-old", legacy.apiKey)
        assertEquals(AiSettings.DEFAULT_DESCRIPTION_MODEL, PreferenceNormalization.aiSettings(legacy).descriptionModel)
    }

    @Test
    fun aiSettingsNormalizesImageModelWithDefaultFallback() {
        assertEquals(
            AiSettings.DEFAULT_IMAGE_MODEL,
            PreferenceNormalization.aiSettings(AiSettings(imageModel = "   ")).imageModel,
        )
        assertEquals(
            "x-ai/grok-imagine-image-2.0",
            PreferenceNormalization.aiSettings(AiSettings(imageModel = " x-ai/grok-imagine-image-2.0 ")).imageModel,
        )
        // Documents persisted before cover generation shipped have no field: Gson supplies the default.
        val legacy = Gson().fromJson("""{"apiKey":"k"}""", AiSettings::class.java)
        assertEquals(AiSettings.DEFAULT_IMAGE_MODEL, legacy.imageModel)
    }
}
