package com.vinicius741.webnovelarchiver.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupExportPlanningTest {
    @Test
    fun fullBackupRejectsEmptyLibrary() {
        assertEquals("Your library is empty", BackupExportPlanning.validateFullBackup(librarySize = 0))
        assertNull(BackupExportPlanning.validateFullBackup(librarySize = 1))
    }
}
