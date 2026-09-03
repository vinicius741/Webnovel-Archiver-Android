package com.vinicius741.webnovelarchiver.tts

import com.vinicius741.webnovelarchiver.domain.model.TtsSession
import com.vinicius741.webnovelarchiver.domain.model.TtsStoryPosition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TtsSessionStoreTest {
    @Test
    fun rapidPositionUpdatesAreConflated() =
        runTest {
            val persistence = FakePersistence()
            val store = TtsSessionStore(persistence, StandardTestDispatcher(testScheduler), debounceMs = 250L)

            store.schedule(TtsSession(storyId = "s", chapterId = "c", currentChunkIndex = 1))
            store.schedule(TtsSession(storyId = "s", chapterId = "c", currentChunkIndex = 2))
            advanceTimeBy(249L)
            assertTrue(persistence.saved.isEmpty())
            advanceTimeBy(1L)
            runCurrent()

            assertEquals(listOf(2), persistence.saved.map(TtsSession::currentChunkIndex))
            // The debounced write mirrors the per-story position alongside the session.
            assertEquals(2, persistence.positions["s"]?.currentChunkIndex)
        }

    @Test
    fun flushCancelsPendingWriteAndPersistsPosition() =
        runTest {
            val persistence = FakePersistence()
            val store = TtsSessionStore(persistence, StandardTestDispatcher(testScheduler), debounceMs = 250L)

            store.schedule(TtsSession(storyId = "s", chapterId = "c", currentChunkIndex = 1))
            store.flush(TtsSession(storyId = "s", chapterId = "c", currentChunkIndex = 7))
            advanceTimeBy(300L)
            runCurrent()

            assertEquals(listOf(7), persistence.saved.map(TtsSession::currentChunkIndex))
            assertEquals(7, persistence.positions["s"]?.currentChunkIndex)
        }

    @Test
    fun stopKeepsStoryPositionWhileClearingSession() =
        runTest {
            val persistence = FakePersistence()
            val store = TtsSessionStore(persistence, StandardTestDispatcher(testScheduler), debounceMs = 250L)

            store.stop(TtsSession(storyId = "s", chapterId = "c", currentChunkIndex = 12))

            assertEquals(1, persistence.clearCount)
            assertEquals(12, persistence.positions["s"]?.currentChunkIndex)
        }

    @Test
    fun stopForgettingPositionDropsIt() =
        runTest {
            val persistence = FakePersistence()
            val store = TtsSessionStore(persistence, StandardTestDispatcher(testScheduler), debounceMs = 250L)
            persistence.positions["s"] = TtsStoryPosition(storyId = "s", chapterId = "c", currentChunkIndex = 3)

            store.stop(TtsSession(storyId = "s", chapterId = "c", currentChunkIndex = 3), persistPosition = false)

            assertEquals(1, persistence.clearCount)
            assertNull(persistence.positions["s"])
        }

    @Test
    fun stopForgettingPositionWithNullSessionClearsFallbackStoryId() =
        runTest {
            val persistence = FakePersistence()
            val store = TtsSessionStore(persistence, StandardTestDispatcher(testScheduler), debounceMs = 250L)
            persistence.positions["s"] = TtsStoryPosition(storyId = "s", chapterId = "c", currentChunkIndex = 3)

            store.stop(null, persistPosition = false, forgetStoryId = "s")

            assertEquals(1, persistence.clearCount)
            assertNull(persistence.positions["s"])
        }

    @Test
    fun finishClearsSessionAndStoryPosition() =
        runTest {
            val persistence = FakePersistence()
            val store = TtsSessionStore(persistence, StandardTestDispatcher(testScheduler), debounceMs = 250L)
            persistence.positions["s"] = TtsStoryPosition(storyId = "s", chapterId = "c", currentChunkIndex = 9)

            store.finish(TtsSession(storyId = "s", chapterId = "c", currentChunkIndex = 9))

            assertEquals(1, persistence.clearCount)
            assertNull(persistence.positions["s"])
        }

    @Test
    fun finishOfDescriptionSessionKeepsStoryPosition() =
        runTest {
            val persistence = FakePersistence()
            val store = TtsSessionStore(persistence, StandardTestDispatcher(testScheduler), debounceMs = 250L)
            persistence.positions["s"] = TtsStoryPosition(storyId = "s", chapterId = "c", currentChunkIndex = 9)

            store.finish(
                TtsSession(storyId = "s", chapterId = TtsDescriptionPlanning.DESCRIPTION_CHAPTER_ID, currentChunkIndex = 2),
            )

            assertEquals(1, persistence.clearCount)
            assertEquals(9, persistence.positions["s"]?.currentChunkIndex)
        }

    @Test
    fun descriptionSessionsNeverMirrorAStoryPosition() =
        runTest {
            val persistence = FakePersistence()
            val store = TtsSessionStore(persistence, StandardTestDispatcher(testScheduler), debounceMs = 250L)

            store.schedule(
                TtsSession(storyId = "s", chapterId = TtsDescriptionPlanning.DESCRIPTION_CHAPTER_ID, currentChunkIndex = 4),
            )
            advanceTimeBy(300L)
            runCurrent()

            assertTrue(persistence.positions.isEmpty())
        }

    private class FakePersistence : TtsSessionPersistence {
        val saved = mutableListOf<TtsSession>()
        val positions = mutableMapOf<String, TtsStoryPosition>()
        var clearCount = 0

        override suspend fun save(session: TtsSession) {
            saved += session
        }

        override suspend fun savePosition(position: TtsStoryPosition) {
            positions[position.storyId] = position
        }

        override suspend fun clear() {
            clearCount += 1
        }

        override suspend fun clearPosition(storyId: String) {
            positions.remove(storyId)
        }
    }
}
