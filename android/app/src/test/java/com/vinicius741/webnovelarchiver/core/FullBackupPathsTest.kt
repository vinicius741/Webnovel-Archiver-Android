package com.vinicius741.webnovelarchiver.core

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
    fun chapterPathMatchesReactNativeFullBackupManifestShape() {
        assertEquals(
            "novels/story%2Fid/0007_chapter%3A8.html",
            FullBackupPaths.chapterPath("story/id", "chapter:8", 7),
        )
    }
}
