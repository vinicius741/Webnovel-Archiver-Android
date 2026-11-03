package com.vinicius741.webnovelarchiver.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class InsertedNoticeMarkerMonitorTest {
    private fun InsertedNoticeMarkerMonitor.alerts(
        sourceId: String,
        count: Int,
    ) = (1..count).count { record(sourceId, markerFound = false) }

    @Test
    fun alertsOnceWhenARunReachesTheThreshold() {
        assertEquals(1, InsertedNoticeMarkerMonitor(alertAfterMissing = 3).alerts("rr", 10))
    }

    @Test
    fun aFoundMarkerResetsTheRunAndRearmsTheAlert() {
        val monitor = InsertedNoticeMarkerMonitor(alertAfterMissing = 3)

        assertEquals(0, monitor.alerts("rr", 2))
        assertFalse(monitor.record("rr", markerFound = true))
        assertEquals(0, monitor.alerts("rr", 2))
        assertEquals(1, monitor.alerts("rr", 1))
        assertFalse(monitor.record("rr", markerFound = true))
        assertEquals(1, monitor.alerts("rr", 3))
    }

    @Test
    fun sourcesAreCountedSeparately() {
        val monitor = InsertedNoticeMarkerMonitor(alertAfterMissing = 2)

        assertEquals(0, monitor.alerts("a", 1))
        assertEquals(0, monitor.alerts("b", 1))
        assertEquals(1, monitor.alerts("a", 1))
    }
}
