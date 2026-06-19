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
