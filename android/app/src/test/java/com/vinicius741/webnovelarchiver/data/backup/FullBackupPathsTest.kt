package com.vinicius741.webnovelarchiver.data.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class FullBackupPathsTest {
    @Test
    fun encodeURIComponentMatchesJavascriptBackupPathEncoding() {
        assertEquals(
            "story%2Fwith%20spaces%3A%C3%A9",
            FullBackupPaths.encodeURIComponent("story/with spaces:é"),
        )
        assertEquals(
            "AZaz09-_.!~*'()",
            FullBackupPaths.encodeURIComponent("AZaz09-_.!~*'()"),
        )
    }

    @Test
    fun chapterPathMatchesFullBackupManifestShape() {
        assertEquals(
            "novels/story%2Fid/0007_chapter%3A8.html",
            FullBackupPaths.chapterPath("story/id", "chapter:8", 7),
        )
    }

    @Test
    fun metricPathEncodesStoryIdUnderMetricsDir() {
        // One segment deep under metrics/, with the same encoding chapterPath uses for story ids, so
        // a round-trip through restore reconstructs the on-disk metrics/<safeName(id)>.json layout.
        assertEquals("metrics/story%2Fid.json", FullBackupPaths.metricPath("story/id"))
        assertEquals("metrics/plain.json", FullBackupPaths.metricPath("plain"))
    }
}
