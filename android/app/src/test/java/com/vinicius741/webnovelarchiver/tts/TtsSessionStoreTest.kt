package com.vinicius741.webnovelarchiver.tts

import com.vinicius741.webnovelarchiver.domain.model.TtsSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TtsSessionStoreTest {
    @Test
    fun rapidPositionUpdatesAreConflated() =
        runTest {
            val persistence = FakePersistence()
            val store = TtsSessionStore(persistence, StandardTestDispatcher(testScheduler), debounceMs = 250L)

            store.schedule(TtsSession(currentChunkIndex = 1))
            store.schedule(TtsSession(currentChunkIndex = 2))
            advanceTimeBy(249L)
            assertTrue(persistence.saved.isEmpty())
            advanceTimeBy(1L)
            runCurrent()

            assertEquals(listOf(2), persistence.saved.map(TtsSession::currentChunkIndex))
        }

    @Test
    fun flushAndClearCancelPendingPositionWrite() =
        runTest {
            val persistence = FakePersistence()
            val store = TtsSessionStore(persistence, StandardTestDispatcher(testScheduler), debounceMs = 250L)

            store.schedule(TtsSession(currentChunkIndex = 1))
            store.flush(TtsSession(currentChunkIndex = 7))
            store.clear()
            advanceTimeBy(300L)
            runCurrent()

            assertEquals(listOf(7), persistence.saved.map(TtsSession::currentChunkIndex))
            assertEquals(1, persistence.clearCount)
        }

    private class FakePersistence : TtsSessionPersistence {
        val saved = mutableListOf<TtsSession>()
        var clearCount = 0

        override suspend fun save(session: TtsSession) {
            saved += session
        }

        override suspend fun clear() {
            clearCount += 1
        }
    }
}
