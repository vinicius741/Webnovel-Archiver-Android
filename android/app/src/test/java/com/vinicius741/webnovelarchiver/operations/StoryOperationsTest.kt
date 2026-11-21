package com.vinicius741.webnovelarchiver.operations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryOperationsTest {
    private val operations = StoryOperations()

    @Test
    fun contentOperationIsClearedOnlyByItsOwnStoryAndKind() {
        operations.setContent("s1", StoryOperationKind.SYNC, "Syncing")

        assertFalse(operations.clearContent("s2", StoryOperationKind.SYNC))
        assertFalse(operations.clearContent("s1", StoryOperationKind.EPUB))
        assertEquals("Syncing", operations.contentFor("s1")?.message)
        assertNull(operations.contentFor("s2"))

        assertTrue(operations.clearContent("s1", StoryOperationKind.SYNC))
        assertNull(operations.content.value)
    }

    @Test
    fun contentAndAiSlotsAreIndependent() {
        operations.setContent("s1", StoryOperationKind.CLEANUP, "Cleaning")
        operations.setAi(StoryOperationState("s1", StoryOperationKind.AI_COVER, "Painting"))

        assertTrue(operations.clearContent("s1", StoryOperationKind.CLEANUP))
        assertEquals(StoryOperationKind.AI_COVER, operations.aiFor("s1")?.kind)
    }

    @Test
    fun lateAiProgressCannotTakeOverAnotherStorysJob() {
        operations.setAi(StoryOperationState("s1", StoryOperationKind.AI_DESCRIPTION, "Writing"))

        assertNull(operations.updateAi("s2", StoryOperationKind.AI_COVER, "Painting", startIfIdle = true))
        assertNull(operations.updateAi("s1", StoryOperationKind.AI_COVER, "Painting"))
        assertEquals("Still writing", operations.updateAi("s1", StoryOperationKind.AI_DESCRIPTION, "Still writing")?.message)
        assertEquals("Still writing", operations.ai.value?.message)
    }

    @Test
    fun idleAiSlotStartsOnlyWhenAsked() {
        assertNull(operations.updateAi("s1", StoryOperationKind.AI_COVER, "Painting"))
        assertEquals("s1", operations.updateAi("s1", StoryOperationKind.AI_COVER, "Painting", startIfIdle = true)?.storyId)
    }

    @Test
    fun clearingAiByKindReturnsWhatWasCleared() {
        operations.setAi(StoryOperationState("s3", StoryOperationKind.AI_COVER, "Painting"))

        assertNull(operations.clearAi(StoryOperationKind.AI_DESCRIPTION))
        assertEquals("s3", operations.clearAi(StoryOperationKind.AI_COVER)?.storyId)
        assertNull(operations.ai.value)
    }
}
