package com.vinicius741.webnovelarchiver.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfSessionStateTest {
    private fun state(rule: PerfCompleteOnRule) =
        PerfSessionState(
            runId = "r1",
            scenario = "cold_library",
            iteration = "1",
            completeOn = rule,
            deadlineNanos = 10_000_000_000L,
            processStartNanos = 1_000L,
            startedAtWallClockMillis = 0L,
        )

    @Test
    fun offerEventRecordsAndReportsCompletionOnce() {
        val s = state(PerfCompleteOnRule("screen_built", "library"))
        assertTrue(s.offerEvent("screen_built", tNanos = 100L, detail = mapOf("route" to "library")))
        assertFalse(s.offerEvent("screen_built", tNanos = 200L, detail = mapOf("route" to "library")))
        assertEquals(2, s.snapshotEvents().count { it.name == "screen_built" })
        assertEquals(100L, s.scenarioCompleteNanos)
    }

    @Test
    fun completeOnRouteQualifierRejectsOtherRoutes() {
        val s = state(PerfCompleteOnRule("screen_built", "details"))
        assertFalse(s.offerEvent("screen_built", tNanos = 100L, detail = mapOf("route" to "library")))
        assertNull(s.scenarioCompleteNanos)
        assertTrue(s.offerEvent("screen_built", tNanos = 200L, detail = mapOf("route" to "details")))
    }

    @Test
    fun completeOnWithoutQualifierMatchesAnyRoute() {
        val s = state(PerfCompleteOnRule("reader_content_presented", null))
        assertTrue(s.offerEvent("reader_content_presented", tNanos = 5L, detail = mapOf("route" to "reader")))
    }

    @Test
    fun eventCapDropsAndCountsInsteadOfGrowing() {
        val s = state(PerfCompleteOnRule("never", null))
        repeat(PerfSessionContract.MAX_EVENTS) { s.offerEvent("e", tNanos = it.toLong()) }
        assertEquals(PerfSessionContract.MAX_EVENTS, s.snapshotEvents().size)
        s.offerEvent("e", tNanos = -1L)
        assertEquals(PerfSessionContract.MAX_EVENTS, s.snapshotEvents().size)
        assertEquals(1, s.droppedEvents)
    }

    @Test
    fun detailValuesAreTruncatedToBoundChapterText() {
        val s = state(PerfCompleteOnRule("never", null))
        val longText = "x".repeat(500)
        s.offerEvent("screen_built", tNanos = 1L, detail = mapOf("title" to longText))
        assertEquals(
            PerfSessionContract.MAX_DETAIL_LENGTH,
            s
                .snapshotEvents()
                .single()
                .detail["title"]
                ?.length,
        )
    }

    @Test
    fun deadlineFiresExactlyOnceAndOnlyAfterCompletionIsMissing() {
        val s = state(PerfCompleteOnRule("screen_built", "library"))
        assertFalse(s.shouldFireDeadline(9_999_999_999L))
        assertTrue(s.shouldFireDeadline(10_000_000_000L))
        assertFalse(s.shouldFireDeadline(11_000_000_000L))
    }

    @Test
    fun completedSessionNeverFiresDeadline() {
        val s = state(PerfCompleteOnRule("screen_built", "library"))
        assertTrue(s.offerEvent("screen_built", tNanos = 100L, detail = mapOf("route" to "library")))
        assertFalse(s.shouldFireDeadline(50_000_000_000L))
    }

    @Test
    fun memoryCapDropsAndCounts() {
        val s = state(PerfCompleteOnRule("never", null))
        val sample = PerfRecordedMemory(1L, "screen:library", 1, 1, 1, 1, 1, 1)
        repeat(PerfSessionContract.MAX_MEMORY_SAMPLES) { s.offerMemory(sample) }
        s.offerMemory(sample)
        assertEquals(PerfSessionContract.MAX_MEMORY_SAMPLES, s.snapshotMemory().size)
        assertEquals(1, s.droppedMemorySamples)
    }
}

class PerfSessionContractTest {
    @Test
    fun requestedAcceptsTruthyValuesOnly() {
        assertTrue(PerfSessionContract.requested("1"))
        assertTrue(PerfSessionContract.requested(" True "))
        assertFalse(PerfSessionContract.requested("0"))
        assertFalse(PerfSessionContract.requested(null))
        assertFalse(PerfSessionContract.requested(""))
    }

    @Test
    fun parseCompleteOnSplitsEventAndRoute() {
        assertEquals(PerfCompleteOnRule("screen_built", "details"), PerfSessionContract.parseCompleteOn("screen_built:details"))
        assertEquals(PerfCompleteOnRule("reader_content_presented", null), PerfSessionContract.parseCompleteOn("reader_content_presented"))
        assertEquals(PerfCompleteOnRule("screen_built", null), PerfSessionContract.parseCompleteOn(null))
        assertEquals(PerfCompleteOnRule("screen_built", null), PerfSessionContract.parseCompleteOn("  "))
    }
}

class PerfFramePlanningTest {
    @Test
    fun jankThresholdIsTwiceTheFrameInterval() {
        // 60 Hz -> 16.67ms interval -> 33.3ms threshold.
        assertEquals(33_333_333L, PerfFramePlanning.jankThresholdNanos(60.0))
    }

    @Test
    fun isJankClassifiesAroundThreshold() {
        val hz = 60.0
        assertFalse(PerfFramePlanning.isJank(16_000_000L, hz))
        assertFalse(PerfFramePlanning.isJank(33_333_333L, hz))
        assertTrue(PerfFramePlanning.isJank(40_000_000L, hz))
    }

    @Test
    fun degenerateRefreshRateFallsBackToOneHertz() {
        assertTrue(PerfFramePlanning.jankThresholdNanos(0.0) > 1_000_000_000L)
    }
}

class PerfOverheadAccumulatorTest {
    @Test
    fun accumulatesMeanAndMax() {
        val acc = PerfOverheadAccumulator()
        acc.record(startNanos = 0, endNanos = 100)
        acc.record(startNanos = 0, endNanos = 300)
        val snapshot = acc.snapshot()
        assertEquals(2, snapshot.eventRecordCalls)
        assertEquals(200, snapshot.eventRecordMeanNanos)
        assertEquals(300, snapshot.eventRecordMaxNanos)
    }

    @Test
    fun emptyAccumulatorIsSafe() {
        val snapshot = PerfOverheadAccumulator().snapshot()
        assertEquals(0, snapshot.eventRecordCalls)
        assertEquals(0, snapshot.eventRecordMeanNanos)
    }
}
