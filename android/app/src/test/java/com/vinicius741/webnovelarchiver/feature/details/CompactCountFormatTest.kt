package com.vinicius741.webnovelarchiver.feature.details

import org.junit.Assert.assertEquals
import org.junit.Test

class CompactCountFormatTest {
    @Test
    fun wholeScaledValuesKeepTheirIntegerZeros() {
        assertEquals("100K", formatCompactCount(100_000))
        assertEquals("200M", formatCompactCount(200_000_000))
        assertEquals("120K", formatCompactCount(120_000))
    }

    @Test
    fun fractionalZerosAreTrimmed() {
        assertEquals("85.2K", formatCompactCount(85_200))
        assertEquals("10K", formatCompactCount(10_000))
        assertEquals("1.5K", formatCompactCount(1_500))
        assertEquals("999", formatCompactCount(999))
    }

    @Test
    fun countDeltasUseTheCompactFormWithASign() {
        assertEquals("+100K", formatTrendDelta(TrendMetricKind.COUNT, 100_000.0))
        assertEquals("-1.2K", formatTrendDelta(TrendMetricKind.COUNT, -1_200.0))
    }

    @Test
    fun patreonDollarsRoundLikeTheDetailsAndLibraryCards() {
        // QA 2026-11-07 F2: 13,860 cents showed $138 in Trends but $139 elsewhere.
        assertEquals("$139", formatTrendValue(TrendMetricKind.PATREON_USD, 13_860.0))
        assertEquals("$138", formatTrendValue(TrendMetricKind.PATREON_USD, 13_849.0))
        assertEquals("+$2", formatTrendDelta(TrendMetricKind.PATREON_USD, 150.0))
    }
}
