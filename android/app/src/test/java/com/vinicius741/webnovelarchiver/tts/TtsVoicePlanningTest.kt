package com.vinicius741.webnovelarchiver.tts

import android.speech.tts.Voice
import org.junit.Assert.assertEquals
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
}
