package com.vinicius741.webnovelarchiver.data.storage

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class RestoreRootSwapTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "root_swap_${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun successfulSwapReplacesLiveRootAndRemovesSnapshot() {
        val live = tree("live", "old")
        val source = tree("source", "new")
        val snapshot = File(dir, "snapshot")

        RestoreRootSwap().swap(source, live, snapshot) { live.mkdirs() }

        assertEquals("new", File(live, "value.txt").readText())
        assertFalse(snapshot.exists())
        assertFalse(source.exists())
    }

    @Test
    fun failedLiveSnapshotNeverTouchesReplacementSource() {
        val live = tree("live", "old")
        val source = tree("source", "new")
        val snapshot = File(dir, "snapshot")
        val operations =
            object : RestoreFileOperations {
                override fun rename(
                    source: File,
                    destination: File,
                ): Boolean = false
            }

        assertThrows(IllegalStateException::class.java) {
            RestoreRootSwap(operations).swap(source, live, snapshot) { }
        }

        assertEquals("old", File(live, "value.txt").readText())
        assertEquals("new", File(source, "value.txt").readText())
    }

    @Test
    fun reportedSnapshotRenameSuccessMustMatchFilesystemPostconditions() {
        val live = tree("live", "old")
        val source = tree("source", "new")
        val snapshot = File(dir, "snapshot")
        val operations =
            object : RestoreFileOperations {
                override fun rename(
                    source: File,
                    destination: File,
                ): Boolean = true
            }

        assertThrows(IllegalStateException::class.java) {
            RestoreRootSwap(operations).swap(source, live, snapshot) { }
        }

        assertEquals("old", File(live, "value.txt").readText())
        assertEquals("new", File(source, "value.txt").readText())
        assertFalse(snapshot.exists())
    }

    @Test
    fun rollbackFailurePreservesSnapshotForManualRecovery() {
        val live = tree("live", "partial")
        val snapshot = tree("snapshot", "old")
        val operations =
            object : RestoreFileOperations {
                override fun rename(
                    source: File,
                    destination: File,
                ): Boolean = false

                override fun copyTree(
                    source: File,
                    destination: File,
                ) {
                    error("injected copy failure")
                }
            }

        val restored = RestoreRootSwap(operations).rollback(live, snapshot) { }

        assertFalse(restored)
        assertTrue(snapshot.exists())
        assertEquals("old", File(snapshot, "value.txt").readText())
    }

    @Test
    fun snapshotCleanupFailureDoesNotRollBackCommittedRoot() {
        val live = tree("live", "old")
        val source = tree("source", "new")
        val snapshot = File(dir, "snapshot")
        val operations =
            object : RestoreFileOperations {
                override fun deleteTree(file: File): Boolean = if (file == snapshot) false else super.deleteTree(file)
            }

        RestoreRootSwap(operations).swap(source, live, snapshot) { live.mkdirs() }

        assertEquals("new", File(live, "value.txt").readText())
        assertEquals("old", File(snapshot, "value.txt").readText())
    }

    private fun tree(
        name: String,
        value: String,
    ): File =
        File(dir, name).apply {
            mkdirs()
            File(this, "value.txt").writeText(value)
        }
}
