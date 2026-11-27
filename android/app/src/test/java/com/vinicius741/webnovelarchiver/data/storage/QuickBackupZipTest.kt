package com.vinicius741.webnovelarchiver.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class QuickBackupZipTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun zip(vararg entries: Pair<String, String>): ByteArray =
        ByteArrayOutputStream()
            .also { bytes ->
                ZipOutputStream(bytes).use { zip ->
                    entries.forEach { (name, text) ->
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(text.toByteArray())
                        zip.closeEntry()
                    }
                }
            }.toByteArray()

    private fun read(
        bytes: ByteArray,
        maxTotalBytes: Long = QuickBackupZip.MAX_TOTAL_BYTES,
    ): Pair<QuickBackupEntries, File> {
        val staging = temp.newFolder()
        return QuickBackupZip.read(ByteArrayInputStream(bytes), staging, maxTotalBytes) to staging
    }

    private fun rejected(
        bytes: ByteArray,
        maxTotalBytes: Long = QuickBackupZip.MAX_TOTAL_BYTES,
    ): String {
        try {
            read(bytes, maxTotalBytes)
        } catch (error: IllegalStateException) {
            return error.message.orEmpty()
        }
        fail("expected the ZIP to be rejected")
        return ""
    }

    @Test
    fun readsTheManifestAndStagesItsFiles() {
        val (entries, staging) = read(zip("quick_backup.json" to "{}", "metrics/s1.json" to "[1]", "covers/s1.jpg" to "jpg"))

        assertEquals("{}", entries.manifestText)
        assertEquals(setOf("metrics/s1.json", "covers/s1.jpg"), entries.files)
        assertEquals("[1]", File(staging, "metrics/s1.json").readText())
        assertEquals("jpg", File(staging, "covers/s1.jpg").readText())
    }

    @Test
    fun tellsAQuickBackupFromAFullOne() {
        assertTrue(QuickBackupZip.isQuickBackup(ByteArrayInputStream(zip("quick_backup.json" to "{}"))))
        assertFalse(QuickBackupZip.isQuickBackup(ByteArrayInputStream(zip("manifest.json" to "{}", "quick_backup.json" to "{}"))))
        assertFalse(QuickBackupZip.isQuickBackup(ByteArrayInputStream("not a zip".toByteArray())))
    }

    @Test
    fun rejectsChapterTextUnknownEntriesAndAMisplacedManifest() {
        assertEquals(
            "Invalid quick backup: unsupported ZIP entry novels/s1/0001_c1.html",
            rejected(zip("quick_backup.json" to "{}", "novels/s1/0001_c1.html" to "text")),
        )
        assertEquals(
            "Invalid quick backup: unsupported ZIP entry settings.json",
            rejected(
                zip(
                    "quick_backup.json" to "{}",
                    "settings.json" to "{}",
                ),
            ),
        )
        assertEquals(
            "Invalid quick backup: the manifest must come first, once",
            rejected(zip("metrics/s1.json" to "[]", "quick_backup.json" to "{}")),
        )
    }

    @Test
    fun enforcesTheSizeLimit() {
        val message = rejected(zip("quick_backup.json" to "{}", "covers/s1.jpg" to "x".repeat(100)), maxTotalBytes = 50)
        assertTrue(message, message.startsWith("Quick-backup entry covers/s1.jpg exceeds"))
    }
}
