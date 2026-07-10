package com.vinicius741.webnovelarchiver.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubRetentionPolicyTest {
    @Test
    fun referencedFilesAreNeverDeletedAndNewestLegacyFilesAreRetained() {
        val entries =
            listOf(
                entry("current-a", 5),
                entry("current-b", 4),
                entry("legacy-new", 3),
                entry("legacy-middle", 2),
                entry("legacy-old", 1),
            )

        val plan = EpubRetentionPolicy.plan(entries, setOf("current-a", "current-b"), legacyFilesToKeep = 2)

        assertEquals(listOf("legacy-old"), plan.delete.map { it.path })
        assertEquals(setOf("current-a", "current-b"), plan.referenced.map { it.path }.toSet())
        assertEquals(listOf("legacy-new", "legacy-middle"), plan.retainedLegacy.map { it.path })
    }

    @Test
    fun cleanupResultReportsOnlySuccessfulReclamation() {
        val plan =
            EpubRetentionPolicy.plan(
                listOf(entry("keep", 30, 3), entry("delete-a", 20, 2), entry("delete-b", 10, 1)),
                referencedPaths = setOf("keep"),
                legacyFilesToKeep = 0,
            )

        val result = EpubRetentionPolicy.result(plan, deletedPaths = setOf("delete-a"))

        assertEquals(3, result.filesBefore)
        assertEquals(1, result.filesDeleted)
        assertEquals(6, result.bytesBefore)
        assertEquals(2, result.bytesReclaimed)
        assertEquals(listOf("delete-b"), result.failedPaths)
    }

    @Test
    fun defaultRetentionIsBounded() {
        val entries = (1..10).map { entry("legacy-$it", it.toLong(), it.toLong()) }

        val plan = EpubRetentionPolicy.plan(entries, emptySet())

        assertEquals(EpubRetentionPolicy.DEFAULT_LEGACY_FILES_TO_KEEP, plan.retainedLegacy.size)
        assertEquals(7, plan.delete.size)
        assertTrue(plan.referenced.isEmpty())
    }

    private fun entry(
        path: String,
        modified: Long,
        bytes: Long = modified,
    ) = EpubStorageEntry(path, bytes, modified)
}
