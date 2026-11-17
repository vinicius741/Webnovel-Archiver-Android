package com.vinicius741.webnovelarchiver.feature.settings

import com.google.gson.Gson
import com.vinicius741.webnovelarchiver.domain.model.AiSettings
import com.vinicius741.webnovelarchiver.domain.model.AppSettings
import com.vinicius741.webnovelarchiver.domain.model.ChapterFilterSettings
import com.vinicius741.webnovelarchiver.domain.model.DisplayPreferences
import com.vinicius741.webnovelarchiver.domain.model.SourceDownloadSettings
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import com.vinicius741.webnovelarchiver.domain.settings.PreferenceNormalization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PreferenceNormalizationTest {
    @Test
    fun legacyTypeSafeKeyIsIgnoredAndDroppedWhileOpenRouterSettingsSurvive() {
        val gson = Gson()
        val legacy =
            gson.fromJson(
                """{"apiKey":" openrouter-key ","typeSafeApiKey":"retired-key","descriptionModel":"chosen-model","coverEvidenceChapters":7}""",
                AiSettings::class.java,
            )
        val normalized = PreferenceNormalization.aiSettings(legacy)
        assertEquals("openrouter-key", normalized.apiKey)
        assertEquals("chosen-model", normalized.descriptionModel)
        assertEquals(7, normalized.coverEvidenceChapters)
        assertFalse(gson.toJson(normalized).contains("typeSafeApiKey"))
        val typeSafeOnly = gson.fromJson("""{"typeSafeApiKey":"retired-key"}""", AiSettings::class.java)
        assertNull(PreferenceNormalization.aiSettings(typeSafeOnly).apiKey)
    }

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
    fun invalidChapterFilterAndFoldModesFallbackToDefaults() {
        assertEquals(
            ChapterFilterSettings(filterMode = "all"),
            PreferenceNormalization.chapterFilterSettings(ChapterFilterSettings(filterMode = "legacy")),
        )
        assertEquals(
            DisplayPreferences(foldLayoutMode = "auto"),
            PreferenceNormalization.displayPreferences(DisplayPreferences(foldLayoutMode = "legacy")),
        )
    }

    @Test
    fun unknownLibraryViewModeFallsBackToList() {
        assertEquals("list", PreferenceNormalization.displayPreferences(DisplayPreferences(libraryViewMode = "shelf")).libraryViewMode)
        assertEquals("covers", PreferenceNormalization.displayPreferences(DisplayPreferences(libraryViewMode = "covers")).libraryViewMode)
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
    fun ttsSettingsWithRetiredProfilesStillLoad() {
        val legacy =
            Gson().fromJson(
                """{"rate":1.8,"enginePackage":"  ","profiles":[{"id":"a","name":"Night"}],"activeProfileId":"a"}""",
                TtsSettings::class.java,
            )

        val settings = PreferenceNormalization.ttsSettings(legacy)

        assertNull(settings.enginePackage)
        assertEquals(1.8f, settings.rate)
    }

    @Test
    fun kokoroV019SettingsLoadAsKokoroV1() {
        val settings = PreferenceNormalization.ttsSettings(TtsSettings(enginePackage = "builtin:kokoro-en-v0_19"))
        assertEquals("builtin:kokoro-multi-lang-v1_0", settings.enginePackage)
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

    @Test
    fun aiSettingsClampsCoverEvidenceChaptersAndLegacyJsonKeepsTheDefault() {
        assertEquals(
            AiSettings.MIN_COVER_EVIDENCE_CHAPTERS,
            PreferenceNormalization.aiSettings(AiSettings(coverEvidenceChapters = 0)).coverEvidenceChapters,
        )
        assertEquals(
            AiSettings.MAX_COVER_EVIDENCE_CHAPTERS,
            PreferenceNormalization.aiSettings(AiSettings(coverEvidenceChapters = 99)).coverEvidenceChapters,
        )
        assertEquals(12, PreferenceNormalization.aiSettings(AiSettings(coverEvidenceChapters = 12)).coverEvidenceChapters)
        // Settings persisted before the TypeSafe selection count existed keep the constructor default.
        val legacy = Gson().fromJson("""{"apiKey":"k"}""", AiSettings::class.java)
        assertEquals(AiSettings.DEFAULT_COVER_EVIDENCE_CHAPTERS, legacy.coverEvidenceChapters)
    }

    @Test
    fun aiSettingsDefaultsTheDecisionModelForLegacyAndBlankValues() {
        val legacy = Gson().fromJson("""{"apiKey":"k"}""", AiSettings::class.java)
        assertEquals(AiSettings.DEFAULT_DECISION_MODEL, PreferenceNormalization.aiSettings(legacy).decisionModel)
        assertEquals(AiSettings.DEFAULT_DECISION_MODEL, PreferenceNormalization.aiSettings(AiSettings(decisionModel = " ")).decisionModel)
        assertEquals("liquid/d1", PreferenceNormalization.aiSettings(AiSettings(decisionModel = " liquid/d1 ")).decisionModel)
    }

    @Test
    fun removedChaptersNoticeHiddenMapToleratesNullAndDropsEmpty() {
        val fromNull = PreferenceNormalization.displayPreferences(DisplayPreferences(removedChaptersNoticeHiddenThrough = null))
        assertEquals(null, fromNull.removedChaptersNoticeHiddenThrough)
        val fromEmpty = PreferenceNormalization.displayPreferences(DisplayPreferences(removedChaptersNoticeHiddenThrough = emptyMap()))
        assertEquals(null, fromEmpty.removedChaptersNoticeHiddenThrough)
        val kept = PreferenceNormalization.displayPreferences(DisplayPreferences(removedChaptersNoticeHiddenThrough = mapOf("rr_1" to 9L)))
        assertEquals(mapOf("rr_1" to 9L), kept.removedChaptersNoticeHiddenThrough)
    }

    @Test
    fun `the Chapters tab's not-downloaded filter is kept`() {
        assertEquals(
            ChapterFilterSettings(filterMode = "notDownloaded"),
            PreferenceNormalization.chapterFilterSettings(ChapterFilterSettings(filterMode = "notDownloaded")),
        )
    }
}
