package com.vinicius741.webnovelarchiver.data.storage

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class FullBackupZipExtractorTest {
    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = File(System.getProperty("java.io.tmpdir"), "backup_extract_${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun extractsOnlyAllowlistedManifestAndChapterFiles() {
        val zip = zipOf("manifest.json" to "{}", "novels/story/chapter.html" to "chapter")
        val output = File(directory, "output")

        val result = FullBackupZipExtractor.extract(zip, output, Long.MAX_VALUE)

        assertEquals(setOf("manifest.json", "novels/story/chapter.html"), result.files)
        assertEquals("chapter", File(output, "novels/story/chapter.html").readText())
    }

    @Test
    fun rejectsUnindexedFormatEntriesBeforeWritingThem() {
        val zip = zipOf("manifest.json" to "{}", "settings.json" to "secret")
        val output = File(directory, "output")

        assertThrows(IllegalStateException::class.java) {
            FullBackupZipExtractor.extract(zip, output, Long.MAX_VALUE)
        }

        assertEquals(false, File(output, "settings.json").exists())
    }

    @Test
    fun extractsMetricHistoryFilesAlongsideChapters() {
        val zip =
            zipOf(
                "manifest.json" to "{}",
                "novels/story/chapter.html" to "chapter",
                "metrics/story.json" to """{"snapshots":[]}""",
            )
        val output = File(directory, "output")

        val result = FullBackupZipExtractor.extract(zip, output, Long.MAX_VALUE)

        assertEquals(
            setOf("manifest.json", "novels/story/chapter.html", "metrics/story.json"),
            result.files,
        )
        assertEquals("""{"snapshots":[]}""", File(output, "metrics/story.json").readText())
    }

    @Test
    fun extractsStoredCoverEntriesByteForByte() {
        val cover = ByteArray(70_000) { (it * 31).toByte() }
        val zip =
            File(directory, "stored.zip").also { file ->
                ZipOutputStream(file.outputStream()).use { out ->
                    out.putNextEntry(ZipEntry("manifest.json"))
                    out.write("{}".toByteArray())
                    out.closeEntry()
                    out.putStoredEntry("covers/story.webp", cover)
                }
            }
        val output = File(directory, "output")

        val result = FullBackupZipExtractor.extract(zip, output, Long.MAX_VALUE)

        assertEquals(setOf("manifest.json", "covers/story.webp"), result.files)
        assertArrayEquals(cover, File(output, "covers/story.webp").readBytes())
        assertEquals(ZipEntry.STORED, ZipFile(zip).use { it.getEntry("covers/story.webp").method })
    }

    @Test
    fun rejectsAnArchiveThatExpandsBeyondTheRatioLimit() {
        // ~2 KB compressed per entry inflating to 8 MB each: a classic deflate bomb that a fixed
        // byte cap used to stop and the ratio guard must stop now that the cap scales with free space.
        val bomb = "0".repeat(8 * 1024 * 1024)
        val zip = zipOf("manifest.json" to "{}", *Array(12) { "novels/story/$it.html" to bomb })
        val output = File(directory, "output")

        val failure =
            assertThrows(IllegalStateException::class.java) {
                FullBackupZipExtractor.extract(zip, output, Long.MAX_VALUE)
            }

        assertTrue(failure.message.orEmpty().contains("expands more than"))
    }

    @Test
    fun diskBudgetCountsEveryFileAsWholeBlocks() {
        // 10 one-byte chapters hold 10 bytes but occupy 10 blocks on disk.
        val entries = Array(10) { "novels/story/$it.html" to "x" }
        val zip = zipOf("manifest.json" to "{}", *entries)

        assertThrows(IllegalStateException::class.java) {
            FullBackupZipExtractor.extract(zip, File(directory, "tight"), Long.MAX_VALUE, budgetBytes = 10L * 4096L)
        }
        val result = FullBackupZipExtractor.extract(zip, File(directory, "fits"), Long.MAX_VALUE, budgetBytes = 11L * 4096L)
        assertEquals(11, result.files.size)
    }

    private fun zipOf(vararg entries: Pair<String, String>): File =
        File(directory, "backup_${System.nanoTime()}.zip").also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                entries.forEach { (name, contents) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(contents.toByteArray())
                    zip.closeEntry()
                }
            }
        }
}
