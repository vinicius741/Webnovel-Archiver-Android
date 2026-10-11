package com.vinicius741.webnovelarchiver.data.storage

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ChapterEvidenceStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `scores survive recreation and corrupt entries are cache misses`() {
        val root = temporary.newFolder()
        val value = JsonObject().apply { addProperty("model", "test") }
        ChapterEvidenceStore(root).write("hash", value)
        assertEquals(value, ChapterEvidenceStore(root).read("hash"))
        File(root, "cover_evidence/hash").writeText("truncated{")
        assertNull(ChapterEvidenceStore(root).read("hash"))
        assertNull(ChapterEvidenceStore(root).read("absent"))
        ChapterEvidenceStore(root).write("hash", value)
        assertEquals(value, ChapterEvidenceStore(root).read("hash"))
    }

    @Test fun `selections survive recreation overwrite per story and tolerate corrupt files`() {
        val root = temporary.newFolder()
        val file = File(root, "cover_evidence_selections.json")
        val store = ChapterEvidenceStore(root)
        assertNull(store.selection("rr_1"))
        store.record("rr_1", listOf(0, 4, 9))
        store.record("sb_2", listOf(2))
        assertEquals(listOf(0, 4, 9), ChapterEvidenceStore(root).selection("rr_1"))
        assertEquals(listOf(2), ChapterEvidenceStore(root).selection("sb_2"))
        ChapterEvidenceStore(root).record("rr_1", listOf(1))
        assertEquals(listOf(1), ChapterEvidenceStore(root).selection("rr_1"))
        file.writeText("truncated{")
        assertNull(ChapterEvidenceStore(root).selection("rr_1"))
        file.delete()
        ChapterEvidenceStore(root).record("rr_1", listOf(3))
        assertEquals(listOf(3), ChapterEvidenceStore(root).selection("rr_1"))
    }

    @Test fun `cover and description selections are kept apart`() {
        val root = temporary.newFolder()
        val store = ChapterEvidenceStore(root)
        store.record("rr_1", listOf(0, 4, 9))
        store.record("rr_1", listOf(1, 2), EvidenceSelectionKind.DESCRIPTION)
        assertEquals(listOf(0, 4, 9), store.selection("rr_1", EvidenceSelectionKind.COVER))
        assertEquals(listOf(1, 2), ChapterEvidenceStore(root).selection("rr_1", EvidenceSelectionKind.DESCRIPTION))
        assertEquals(listOf(0, 4, 9), ChapterEvidenceStore(root).selection("rr_1"))
        File(root, "description_evidence_selections.json").writeText("truncated{")
        assertNull(ChapterEvidenceStore(root).selection("rr_1", EvidenceSelectionKind.DESCRIPTION))
        assertEquals(listOf(0, 4, 9), ChapterEvidenceStore(root).selection("rr_1"))
    }

    @Test fun `selection reads stay cached after the disposable file is removed`() {
        val root = temporary.newFolder()
        val store = ChapterEvidenceStore(root)
        val indices = mutableListOf(0, 4, 9)
        store.record("story", indices)
        indices.clear()
        assertTrue(File(root, "cover_evidence_selections.json").delete())

        assertEquals(listOf(0, 4, 9), store.selection("story"))
        assertNull(ChapterEvidenceStore(root).selection("story"))
    }

    @Test fun `selection reads do not wait for score cache maintenance`() {
        val store = ChapterEvidenceStore(temporary.newFolder())
        store.record("story", listOf(0, 4, 9))
        val executor = Executors.newFixedThreadPool(2)
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            executor.submit {
                synchronized(store) {
                    locked.countDown()
                    release.await()
                }
            }
            assertTrue(locked.await(5, TimeUnit.SECONDS))
            val selection = executor.submit<List<Int>?> { store.selection("story") }
            assertEquals(listOf(0, 4, 9), selection.get(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
