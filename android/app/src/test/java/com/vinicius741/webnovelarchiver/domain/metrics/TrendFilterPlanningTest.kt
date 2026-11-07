package com.vinicius741.webnovelarchiver.domain.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendFilterPlanningTest {
    private val day = 86_400_000L
    private val start = 1_790_294_400_000L

    private fun daily(values: List<Double>): List<MetricPoint> = values.mapIndexed { index, value -> (start + index * day) to value }

    @Test
    fun rangesLongerThanTheHistoryAreNotOffered() {
        assertEquals(
            listOf(TrendRange.WEEK, TrendRange.MONTH, TrendRange.ALL),
            TrendFilterPlanning.availableRanges(start, start + 45 * day),
        )
        assertEquals(TrendRange.entries.toList(), TrendFilterPlanning.availableRanges(start, start + 400 * day))
    }

    @Test
    fun emptyOrSingleInstantHistoryOffersOnlyAll() {
        assertEquals(listOf(TrendRange.ALL), TrendFilterPlanning.availableRanges(null, null))
        assertEquals(listOf(TrendRange.ALL), TrendFilterPlanning.availableRanges(start, start))
        // Exactly seven days of span equals the week window, so it adds nothing over All.
        assertEquals(listOf(TrendRange.ALL), TrendFilterPlanning.availableRanges(start, start + 7 * day))
    }

    @Test
    fun allRangeKeepsTheWholeSeries() {
        val points = daily(listOf(1.0, 2.0, 3.0))
        val window = TrendFilterPlanning.window(points, TrendRange.ALL, points.last().first)
        assertEquals(points, window.points)
        assertNull(window.startAt)
        assertEquals(2.0, window.change!!, 0.0)
        assertTrue(window.isChartable)
    }

    @Test
    fun windowStartingOnAMeasurementNeedsNoCarryIn() {
        // Ten daily points; a 7-day window anchored on the last one starts exactly on index 2.
        val points = daily((0 until 10).map { it.toDouble() })
        val anchor = points.last().first
        val window = TrendFilterPlanning.window(points, TrendRange.WEEK, anchor)
        assertEquals(anchor - 7 * day, window.startAt)
        assertEquals(anchor, window.endAt)
        assertEquals(8, window.inWindowCount)
        assertEquals(points.subList(2, 10), window.points)
        // Seven days of daily +1 steps.
        assertEquals(7.0, window.change!!, 0.0)
    }

    @Test
    fun rangedWindowCarriesInTheValueKnownAtItsStart() {
        // Anchored half a day past index 9, the 7-day window opens between index 2 and 3.
        val points = daily((0 until 10).map { it.toDouble() })
        val anchor = points.last().first + day / 2
        val window = TrendFilterPlanning.window(points, TrendRange.WEEK, anchor)
        assertEquals(7, window.inWindowCount)
        // Index 2 is carried in as the value known when the window opened.
        assertEquals(points.subList(2, 10), window.points)
        assertEquals(7.0, window.change!!, 0.0)
    }

    @Test
    fun windowWithoutCarryInWhenTheSeriesStartsInside() {
        val points = daily(listOf(5.0, 6.0))
        val window = TrendFilterPlanning.window(points, TrendRange.MONTH, points.last().first)
        assertEquals(points, window.points)
        assertEquals(2, window.inWindowCount)
    }

    @Test
    fun seriesThatStoppedBeforeTheWindowIsNotChartable() {
        // Patreon stopped being measured 20 days before the newest snapshot of the history.
        val points = daily(listOf(10.0, 11.0, 12.0))
        val anchor = points.last().first + 20 * day
        val window = TrendFilterPlanning.window(points, TrendRange.WEEK, anchor)
        assertEquals(0, window.inWindowCount)
        assertEquals(listOf(points.last()), window.points)
        assertFalse(window.isChartable)
        assertNull(window.change)
    }

    @Test
    fun downsampleLeavesShortSeriesUntouched() {
        val points = daily((0 until 50).map { it.toDouble() })
        assertSame(points, TrendFilterPlanning.downsample(points, maxPoints = 120))
    }

    @Test
    fun downsampleKeepsEndpointsAndPeaks() {
        val values = (0 until 1000).map { if (it == 517) 500.0 else (it % 7).toDouble() }
        val points = daily(values)
        val thinned = TrendFilterPlanning.downsample(points, maxPoints = 100)
        assertEquals(100, thinned.size)
        assertEquals(points.first(), thinned.first())
        assertEquals(points.last(), thinned.last())
        assertTrue("spike must survive downsampling", thinned.any { it.second == 500.0 })
        assertEquals("output stays oldest-first", thinned.sortedBy { it.first }, thinned)
    }

    @Test
    fun groupsAreOfferedOnlyWhenThereIsAChoice() {
        assertEquals(listOf(TrendGroup.ALL), TrendFilterPlanning.availableGroups(setOf(TrendGroup.ENGAGEMENT)))
        assertEquals(
            listOf(TrendGroup.ALL, TrendGroup.SCORE, TrendGroup.PATREON),
            TrendFilterPlanning.availableGroups(setOf(TrendGroup.PATREON, TrendGroup.SCORE)),
        )
    }

    @Test
    fun selectionRoundTripsAndToleratesGarbage() {
        val token = TrendFilterPlanning.encodeSelection(TrendRange.QUARTER, TrendGroup.PATREON)
        assertEquals(TrendRange.QUARTER to TrendGroup.PATREON, TrendFilterPlanning.decodeSelection(token))
        assertEquals(null to null, TrendFilterPlanning.decodeSelection(null))
        assertEquals(TrendRange.ALL to null, TrendFilterPlanning.decodeSelection("ALL|nope"))
    }
}
