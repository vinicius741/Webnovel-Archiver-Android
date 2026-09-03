package com.vinicius741.webnovelarchiver.tts

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TtsSleepTimerTest {
    @Test
    fun durationTimerExpiresAndInvokesCallback() =
        runTest {
            var expiredCount = 0
            val timer =
                TtsSleepTimer(TestScope(StandardTestDispatcher(testScheduler))) {
                    expiredCount += 1
                }

            timer.setDuration(15)
            assertTrue(timer.mode is TtsSleepTimerMode.Duration)
            assertEquals(15, (timer.mode as TtsSleepTimerMode.Duration).minutes)

            advanceTimeBy(10 * 60_000L)
            runCurrent()
            assertEquals(0, expiredCount)

            advanceTimeBy(5 * 60_000L)
            runCurrent()
            assertEquals(1, expiredCount)
            assertTrue(timer.mode is TtsSleepTimerMode.Off)
        }

    @Test
    fun endOfChapterTriggersOnChapterCompleted() =
        runTest {
            var expiredCount = 0
            val timer =
                TtsSleepTimer(TestScope(StandardTestDispatcher(testScheduler))) {
                    expiredCount += 1
                }

            timer.setEndOfChapter()
            assertTrue(timer.mode is TtsSleepTimerMode.EndOfChapter)

            val fired = timer.onChapterCompleted()
            assertTrue(fired)
            assertEquals(1, expiredCount)
            assertTrue(timer.mode is TtsSleepTimerMode.Off)

            // Subsequent chapter completions do not fire again
            assertFalse(timer.onChapterCompleted())
            assertEquals(1, expiredCount)
        }

    @Test
    fun setOffCancelsDurationTimer() =
        runTest {
            var expiredCount = 0
            val timer =
                TtsSleepTimer(TestScope(StandardTestDispatcher(testScheduler))) {
                    expiredCount += 1
                }

            timer.setDuration(30)
            assertTrue(timer.mode is TtsSleepTimerMode.Duration)

            timer.setOff()
            assertTrue(timer.mode is TtsSleepTimerMode.Off)

            advanceTimeBy(35 * 60_000L)
            runCurrent()
            assertEquals(0, expiredCount)
        }
}
