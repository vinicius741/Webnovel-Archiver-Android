package com.vinicius741.webnovelarchiver.tts

import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsProfilePlanningTest {
    private val tuned =
        TtsSettings(
            pitch = 1.1f,
            rate = 1.8f,
            voiceIdentifier = "en-us-x-sfg-local",
            enginePackage = "com.google.android.tts",
            phrasePauseMs = 50L,
            sentencePauseMs = 300L,
            paragraphPauseMs = 600L,
            scenePauseMs = 850L,
            announceChapters = false,
        )

    @Test
    fun `saving then applying a profile brings back every voice value`() {
        val saved = TtsProfilePlanning.save(tuned, "Night") { "id-1" }
        val profile = saved.profiles.single()
        assertEquals("id-1", saved.activeProfileId)

        val changed = saved.copy(pitch = 0.7f, rate = 1.0f, voiceIdentifier = null, enginePackage = null, sentencePauseMs = 0L)
        val restored = TtsProfilePlanning.apply(changed, profile)

        assertEquals(saved, restored)
        assertTrue(TtsProfilePlanning.matches(profile, restored))
    }

    @Test
    fun `profiles leave playback behavior alone`() {
        val profile = TtsProfilePlanning.save(tuned, "Night") { "id-1" }.profiles.single()
        val other = TtsSettings(announceChapters = true, contextReplay = false, chapterSkipButtons = true)

        val applied = TtsProfilePlanning.apply(other, profile)

        assertTrue(applied.announceChapters)
        assertFalse(applied.contextReplay)
        assertTrue(applied.chapterSkipButtons)
    }

    @Test
    fun `saving under an existing name updates that profile in place`() {
        val first = TtsProfilePlanning.save(tuned, "Night") { "id-1" }
        val second = TtsProfilePlanning.save(first.copy(rate = 1.2f), "Day") { "id-2" }
        val updated = TtsProfilePlanning.save(second.copy(rate = 1.5f), "night") { "id-3" }

        assertEquals(listOf("id-1", "id-2"), updated.profiles.map { it.id })
        assertEquals(1.5f, updated.profiles.first().rate)
        assertEquals("id-1", updated.activeProfileId)
    }

    @Test
    fun `a changed setting no longer matches the active profile`() {
        val saved = TtsProfilePlanning.save(tuned, "Night") { "id-1" }

        val edited = saved.copy(sentencePauseMs = 400L)

        assertEquals("Night", TtsProfilePlanning.active(edited)?.name)
        assertFalse(TtsProfilePlanning.matches(TtsProfilePlanning.active(edited)!!, edited))
    }

    @Test
    fun `deleting the active profile clears it but keeps the settings`() {
        val saved = TtsProfilePlanning.save(tuned, "Night") { "id-1" }

        val deleted = TtsProfilePlanning.delete(saved, "id-1")

        assertTrue(deleted.profiles.isEmpty())
        assertNull(deleted.activeProfileId)
        assertEquals(tuned.rate, deleted.rate)
    }

    @Test
    fun `names are trimmed, collapsed and bounded`() {
        assertEquals("Late night", TtsProfilePlanning.cleanName("  Late   night "))
        assertNull(TtsProfilePlanning.cleanName("   "))
        assertEquals(TtsProfilePlanning.MAX_NAME_LENGTH, TtsProfilePlanning.cleanName("x".repeat(100))!!.length)
    }

    @Test
    fun `only engine, voice and pitch change the rendered sound`() {
        assertFalse(TtsProfilePlanning.soundsDifferent(tuned, tuned.copy(rate = 1.0f, sentencePauseMs = 0L)))
        assertTrue(TtsProfilePlanning.soundsDifferent(tuned, tuned.copy(enginePackage = "com.k2fsa.sherpa.onnx.tts.engine")))
        assertTrue(TtsProfilePlanning.soundsDifferent(tuned, tuned.copy(voiceIdentifier = null)))
        assertTrue(TtsProfilePlanning.soundsDifferent(tuned, tuned.copy(pitch = 1.0f)))
    }
}
