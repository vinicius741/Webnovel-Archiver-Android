package com.vinicius741.webnovelarchiver.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubFilenameTest {
    @Test
    fun rangeFilenameUsesSafeLowercaseBaseAndChapterSuffix() {
        assertEquals(
            "my_story_volume_1_Ch3-27.epub",
            EpubFilename.forRange("My Story: Volume #1!", 3, 27),
        )
    }

    @Test
    fun regeneratedRangeUsesFreshFilenameForReaderImport() {
        val first = EpubFilename.forRange("Story", 1, 4, "first")
        val second = EpubFilename.forRange("Story", 1, 4, "second")

        assertEquals("story_Ch1-4_gfirst.epub", first)
        assertEquals("story_Ch1-4_gsecond.epub", second)
    }

    @Test
    fun sanitizeBaseFallsBackForBlankOrSymbolOnlyTitles() {
        assertEquals("story", EpubFilename.sanitizeBase(""))
        assertEquals("story", EpubFilename.sanitizeBase("!@#$"))
    }

    @Test
    fun sanitizeBaseBoundsLongTitlesBeforeRangeSuffix() {
        val base = EpubFilename.sanitizeBase("A".repeat(200))

        assertEquals(80, base.length)
        assertTrue(EpubFilename.forRange("A".repeat(200), 1, 10).endsWith("_Ch1-10.epub"))
    }
}
