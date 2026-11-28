package com.vinicius741.webnovelarchiver.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun compactCoverPathRenamesToWebpUnlessAnotherCoverOwnsThatPath() {
        assertEquals("covers/rr_1.webp", FullBackupPaths.compactCoverPath("covers/rr_1.jpg", setOf("covers/rr_1.jpg")))
        assertEquals("covers/rr_1.webp", FullBackupPaths.compactCoverPath("covers/rr_1.png", emptySet()))
        assertEquals("covers/a.b.webp", FullBackupPaths.compactCoverPath("covers/a.b.jpeg", emptySet()))
        assertEquals("covers/plain.webp", FullBackupPaths.compactCoverPath("covers/plain", emptySet()))
        // Already WebP: shipped as is.
        assertNull(FullBackupPaths.compactCoverPath("covers/rr_1.webp", emptySet()))
        // Restore rejects duplicate cover paths, so a taken .webp name keeps the original.
        assertNull(FullBackupPaths.compactCoverPath("covers/rr_1.jpg", setOf("covers/rr_1.jpg", "covers/rr_1.webp")))
    }
}
