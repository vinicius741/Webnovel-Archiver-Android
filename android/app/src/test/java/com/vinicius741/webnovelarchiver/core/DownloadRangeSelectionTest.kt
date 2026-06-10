package com.vinicius741.webnovelarchiver.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRangeSelectionTest {
    @Test
    fun rangeModeConvertsOneBasedChaptersToZeroBasedIndexes() {
        val result = DownloadRangeSelection.select(
            mode = DownloadRangeSelection.Mode.RANGE,
            totalChapters = 10,
            rangeStart = 3,
            rangeEnd = 5,
            countStart = null,
            count = null,
            bookmarkChapterNumber = null,
        )

        assertTrue(result.valid)
        assertEquals(listOf(2, 3, 4), result.indexes)
        assertEquals(3, result.startChapter)
        assertEquals(5, result.endChapter)
    }

    @Test
    fun bookmarkModeStartsAfterBookmarkAndCapsAtTotalChapters() {
        val result = DownloadRangeSelection.select(
            mode = DownloadRangeSelection.Mode.BOOKMARK,
            totalChapters = 100,
            rangeStart = null,
            rangeEnd = null,
            countStart = null,
            count = 150,
            bookmarkChapterNumber = 47,
        )

        assertTrue(result.valid)
        assertEquals(47, result.indexes.first())
        assertEquals(99, result.indexes.last())
        assertEquals(48, result.startChapter)
        assertEquals(100, result.endChapter)
    }

    @Test
    fun bookmarkModeRejectsLastChapterBookmark() {
        val result = DownloadRangeSelection.select(
            mode = DownloadRangeSelection.Mode.BOOKMARK,
            totalChapters = 100,
            rangeStart = null,
            rangeEnd = null,
            countStart = null,
            count = 10,
            bookmarkChapterNumber = 100,
        )

        assertFalse(result.valid)
        assertEquals("Bookmark is at the last chapter, nothing to download.", result.error)
    }

    @Test
    fun countModeDownloadsCountFromStartChapter() {
        val result = DownloadRangeSelection.select(
            mode = DownloadRangeSelection.Mode.COUNT,
            totalChapters = 20,
            rangeStart = null,
            rangeEnd = null,
            countStart = 5,
            count = 3,
            bookmarkChapterNumber = null,
        )

        assertTrue(result.valid)
        assertEquals(listOf(4, 5, 6), result.indexes)
        assertEquals(5, result.startChapter)
        assertEquals(7, result.endChapter)
    }

    @Test
    fun invalidRangeReturnsReactNativeCompatibleError() {
        val result = DownloadRangeSelection.select(
            mode = DownloadRangeSelection.Mode.RANGE,
            totalChapters = 10,
            rangeStart = 8,
            rangeEnd = 2,
            countStart = null,
            count = null,
            bookmarkChapterNumber = null,
        )

        assertFalse(result.valid)
        assertEquals("Start chapter cannot be greater than end chapter.", result.error)
    }
}
