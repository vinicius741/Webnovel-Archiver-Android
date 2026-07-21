package com.vinicius741.webnovelarchiver.domain.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendAxisPlanningTest {
    @Test
    fun emptySeriesReturnsNull() {
        assertNull(TrendAxisPlanning.yAxisRange(emptyList(), hardMin = 0.0, hardMax = 5.0))
    }

    @Test
    fun smallVariationFitsToDataWithPadding() {
        // A 4.6 -> 4.8 score wobble must fill the chart instead of hugging the top of a 0–5 axis.
        val range = TrendAxisPlanning.yAxisRange(seriesOf(4.6, 4.7, 4.8), hardMin = 0.0, hardMax = 5.0)!!
        val expectedPad = 0.2 * TrendAxisPlanning.RANGE_PADDING_FRACTION
        assertEquals(4.6 - expectedPad, range.first, EPS)
        assertEquals(4.8 + expectedPad, range.second, EPS)
    }

    @Test
    fun clampsToScoreDomain() {
        val range = TrendAxisPlanning.yAxisRange(seriesOf(4.9, 5.0), hardMin = 0.0, hardMax = 5.0)!!
        assertTrue(range.second <= 5.0)
        assertTrue(range.first >= 0.0)
        assertEquals(5.0, range.second, EPS)
    }

    @Test
    fun clampsLowerBoundAtZeroForCounts() {
        val range = TrendAxisPlanning.yAxisRange(seriesOf(0.0, 3.0), hardMin = 0.0, hardMax = Double.POSITIVE_INFINITY)!!
        assertEquals(0.0, range.first, EPS)
        assertEquals(3.0 + 3.0 * TrendAxisPlanning.RANGE_PADDING_FRACTION, range.second, EPS)
    }

    @Test
    fun flatSeriesGetsSymmetricWindowAroundValue() {
        val range = TrendAxisPlanning.yAxisRange(seriesOf(410.0, 410.0, 410.0), hardMin = 0.0, hardMax = Double.POSITIVE_INFINITY)!!
        val halfWindow = 410.0 * TrendAxisPlanning.FLAT_WINDOW_FRACTION
        assertEquals(410.0 - halfWindow, range.first, EPS)
        assertEquals(410.0 + halfWindow, range.second, EPS)
    }

    @Test
    fun flatSeriesAtZeroFallsBackToAbsoluteWindow() {
        val range = TrendAxisPlanning.yAxisRange(seriesOf(0.0, 0.0), hardMin = 0.0, hardMax = Double.POSITIVE_INFINITY)!!
        assertEquals(0.0, range.first, EPS)
        assertEquals(TrendAxisPlanning.FLAT_WINDOW_MIN, range.second, EPS)
    }

    @Test
    fun flatSeriesAtDomainCeilingStaysInsideDomain() {
        val range = TrendAxisPlanning.yAxisRange(seriesOf(5.0, 5.0), hardMin = 0.0, hardMax = 5.0)!!
        assertTrue(range.first < 5.0)
        assertEquals(5.0, range.second, EPS)
    }

    @Test
    fun outOfDomainDataFallsBackToUnclampedWindow() {
        // A provider bug storing a 6.0 score must not produce an inverted (min > max) axis.
        val range = TrendAxisPlanning.yAxisRange(seriesOf(6.0, 6.0), hardMin = 0.0, hardMax = 5.0)!!
        assertTrue(range.first < range.second)
        assertTrue(range.first <= 6.0 && range.second >= 6.0)
    }

    private fun seriesOf(vararg values: Double): List<MetricPoint> = values.mapIndexed { index, value -> index.toLong() to value }

    private companion object {
        const val EPS = 1e-9
    }
}
