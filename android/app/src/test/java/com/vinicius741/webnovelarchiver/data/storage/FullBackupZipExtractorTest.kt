package com.vinicius741.webnovelarchiver.data.storage

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
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
