package com.vinicius741.webnovelarchiver.operations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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
    fun onlyOneOfTwoRacingStartsListsANovelAsSyncingFromUpdates() {
        val pool = Executors.newFixedThreadPool(RACERS)
        try {
            repeat(RACE_ROUNDS) { round ->
                val raced = StoryOperations()
                val ready = CountDownLatch(RACERS)
                val go = AtomicBoolean(false)
                val starts =
                    List(RACERS) {
                        pool.submit<Boolean> {
                            ready.countDown()
                            // Spinning, not parking, so both racers write within the same instant.
                            while (!go.get()) Thread.onSpinWait()
                            raced.startUpdatesSync("s1", "Starting...")
                        }
                    }
                ready.await()
                go.set(true)
                assertEquals("round $round", 1, starts.count { it.get() })
                assertTrue(raced.isSyncingFromUpdates("s1"))
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun clearingAiByKindReturnsWhatWasCleared() {
        operations.setAi(StoryOperationState("s3", StoryOperationKind.AI_COVER, "Painting"))

        assertNull(operations.clearAi(StoryOperationKind.AI_DESCRIPTION))
        assertEquals("s3", operations.clearAi(StoryOperationKind.AI_COVER)?.storyId)
        assertNull(operations.ai.value)
    }

    private companion object {
        const val RACERS = 2
        const val RACE_ROUNDS = 5_000
    }
}
