package com.vinicius741.webnovelarchiver.app

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppTimeTest {
    @Test fun scaledDeadlinesAndSleepsAdvanceTogether() {
        var nanos = 0L
        val time = ScaledAppTime(1_000L, 100.0, { 1_020L }, { nanos })
        val deadline = time.nowMillis() + 60_000L
        assertEquals(600L, time.realDelayMillis(deadline - time.nowMillis()))
        nanos += 600_000_000L
        assertEquals(deadline, time.nowMillis())
        assertEquals(60_000L, time.elapsedMillis())
    }

    @Test fun wallClockChangesDoNotMoveAnActiveDeadline() {
        var wall = 1_000L
        var nanos = 0L
        val time = ScaledAppTime(1_000L, 10.0, { wall }, { nanos })
        wall -= 5_000L
        nanos += 50_000_000L
        assertEquals(1_500L, time.nowMillis())
        assertEquals(1L, time.realDelayMillis(1L))
    }

    @Test fun sharedVirtualSchedulerAdvancesLogicalWaits() =
        runTest {
            val time =
                object : AppTime {
                    override fun nowMillis() = testScheduler.currentTime

                    override fun elapsedMillis() = testScheduler.currentTime
                }
            time.sleep(30 * 60 * 1000L)
            assertEquals(1_800_000L, time.nowMillis())
            assertTrue(time.elapsedMillis() > 0)
        }
}
