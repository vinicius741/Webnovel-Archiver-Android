package com.vinicius741.webnovelarchiver.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupProgressPlanningTest {
    @Test
    fun startMessageNamesTheNovelCount() {
        assertEquals("Backing up 168 novels…", BackupProgressPlanning.startMessage(168))
    }

    @Test
    fun reportingIsThrottledToFirstEveryBatchAndLast() {
        assertTrue(BackupProgressPlanning.shouldReport(filesWritten = 1, totalFiles = 1200))
        assertFalse(BackupProgressPlanning.shouldReport(filesWritten = 2, totalFiles = 1200))
        assertTrue(BackupProgressPlanning.shouldReport(filesWritten = 25, totalFiles = 1200))
        assertFalse(BackupProgressPlanning.shouldReport(filesWritten = 26, totalFiles = 1200))
        assertTrue(BackupProgressPlanning.shouldReport(filesWritten = 1200, totalFiles = 1200))
    }

    @Test
    fun smallBackupsReportFirstAndLastOnly() {
        assertTrue(BackupProgressPlanning.shouldReport(filesWritten = 1, totalFiles = 10))
        assertFalse(BackupProgressPlanning.shouldReport(filesWritten = 5, totalFiles = 10))
        assertTrue(BackupProgressPlanning.shouldReport(filesWritten = 10, totalFiles = 10))
    }

    @Test
    fun fileMessageCountsBothSides() {
        assertEquals("Zipping files 25 of 1200", BackupProgressPlanning.fileMessage(25, 1200))
    }
}
