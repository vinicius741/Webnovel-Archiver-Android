package com.vinicius741.webnovelarchiver.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class BackupInputLimitsTest {
    @Test
    fun allowlistAcceptsOnlyManifestAndChapterEntries() {
        assertTrue(BackupInputLimits.isAllowedFullBackupEntry("manifest.json", directory = false))
        assertTrue(BackupInputLimits.isAllowedFullBackupEntry("novels/story/chapter.html", directory = false))
        assertTrue(BackupInputLimits.isAllowedFullBackupEntry("novels/story/", directory = true))
        assertFalse(BackupInputLimits.isAllowedFullBackupEntry("settings.json", directory = false))
        assertFalse(BackupInputLimits.isAllowedFullBackupEntry("novels/story/book.epub", directory = false))
        assertFalse(BackupInputLimits.isAllowedFullBackupEntry("novels/../secret.html", directory = false))
        assertFalse(BackupInputLimits.isAllowedFullBackupEntry("novels\\story\\chapter.html", directory = false))
    }

    @Test
    fun allowlistAcceptsMetricTreeAlongsideNovels() {
        // Per-story trend-history files live under metrics/<encoded-id>.json.
        assertTrue(BackupInputLimits.isAllowedFullBackupEntry("metrics", directory = true))
        assertTrue(BackupInputLimits.isAllowedFullBackupEntry("metrics/story%2Fid.json", directory = false))
        // Wrong extension, traversal, and overly-deep paths are rejected just like the novels/ tree.
        assertFalse(BackupInputLimits.isAllowedFullBackupEntry("metrics/story.json/nested.json", directory = false))
        assertFalse(BackupInputLimits.isAllowedFullBackupEntry("metrics/../escape.json", directory = false))
        assertFalse(BackupInputLimits.isAllowedFullBackupEntry("metrics/story.txt", directory = false))
        assertFalse(BackupInputLimits.isAllowedFullBackupEntry("metrics/", directory = false))
    }

    @Test
    fun allowlistAcceptsCoverTreeWithImageExtensionsOnly() {
        // Generated AI covers live under covers/<name>.<png|jpg|jpeg|webp>.
        assertTrue(BackupInputLimits.isAllowedFullBackupEntry("covers", directory = true))
        assertTrue(BackupInputLimits.isAllowedFullBackupEntry("covers/story-1.png", directory = false))
        assertTrue(BackupInputLimits.isAllowedFullBackupEntry("covers/story-1.JPG", directory = false))
        assertTrue(BackupInputLimits.isAllowedFullBackupEntry("covers/story%2Fid.webp", directory = false))
        assertFalse(BackupInputLimits.isAllowedFullBackupEntry("covers/story-1.gif", directory = false))
        assertFalse(BackupInputLimits.isAllowedFullBackupEntry("covers/nested/story-1.png", directory = false))
        // A dotted directory component must not pass via the directory's own extension.
        assertFalse(BackupInputLimits.isAllowedFullBackupEntry("covers/a.jpg/b.txt", directory = false))
        assertFalse(BackupInputLimits.isAllowedFullBackupEntry("covers/../escape.png", directory = false))
        assertFalse(BackupInputLimits.isAllowedFullBackupEntry("covers/", directory = false))
    }

    @Test
    fun streamingInputLimitStopsBeforeUnboundedRead() {
        val failure =
            assertThrows(IllegalStateException::class.java) {
                BackupInputLimits.readUtf8(
                    ByteArrayInputStream(ByteArray(9) { 'a'.code.toByte() }),
                    maxBytes = 8,
                    label = "Test backup",
                )
            }
        assertTrue(failure.message.orEmpty().contains("input limit"))
    }

    @Test
    fun extractionBudgetReservesSpaceForRawAndStagedTrees() {
        val usable = 264L * 1024L * 1024L
        assertEquals((200L * 1024L * 1024L) / 3L, BackupInputLimits.extractionBudget(usable))
        assertThrows(IllegalStateException::class.java) {
            BackupInputLimits.extractionBudget(32L * 1024L * 1024L)
        }
    }

    @Test
    fun exactIntRejectsFractionalNonFiniteAndOverflowValues() {
        assertEquals(2, BackupInputLimits.exactInt(2.0))
        assertEquals(null, BackupInputLimits.exactInt(2.5))
        assertEquals(null, BackupInputLimits.exactInt(Double.NaN))
        assertEquals(null, BackupInputLimits.exactInt(Long.MAX_VALUE))
    }

    @Test
    fun swapSpacePreservesReserveAndRejectsInvalidSizes() {
        val reserveAndPayload = (64L + 10L) * 1024L * 1024L
        assertTrue(BackupInputLimits.hasSwapSpace(reserveAndPayload, 10L * 1024L * 1024L))
        assertFalse(BackupInputLimits.hasSwapSpace(reserveAndPayload - 1L, 10L * 1024L * 1024L))
        assertFalse(BackupInputLimits.hasSwapSpace(Long.MAX_VALUE, -1L))
    }
}
