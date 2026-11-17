package com.vinicius741.webnovelarchiver.tts

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
    fun aMissingBuiltInModelSpeaksWithThePhoneEngineAtTheUsersSpeed() {
        val settings = TtsSettings(enginePackage = "builtin:phonon-31m-en", voiceIdentifier = "phonon-31m-en:Harper", rate = 1.8f)
        val standIn = TtsEnginePlanning.standIn(settings)
        assertNull(standIn.enginePackage)
        assertNull(standIn.voiceIdentifier)
        assertEquals(1.8f, standIn.rate)
    }

    @Test
    fun kokoroV019SettingsMoveToKokoroV1KeepingTheVoiceWhenItExists() {
        val bella = TtsSettings(enginePackage = "builtin:kokoro-en-v0_19", voiceIdentifier = "kokoro-en-v0_19:af_bella", rate = 1.5f)
        val moved = TtsEnginePlanning.withoutRetiredEngine(bella)
        assertEquals("builtin:kokoro-multi-lang-v1_0", moved.enginePackage)
        assertEquals("kokoro-multi-lang-v1_0:af_bella", moved.voiceIdentifier)
        assertEquals(1.5f, moved.rate)
        // v0.19's blended default has no v1.0 twin: v1.0's default voice.
        val blend = bella.copy(voiceIdentifier = "kokoro-en-v0_19:af")
        assertNull(TtsEnginePlanning.withoutRetiredEngine(blend).voiceIdentifier)
        // British v0.19 voices are not in v1.0's American list either.
        assertNull(TtsEnginePlanning.withoutRetiredEngine(bella.copy(voiceIdentifier = "kokoro-en-v0_19:bf_emma")).voiceIdentifier)
        val phonon = TtsSettings(enginePackage = "builtin:phonon-31m-en")
        assertEquals(phonon, TtsEnginePlanning.withoutRetiredEngine(phonon))
    }

    @Test
    fun onlyGoogleHasAPitchControl() {
        assertEquals(true, TtsEnginePlanning.hasPitch("com.google.android.tts"))
        assertEquals(false, TtsEnginePlanning.hasPitch("builtin:phonon-31m-en"))
        assertEquals(false, TtsEnginePlanning.hasPitch(null))
    }
}
