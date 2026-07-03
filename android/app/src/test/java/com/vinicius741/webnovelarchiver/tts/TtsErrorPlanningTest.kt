package com.vinicius741.webnovelarchiver.tts

import android.speech.tts.TextToSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsErrorPlanningTest {
    @Test
    fun synthesisCodeNameMapsKnownCodes() {
        assertEquals("ERROR_NETWORK", TtsErrorPlanning.synthesisCodeName(TextToSpeech.ERROR_NETWORK))
        assertEquals("ERROR_SYNTHESIS", TtsErrorPlanning.synthesisCodeName(TextToSpeech.ERROR_SYNTHESIS))
        assertEquals("ERROR_UNKNOWN", TtsErrorPlanning.synthesisCodeName(null))
    }

    @Test
    fun logMessageIncludesSynthesisCodeAndDetail() {
        val message =
            TtsErrorPlanning.logMessage(
                TtsPlaybackError(
                    kind = TtsPlaybackErrorKind.SynthesisFailed,
                    code = TextToSpeech.ERROR_NETWORK_TIMEOUT,
                    detail = "chapter_chunk_1",
                ),
            )

        assertTrue(message.contains("ERROR_NETWORK_TIMEOUT"))
        assertTrue(message.contains("chapter_chunk_1"))
    }

    @Test
    fun watchdogTimeoutUsesGenerousBounds() {
        assertEquals(30_000L, TtsWatchdogPlanning.timeoutMs(textLength = 10, rate = 1f))
        assertEquals(300_000L, TtsWatchdogPlanning.timeoutMs(textLength = 10_000, rate = 0.5f))
        assertTrue(TtsWatchdogPlanning.timeoutMs(textLength = 1_000, rate = 2f) in 30_000L..300_000L)
    }
}
