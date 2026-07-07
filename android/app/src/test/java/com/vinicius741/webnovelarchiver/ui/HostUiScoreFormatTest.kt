package com.vinicius741.webnovelarchiver.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers [formatScore], which normalizes provider score strings to a single canonical
 * two-decimal form ("X.XX") so Royal Road and Scribble Hub cards/details render identically (QA F1).
 */
class HostUiScoreFormatTest {
    @Test
    fun royalRoadFormatIsPreservedAsCanonical() {
        // Royal Road stores "4.84 / 5"; only the leading number is kept and re-normalized.
        assertEquals("4.84", formatScore("4.84 / 5"))
    }

    @Test
    fun scribbleHubBareDecimalIsZeroPadded() {
        assertEquals("4.80", formatScore("4.8"))
        assertEquals("4.90", formatScore("4.9"))
    }

    @Test
    fun oneDecimalRoyalRoadIsAlignedWithScribbleHubOutput() {
        // Both providers must produce the same string for the same rating.
        assertEquals(formatScore("4.5 / 5"), formatScore("4.5"))
    }

    @Test
    fun roundsHalfUpToTwoDecimals() {
        assertEquals("4.57", formatScore("4.565"))
    }

    @Test
    fun wholeNumberScoreGetsDecimal() {
        assertEquals("4.00", formatScore("4"))
    }

    @Test
    fun nonNumericInputIsReturnedTrimmed() {
        assertEquals("N/A", formatScore("N/A"))
        assertEquals("", formatScore(""))
    }
}
