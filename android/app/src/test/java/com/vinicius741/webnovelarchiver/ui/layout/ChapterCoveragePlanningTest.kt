package com.vinicius741.webnovelarchiver.ui.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the chapter-coverage bar geometry: contiguous downloads must collapse into a
 * single run (one rect per run is what keeps the bar free of per-chapter hairline seams), and the
 * bookmark fraction must always resolve to a real, in-range slot.
 */
class ChapterCoveragePlanningTest {
    @Test
    fun downloadedRunsCoalescesContiguousChaptersIntoOneRun() {
        val flags = booleanArrayOf(true, true, true, true)

        assertEquals(listOf(0..3), ChapterCoveragePlanning.downloadedRuns(flags))
    }

    @Test
    fun downloadedRunsSplitsRunsAtGaps() {
        val flags = booleanArrayOf(true, true, false, true, false, true, true, true)

        assertEquals(listOf(0..1, 3..3, 5..7), ChapterCoveragePlanning.downloadedRuns(flags))
    }

    @Test
    fun downloadedRunsIsEmptyWhenNothingIsDownloaded() {
        assertEquals(emptyList<IntRange>(), ChapterCoveragePlanning.downloadedRuns(BooleanArray(5)))
    }

    @Test
    fun downloadedRunsIsEmptyForNoChapters() {
        assertEquals(emptyList<IntRange>(), ChapterCoveragePlanning.downloadedRuns(BooleanArray(0)))
    }

    @Test
    fun downloadedRunsCoversFullRangeWhenAllDownloaded() {
        val flags = BooleanArray(100) { true }

        assertEquals(listOf(0..99), ChapterCoveragePlanning.downloadedRuns(flags))
    }

    @Test
    fun bookmarkSlotIsNullWithoutFractionOrChapters() {
        assertNull(ChapterCoveragePlanning.bookmarkSlot(null, 100))
        assertNull(ChapterCoveragePlanning.bookmarkSlot(0.5f, 0))
    }

    @Test
    fun bookmarkSlotLandsInTheFractionalChapter() {
        // Chapter 51 of 100 (index 50) → fraction 0.5 → slot 50.
        assertEquals(50, ChapterCoveragePlanning.bookmarkSlot(0.5f, 100))
    }

    @Test
    fun bookmarkSlotClampsIntoTheValidRange() {
        assertEquals(0, ChapterCoveragePlanning.bookmarkSlot(0f, 100))
        // fraction == 1.0 would land past the last slot without clamping.
        assertEquals(99, ChapterCoveragePlanning.bookmarkSlot(1f, 100))
        assertEquals(99, ChapterCoveragePlanning.bookmarkSlot(1.5f, 100))
        assertEquals(0, ChapterCoveragePlanning.bookmarkSlot(-0.2f, 100))
    }
}
