package com.vinicius741.webnovelarchiver.domain.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ArchiveUtilsTest {
    @Test
    fun safeExtractionTargetRejectsTraversalAndAbsolutePaths() {
        val root = Files.createTempDirectory("webnovel_zip_safe").toFile()

        assertNotNull(ArchiveUtils.safeExtractionTarget(root, "novels/story/chapter.html"))
        assertNull(ArchiveUtils.safeExtractionTarget(root, "../escape.txt"))
        assertNull(ArchiveUtils.safeExtractionTarget(root, "novels/../../escape.txt"))
        assertNull(ArchiveUtils.safeExtractionTarget(root, "/absolute/path.txt"))
        assertNull(ArchiveUtils.safeExtractionTarget(root, "..\\escape.txt"))
    }

    @Test
    fun putStoredEntryCreatesUncompressedEntryWithExpectedContents() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            ArchiveUtils.putStoredEntry(zip, "mimetype", "application/epub+zip".toByteArray())
        }

        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            val entry = zip.nextEntry
            assertEquals("mimetype", entry.name)
            assertEquals(ZipEntry.STORED, entry.method)
            assertEquals("application/epub+zip", zip.readBytes().decodeToString())
        }
    }
}
