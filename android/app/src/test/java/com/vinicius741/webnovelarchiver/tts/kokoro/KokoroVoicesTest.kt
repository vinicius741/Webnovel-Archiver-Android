package com.vinicius741.webnovelarchiver.tts.kokoro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KokoroVoicesTest {
    @Test
    fun `every speaker of the model is offered once, in model order`() {
        assertEquals((0..10).toList(), KokoroVoices.all.map { it.speakerId })
        assertEquals(
            KokoroVoices.all.size,
            KokoroVoices.all
                .map { it.code }
                .toSet()
                .size,
        )
        assertEquals(11, KokoroVoices.voiceInfos().size)
    }

    @Test
    fun `identifiers round trip and name the model version`() {
        KokoroVoices.all.forEach { voice ->
            val id = KokoroVoices.identifier(voice)
            assertTrue(id.startsWith("kokoro-en-v0_19:"))
            assertTrue(KokoroVoices.isKokoroIdentifier(id))
            assertEquals(voice, KokoroVoices.forIdentifier(id))
        }
        assertEquals(9, KokoroVoices.forIdentifier("kokoro-en-v0_19:bm_george")?.speakerId)
    }

    @Test
    fun `android voice names are not kokoro voices`() {
        assertFalse(KokoroVoices.isKokoroIdentifier("en-us-x-sfg-local"))
        assertFalse(KokoroVoices.isKokoroIdentifier(null))
        assertNull(KokoroVoices.forIdentifier("en-us-x-sfg-local"))
        assertNull(KokoroVoices.forIdentifier("kokoro-en-v0_19:unknown"))
    }

    @Test
    fun `british voices are filed under british english`() {
        val british = KokoroVoices.voiceInfos().filter { it.language == "en-GB" }.map { it.name.substringBefore(' ') }
        assertEquals(listOf("Emma", "Isabella", "George", "Lewis"), british)
    }

    @Test
    fun `only the built-in engine id is kokoro`() {
        assertTrue(KokoroVoices.isEngine(KokoroVoices.ENGINE_ID))
        assertFalse(KokoroVoices.isEngine("com.google.android.tts"))
        assertFalse(KokoroVoices.isEngine(null))
    }
}
