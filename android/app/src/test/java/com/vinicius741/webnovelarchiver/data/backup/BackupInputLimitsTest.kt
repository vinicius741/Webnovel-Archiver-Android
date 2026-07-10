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
