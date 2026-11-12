package com.vinicius741.webnovelarchiver.feature.settings

import com.vinicius741.webnovelarchiver.domain.model.TtsProfile
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class TtsSpeechDraftTest {
    private val edited =
        TtsSettings(
            rate = 1.4f,
            pitch = 0.9f,
            contextReplay = false,
            announceChapters = false,
            chapterSkipButtons = true,
            phrasePauseMs = 75,
            sentencePauseMs = 600,
            paragraphPauseMs = 1_200,
            scenePauseMs = 3_000,
        )

    @Test
    fun roundTripsTheEditableFields() {
        assertEquals(edited, TtsSpeechDraft.of(edited).applyTo(TtsSettings()))
    }

    @Test
    fun keepsEngineVoiceAndProfilesSavedAfterTheDraftWasTaken() {
        val opened =
            edited.copy(
                enginePackage = "kokoro",
                voiceIdentifier = "af_bella",
                profiles = listOf(TtsProfile(id = "a", name = "Night"), TtsProfile(id = "b", name = "Day")),
                activeProfileId = "a",
            )
        val draft = TtsSpeechDraft.of(opened).copy(rate = 1.8f)
        // Meanwhile a profile was deleted and Kokoro was removed (back to the phone's engine).
        val latest = opened.copy(enginePackage = null, voiceIdentifier = null, profiles = opened.profiles.drop(1), activeProfileId = null)

        val saved = draft.applyTo(latest)

        assertEquals(latest.copy(rate = 1.8f), saved)
    }

    @Test
    fun aDraftTakenFromTheSavedSettingsChangesNothing() {
        val settings = edited.copy(enginePackage = "com.google.android.tts", profiles = listOf(TtsProfile(id = "a", name = "Night")))
        assertEquals(settings, TtsSpeechDraft.of(settings).applyTo(settings))
    }
}
