package com.vinicius741.webnovelarchiver.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CoverEvidenceSelectionStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `selections survive recreation overwrite per story and tolerate corrupt files`() {
        val file = temporary.newFile("cover_evidence_selections.json")
        val store = CoverEvidenceSelectionStore(file)
        assertNull(store.selection("rr_1"))
        store.record("rr_1", listOf(0, 4, 9))
        store.record("sb_2", listOf(2))
        assertEquals(listOf(0, 4, 9), CoverEvidenceSelectionStore(file).selection("rr_1"))
        assertEquals(listOf(2), CoverEvidenceSelectionStore(file).selection("sb_2"))
        CoverEvidenceSelectionStore(file).record("rr_1", listOf(1))
        assertEquals(listOf(1), CoverEvidenceSelectionStore(file).selection("rr_1"))
        file.writeText("truncated{")
        assertNull(CoverEvidenceSelectionStore(file).selection("rr_1"))
        File(file.parentFile, "cover_evidence_selections.json").delete()
        CoverEvidenceSelectionStore(file).record("rr_1", listOf(3))
        assertEquals(listOf(3), CoverEvidenceSelectionStore(file).selection("rr_1"))
    }
}
