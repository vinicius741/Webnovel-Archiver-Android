package com.vinicius741.webnovelarchiver.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupExportPlanningTest {
    @Test
    fun jsonBackupRejectsEmptyLibraryAndOversizedPayloads() {
        assertEquals(
            "Your library is empty",
            BackupExportPlanning.validateJsonBackup(librarySize = 0, jsonByteCount = 2),
        )
        assertEquals(
            "Backup is too large (50.1 MB). Consider exporting fewer novels.",
            BackupExportPlanning.validateJsonBackup(
                librarySize = 1,
                jsonByteCount = (50.1 * 1024 * 1024).toLong(),
            ),
        )
        assertNull(BackupExportPlanning.validateJsonBackup(librarySize = 1, jsonByteCount = 1024))
    }

    @Test
    fun fullBackupRejectsEmptyLibrary() {
        assertEquals("Your library is empty", BackupExportPlanning.validateFullBackup(librarySize = 0))
        assertNull(BackupExportPlanning.validateFullBackup(librarySize = 1))
    }
}
