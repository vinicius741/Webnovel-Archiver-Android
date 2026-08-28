package com.vinicius741.webnovelarchiver.data.backup

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class BackupFilePlanningTest {
    private lateinit var testRoot: File
    private lateinit var directory: File

    @Before
    fun setUp() {
        testRoot = File(System.getProperty("java.io.tmpdir"), "backup_files_${System.nanoTime()}").apply { mkdirs() }
        directory = File(testRoot, "backups").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        testRoot.deleteRecursively()
    }

    @Test
    fun orphanTempDetectionMatchesAtomicWriteTempNamesOnly() {
        assertTrue(BackupFilePlanning.isOrphanTempFile("webnovel_full_backup_1770000000000.zip.tmp.2"))
        assertTrue(BackupFilePlanning.isOrphanTempFile("library.json.tmp.17"))
        assertFalse(BackupFilePlanning.isOrphanTempFile("webnovel_full_backup_1770000000000.zip"))
        assertFalse(BackupFilePlanning.isOrphanTempFile("library.json.tmp"))
        assertFalse(BackupFilePlanning.isOrphanTempFile("library.tmp.abc"))
    }

    @Test
    fun sweepDeletesOnlyOrphanedTemps() {
        File(directory, "webnovel_full_backup_1.zip.tmp.2").writeText("partial")
        File(directory, "library.json.tmp.9").writeText("partial")
        val backup = File(directory, "webnovel_full_backup_1.zip").apply { writeText("zip") }

        assertEquals(2, BackupFilePlanning.sweepOrphanTempFiles(directory))

        assertTrue(backup.exists())
        assertTrue(directory.listFiles()!!.none { BackupFilePlanning.isOrphanTempFile(it.name) })
    }

    @Test
    fun artifactLabelsCoverTheExportsTheAppCreates() {
        assertEquals("Full backup", BackupFilePlanning.artifactLabel("webnovel_full_backup_1.zip"))
        assertEquals("Metadata backup", BackupFilePlanning.artifactLabel("webnovel_backup_2.json"))
        assertEquals("Cleanup rules backup", BackupFilePlanning.artifactLabel("webnovel_cleanup_rules_3.json"))
        assertEquals("webnovel_source_access_log_4", BackupFilePlanning.artifactLabel("webnovel_source_access_log_4.json"))
    }

    @Test
    fun listArtifactsIsNewestFirstAndExcludesTempsAndDiagnostics() {
        val old =
            File(directory, "webnovel_backup_1.json").apply {
                writeText("{}")
                setLastModified(1_000L)
            }
        val newest =
            File(directory, "webnovel_full_backup_2.zip").apply {
                writeText("zip")
                setLastModified(2_000L)
            }
        File(directory, "webnovel_full_backup_2.zip.tmp.3").apply {
            writeText("partial")
            setLastModified(3_000L)
        }
        File(directory, "webnovel_source_access_log_4.json").apply {
            writeText("{}")
            setLastModified(4_000L)
        }

        assertEquals(listOf(newest, old), BackupFilePlanning.listArtifacts(directory))
    }

    @Test
    fun deleteWithinOnlyDeletesFilesDirectlyInsideTheDirectory() {
        val backup = File(directory, "webnovel_full_backup_1.zip").apply { writeText("zip") }
        val nested = File(directory, "nested").apply { mkdirs() }
        val insideNested = File(nested, "innocent.json").apply { writeText("{}") }
        val elsewhere = File(testRoot, "outside.json").apply { writeText("{}") }

        assertTrue(BackupFilePlanning.deleteWithin(directory, backup))
        assertFalse(backup.exists())
        assertFalse(BackupFilePlanning.deleteWithin(directory, insideNested))
        assertTrue(insideNested.exists())
        assertFalse(BackupFilePlanning.deleteWithin(directory, elsewhere))
        assertTrue(elsewhere.exists())
    }

    @Test
    fun sizeLabelRendersBytesKilobytesAndMegabytes() {
        assertEquals("512 B", BackupFilePlanning.sizeLabel(512))
        assertEquals("2 KB", BackupFilePlanning.sizeLabel(2048))
        assertEquals("37.1 MB", BackupFilePlanning.sizeLabel((37.1 * 1024 * 1024).toLong()))
    }
}
