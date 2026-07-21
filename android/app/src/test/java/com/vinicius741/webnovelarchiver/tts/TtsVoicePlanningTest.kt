package com.vinicius741.webnovelarchiver.tts

import android.speech.tts.Voice
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TtsVoicePlanningTest {
    private val voices =
        listOf(
            VoiceInfo("pt-br-local", "pt-br-x-ptb-local", "pt-BR", 1, 1),
            VoiceInfo("en-us-local", "en-us-x-sfg-local", "en-US", 1, 1),
            VoiceInfo("en-gb-local", "en-gb-x-gbb-local", "en-GB", 1, 1),
        )

    @Test
    fun exposesUniqueLanguageFilters() {
        assertEquals(listOf("en", "pt"), TtsVoicePlanning.languageCodes(voices))
    }

    @Test
    fun filtersByLanguageAndHumanReadableSearchText() {
        assertEquals(2, TtsVoicePlanning.filter(voices, "English", "en", Locale.US).size)
        assertEquals(listOf("pt-br-local"), TtsVoicePlanning.filter(voices, "Brazil", null, Locale.US).map { it.identifier })
    }

    @Test
    fun voiceMetadataLabelShowsQualityAndLatency() {
        val voice = VoiceInfo("id", "name", "en-US", Voice.QUALITY_HIGH, Voice.LATENCY_LOW)

        assertEquals("High quality · Low latency", TtsVoicePlanning.voiceMetadataLabel(voice))
    }

    @Test
    fun toVoiceInfoNullVoicesReturnsEmpty() {
        // A non-null Voice set can't be exercised in a pure JVM unit test: android.speech.tts.Voice
        // accessors (getName, hashCode, ...) are not mocked outside an instrumentation run. The
        // sort/filter/map path is covered by TtsEngine.availableVoices on-device.
        assertTrue(TtsVoicePlanning.toVoiceInfo(null).isEmpty())
    }

    @Test
    fun resolveVoiceFallsBackToDefaultLanguageWhenNoPreference() {
        val settings = TtsSettings(voiceIdentifier = null)

        val result = TtsVoicePlanning.resolveVoice(emptySet(), settings)

        assertEquals(VoiceSelectionResult.UseDefaultLanguage, result)
    }

    @Test
    fun resolveVoiceNullVoicesAndNoPreferenceUsesDefaultLanguage() {
        val settings = TtsSettings(voiceIdentifier = null)

        assertEquals(VoiceSelectionResult.UseDefaultLanguage, TtsVoicePlanning.resolveVoice(null, settings))
    }

    @Test
    fun resolveVoiceNullVoicesWithIdentifierReportsMissing() {
        // A non-null Voice set can't be exercised in a pure JVM unit test (Voice accessors are not
        // mocked); with null voices the identifier cannot match, so the result is VoiceMissing.
        val settings = TtsSettings(voiceIdentifier = "anything")

        val result = TtsVoicePlanning.resolveVoice(null, settings)

        assertTrue(result is VoiceSelectionResult.VoiceMissing)
        assertEquals("anything", (result as VoiceSelectionResult.VoiceMissing).identifier)
    }
}
