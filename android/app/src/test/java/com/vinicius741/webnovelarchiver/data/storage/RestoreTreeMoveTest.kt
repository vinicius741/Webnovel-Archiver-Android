package com.vinicius741.webnovelarchiver.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class RestoreTreeMoveTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val raw get() = File(tmp.root, "work/raw_extraction")
    private val staged get() = File(tmp.root, "work/staged_root")

    private val refusingRename =
        object : RestoreFileOperations {
            override fun rename(
                source: File,
                destination: File,
            ): Boolean = false
        }

    private fun write(
        root: File,
        tree: Map<String, String>,
    ) {
        tree.forEach { (path, text) -> File(root, path).apply { parentFile?.mkdirs() }.writeText(text) }
    }

    private fun contents(root: File): Map<String, String> =
        root
            .walkTopDown()
            .filter(File::isFile)
            .associate { it.relativeTo(root).path to it.readText() }

    private val extraction =
        mapOf(
            "manifest.json" to "{}",
            "novels/a/1.html" to "chapter",
            "metrics/a.json" to "{}",
            "covers/a.jpg" to "cover",
            "chapter_rewrites/a/manifest.json" to "{}",
        )

    @Test
    fun adoptsDataTreesByRenameAndLeavesTheRestInTheExtraction() {
        write(raw, extraction)
        staged.mkdirs()
        val chapter = File(raw, "novels/a/1.html")
        val inode = Files.getAttribute(chapter.toPath(), "unix:ino")

        RestoreTreeMove().adoptExtractedTrees(raw, staged)

        assertEquals(
            mapOf("novels/a/1.html" to "chapter", "metrics/a.json" to "{}", "covers/a.jpg" to "cover"),
            contents(staged),
        )
        // Same inode: the chapter was renamed, not rewritten.
        assertEquals(inode, Files.getAttribute(File(staged, "novels/a/1.html").toPath(), "unix:ino"))
        assertEquals(mapOf("manifest.json" to "{}", "chapter_rewrites/a/manifest.json" to "{}"), contents(raw))
    }

    @Test
    fun skipsTreesTheBackupDoesNotCarry() {
        write(raw, mapOf("manifest.json" to "{}"))
        staged.mkdirs()

        RestoreTreeMove().adoptExtractedTrees(raw, staged)

        assertTrue(staged.list()!!.isEmpty())
    }

    @Test
    fun refusedRenameFallsBackToAVerifiedCopyAndDropsTheSource() {
        write(raw, extraction)
        staged.mkdirs()

        RestoreTreeMove(refusingRename).adoptExtractedTrees(raw, staged)

        assertEquals(
            mapOf("novels/a/1.html" to "chapter", "metrics/a.json" to "{}", "covers/a.jpg" to "cover"),
            contents(staged),
        )
        assertFalse(File(raw, "novels").exists())
    }

    @Test
    fun copyFallbackRefusesWithoutSpaceAndWritesNothing() {
        write(raw, extraction)
        staged.mkdirs()

        assertThrows(IllegalStateException::class.java) {
            RestoreTreeMove(refusingRename, usableSpace = { 0L }).adoptExtractedTrees(raw, staged)
        }

        assertFalse(File(staged, "novels").exists())
        assertEquals(extraction, contents(raw))
    }

    @Test
    fun incompleteCopyIsRemovedAndTheSourceKept() {
        write(raw, extraction)
        staged.mkdirs()
        val truncating =
            object : RestoreFileOperations {
                override fun rename(
                    source: File,
                    destination: File,
                ): Boolean = false

                override fun copyTree(
                    source: File,
                    destination: File,
                ) {
                    super.copyTree(source, destination)
                    destination
                        .walkTopDown()
                        .filter(File::isFile)
                        .first()
                        .writeText("")
                }
            }

        assertThrows(IllegalStateException::class.java) {
            RestoreTreeMove(truncating).adoptExtractedTrees(raw, staged)
        }

        assertFalse(File(staged, "novels").exists())
        assertEquals(extraction, contents(raw))
    }

    @Test
    fun refusesToMergeIntoAnExistingTarget() {
        write(raw, extraction)
        write(staged, mapOf("novels/b/1.html" to "other"))

        assertThrows(IllegalStateException::class.java) {
            RestoreTreeMove().adoptExtractedTrees(raw, staged)
        }

        assertEquals(mapOf("novels/b/1.html" to "other"), contents(staged))
    }
}
