package com.vinicius741.webnovelarchiver.data.storage

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/*
 * R06: a failed atomic replacement must leave the previous destination fully intact and must not
 * report success. Rename, copy, and fsync failures are injected through the AtomicFileOps seam.
 */
class AtomicFileWritesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun tearDown() {
        AtomicFileWrites.useDefaultOps()
    }

    @Test
    fun renameFailurePreservesOldFileAndThrows() {
        val destination = tmp.newFile("chapter.html")
        destination.writeText("known good content")
        AtomicFileWrites.ops =
            object : AtomicFileOps by DefaultAtomicFileOps {
                override fun rename(
                    temp: File,
                    destination: File,
                ): Boolean = false
            }

        val error =
            runCatching {
                AtomicFileWrites.writeText(destination, "new content")
            }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals("known good content", destination.readText())
        assertTrue("orphan temp must be cleaned up", destination.parentFile!!.listFiles()!!.none { it.name.contains(".tmp.") })
    }

    @Test
    fun fsyncFailurePreservesOldFileAndThrows() {
        val destination = tmp.newFile("chapter.html")
        destination.writeText("known good content")
        AtomicFileWrites.ops =
            object : AtomicFileOps by DefaultAtomicFileOps {
                override fun fsync(file: File) = throw IOException("simulated fsync failure")
            }

        val error =
            runCatching {
                AtomicFileWrites.writeText(destination, "new content")
            }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals("known good content", destination.readText())
    }

    @Test
    fun copyFailurePreservesOldDestinationAndThrows() {
        val source = tmp.newFile("source.html")
        source.writeText("new chapter html")
        val destination = tmp.newFile("chapter.html")
        destination.writeText("known good content")
        AtomicFileWrites.ops =
            object : AtomicFileOps by DefaultAtomicFileOps {
                override fun copy(
                    source: File,
                    destination: File,
                ) = throw IOException("simulated disk-full copy failure")
            }

        val error =
            runCatching {
                AtomicFileWrites.copyAtomically(source, destination)
            }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals("known good content", destination.readText())
    }

    @Test
    fun successfulWriteReplacesContentAtomically() {
        val destination = tmp.newFile("chapter.html")
        destination.writeText("old")

        AtomicFileWrites.writeText(destination, "new")

        assertEquals("new", destination.readText())
        assertFalse(destination.parentFile!!.listFiles()!!.any { it.name.contains(".tmp.") })
    }

    @Test
    fun successfulCopyStreamsContentThroughCommit() {
        val source = tmp.newFile("source.html")
        source.writeText("archived chapter html")
        val destination = tmp.newFile("chapter.html")
        destination.writeText("old")

        AtomicFileWrites.copyAtomically(source, destination)

        assertEquals("archived chapter html", destination.readText())
    }
}
