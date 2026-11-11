package com.vinicius741.webnovelarchiver.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtsEnginePlanningTest {
    private val google = TtsEngineInfo("com.google.android.tts", "Speech Recognition and Synthesis from Google")
    private val samsung = TtsEngineInfo("com.samsung.SMT", "Samsung text-to-speech engine")
    private val sherpa = TtsEngineInfo("com.k2fsa.sherpa.onnx.tts.engine", "sherpa-onnx TTS Engine")

    @Test
    fun `a chosen engine is used only while it is installed`() {
        val installed = setOf(google.packageName, sherpa.packageName)

        assertEquals(sherpa.packageName, TtsEnginePlanning.resolve(sherpa.packageName, installed))
        assertNull(TtsEnginePlanning.resolve(samsung.packageName, installed))
        assertNull(TtsEnginePlanning.resolve(null, installed))
    }

    @Test
    fun `engines are listed once each by name`() {
        val sorted = TtsEnginePlanning.sorted(listOf(sherpa, samsung, google, sherpa))

        assertEquals(listOf(samsung, sherpa, google), sorted)
    }

    @Test
    fun `labels name the engine or say why it is not in use`() {
        val engines = listOf(google, sherpa)

        assertEquals("Phone default (${google.label})", TtsEnginePlanning.label(null, engines, google.packageName))
        assertEquals("Phone default", TtsEnginePlanning.label(null, engines, null))
        assertEquals(sherpa.label, TtsEnginePlanning.label(sherpa.packageName, engines, google.packageName))
        assertEquals("Not installed: com.samsung.SMT", TtsEnginePlanning.label(samsung.packageName, engines, google.packageName))
    }
}
