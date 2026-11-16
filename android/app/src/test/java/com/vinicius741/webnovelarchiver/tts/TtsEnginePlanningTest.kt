package com.vinicius741.webnovelarchiver.tts

import com.vinicius741.webnovelarchiver.domain.model.TtsProfile
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
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

    @Test
    fun aMissingBuiltInModelBorrowsTheGoogleProfile() {
        val google =
            TtsProfile(
                id = "g",
                name = "Google",
                enginePackage = "com.google.android.tts",
                voiceIdentifier = "en-us-x-tpf-local",
                pitch = 0.9f,
            )
        val other = TtsProfile(id = "o", name = "Other", enginePackage = "com.other.tts", voiceIdentifier = "other")
        val kokoro = TtsProfile(id = "k", name = "Kokoro", enginePackage = "builtin:phonon-31m-en")
        val settings =
            TtsSettings(
                enginePackage = "builtin:phonon-31m-en",
                voiceIdentifier = "phonon-31m-en:Harper",
                rate = 1.8f,
                profiles = listOf(kokoro, other, google),
            )
        val isBuiltIn = { engine: String? -> engine?.startsWith("builtin:") == true }
        val standIn = TtsEnginePlanning.standIn(settings, setOf("com.google.android.tts", "com.other.tts"), isBuiltIn)
        assertEquals("com.google.android.tts", standIn.enginePackage)
        assertEquals("en-us-x-tpf-local", standIn.voiceIdentifier)
        assertEquals(0.9f, standIn.pitch)
        // The listening speed stays the user's.
        assertEquals(1.8f, standIn.rate)
        // Google not installed: another usable profile; none at all: the phone's engine.
        assertEquals("com.other.tts", TtsEnginePlanning.standIn(settings, setOf("com.other.tts"), isBuiltIn).enginePackage)
        val bare = TtsEnginePlanning.standIn(settings.copy(profiles = listOf(kokoro)), setOf("com.google.android.tts"), isBuiltIn)
        assertNull(bare.enginePackage)
        assertNull(bare.voiceIdentifier)
    }
}
