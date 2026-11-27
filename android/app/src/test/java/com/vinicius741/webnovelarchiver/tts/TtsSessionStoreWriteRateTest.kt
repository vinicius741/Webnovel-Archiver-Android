package com.vinicius741.webnovelarchiver.tts

import com.vinicius741.webnovelarchiver.domain.model.TtsSession
import com.vinicius741.webnovelarchiver.domain.model.TtsStoryPosition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Write-rate harness: one minute of continuous listening, a new sentence every few seconds (the
 * engine schedules a session write on every sentence start), then a pause. Counts the durable
 * writes [TtsSessionStore] asks for, and checks that the pause still saves the last place heard.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TtsSessionStoreWriteRateTest {
    @Test
    fun aMinuteOfPlaybackWritesOncePerProgressInterval() =
        runTest {
            for (sentenceMs in SENTENCE_LENGTHS_MS) {
                val persistence = CountingPersistence()
                val store =
                    TtsSessionStore(
                        persistence,
                        StandardTestDispatcher(testScheduler),
                        clock = { testScheduler.currentTime },
                    )
                val start = testScheduler.currentTime
                val last = playOneMinute(sentenceMs, store::schedule)
                store.flush(last.copy(isPaused = true, wasPlaying = false))
                runCurrent()
                report("throttled", sentenceMs, persistence)

                // One write when playback starts, one per interval after it, and the pause.
                val budget = 1 + PLAY_MS / TtsSessionStore.PROGRESS_INTERVAL_MS + 1
                assertTrue("session writes ${persistence.sessionWrites} > $budget", persistence.sessionWrites <= budget)
                assertEquals(persistence.sessionWrites, persistence.positionWrites)
                assertEquals(last.currentChunkIndex, persistence.lastSaved?.currentChunkIndex)
                assertTrue(testScheduler.currentTime - start >= PLAY_MS)
            }
        }

    private suspend fun TestScope.playOneMinute(
        sentenceMs: Long,
        schedule: (TtsSession) -> Unit,
    ): TtsSession {
        val start = testScheduler.currentTime
        var session = TtsSession(storyId = "s", chapterId = "c", isPaused = false, wasPlaying = true)
        var chunk = 0
        while (testScheduler.currentTime - start < PLAY_MS) {
            session = session.copy(currentChunkIndex = chunk++, updatedAt = testScheduler.currentTime)
            schedule(session)
            advanceTimeBy(sentenceMs)
            runCurrent()
        }
        return session
    }

    private fun report(
        label: String,
        sentenceMs: Long,
        persistence: CountingPersistence,
    ) {
        println(
            "TTS write rate [$label] sentence=${sentenceMs}ms sentences=${PLAY_MS / sentenceMs}: " +
                "session=${persistence.sessionWrites} positions=${persistence.positionWrites} per minute + pause",
        )
    }

    private class CountingPersistence : TtsSessionPersistence {
        var sessionWrites = 0
        var positionWrites = 0
        var lastSaved: TtsSession? = null

        override suspend fun save(session: TtsSession) {
            sessionWrites += 1
            lastSaved = session
        }

        override suspend fun savePosition(position: TtsStoryPosition) {
            positionWrites += 1
        }

        override suspend fun clear() = Unit

        override suspend fun clearPosition(storyId: String) = Unit
    }

    private companion object {
        const val PLAY_MS = 60_000L
        val SENTENCE_LENGTHS_MS = listOf(2_000L, 4_000L, 6_000L)
    }
}
