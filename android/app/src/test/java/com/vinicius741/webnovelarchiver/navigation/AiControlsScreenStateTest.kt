package com.vinicius741.webnovelarchiver.navigation

import com.vinicius741.webnovelarchiver.ai.AiCoverDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiControlsScreenStateTest {
    @Test
    fun `persisted one-step prompt replaces stale in-memory preview`() {
        val state = AiControlsScreenState()
        state.coverDrafts["story-1"] = AiCoverDraft("old prompt", byteArrayOf(1), "image/png")
        state.coverPrompts["story-1"] = "old prompt"

        val changed = state.replaceCoverPreviewWithPrompt("story-1", "new prompt")

        assertTrue(changed)
        assertNull(state.coverDrafts["story-1"])
        assertEquals("new prompt", state.coverPrompts["story-1"])
        assertFalse(state.replaceCoverPreviewWithPrompt("story-1", "new prompt"))
    }
}
