package com.vinicius741.webnovelarchiver.feature.ai

import com.vinicius741.webnovelarchiver.ai.AiCoverDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiAttentionTest {
    private val attention = AiAttention()

    @Test
    fun theNewestFailureComesLastAndADismissalLastsUntilTheNextFailure() {
        attention.recordFailure("a", AiControlsTab.COVER, "first")
        attention.recordFailure("b", AiControlsTab.DESCRIPTION, "second")
        attention.recordFailure("a", AiControlsTab.COVER, "again")
        val coverKey = AiJobChipPlanning.failureKey("a", AiControlsTab.COVER)

        assertEquals(
            listOf(AiJobChipPlanning.failureKey("b", AiControlsTab.DESCRIPTION), coverKey),
            attention.state.value.failures.keys
                .toList(),
        )
        assertEquals(
            "again",
            attention.state.value.failures[coverKey]
                ?.message,
        )

        attention.dismiss(coverKey)
        assertTrue(coverKey in attention.state.value.dismissed)
        attention.recordFailure("a", AiControlsTab.COVER, "third")
        assertTrue(coverKey !in attention.state.value.dismissed)
    }

    @Test
    fun lookingAtATabClearsOnlyThatTabsMarkers() {
        attention.markUnseen("a", AiControlsTab.DESCRIPTION)
        attention.markUnseen("a", AiControlsTab.CHARACTERS)
        attention.recordFailure("a", AiControlsTab.DESCRIPTION, "boom")
        attention.recordFailure("a", AiControlsTab.COVER, "boom")
        attention.dismiss(AiJobChipPlanning.readyKey("a", AiControlsTab.DESCRIPTION))

        attention.markSeen("a", AiControlsTab.DESCRIPTION)

        val state = attention.state.value
        assertTrue(state.unseenDescriptions.isEmpty())
        assertEquals(setOf("a"), state.unseenCharacters)
        assertEquals(listOf(AiJobChipPlanning.failureKey("a", AiControlsTab.COVER)), state.failures.keys.toList())
        assertTrue(state.dismissed.isEmpty())
    }

    @Test
    fun typingKeepsThePromptRevisionAndOtherWritesChangeIt() {
        attention.setCoverPrompt("a", "a ca", typed = true)
        assertEquals(CoverPrompt("a ca", 0), attention.state.value.coverPrompts["a"])

        attention.setCoverPrompt("a", "a castle", typed = true)
        assertEquals(
            0,
            attention.state.value.coverPrompts["a"]
                ?.revision,
        )

        attention.setCoverPrompt("a", "a dragon")
        val set = attention.state.value.coverPrompts["a"]
        assertNotEquals(0, set?.revision)

        // Setting the same text again is not a change.
        attention.setCoverPrompt("a", "a dragon")
        assertEquals(set, attention.state.value.coverPrompts["a"])

        attention.setCoverPrompt("a", null)
        assertNull(attention.state.value.coverPrompts["a"])
    }

    @Test
    fun aNewerPromptReplacesThePreview() {
        attention.setCoverDraft("a", AiCoverDraft("old", byteArrayOf(1), null))

        attention.replaceCoverPreviewWithPrompt("a", "new")

        assertNull(attention.state.value.coverDrafts["a"])
        assertEquals(
            "new",
            attention.state.value.coverPrompts["a"]
                ?.text,
        )
    }
}
