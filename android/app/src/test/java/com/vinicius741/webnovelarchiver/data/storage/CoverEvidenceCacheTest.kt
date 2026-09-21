package com.vinicius741.webnovelarchiver.data.storage

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CoverEvidenceCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `scores survive recreation and corrupt entries are cache misses`() {
        val directory = temporary.newFolder()
        val value = JsonObject().apply { addProperty("model", "test") }
        CoverEvidenceCache(directory).write("hash", value)
        assertEquals(value, CoverEvidenceCache(directory).read("hash"))
        File(directory, "hash").writeText("truncated{")
        assertNull(CoverEvidenceCache(directory).read("hash"))
        assertNull(CoverEvidenceCache(directory).read("absent"))
        CoverEvidenceCache(directory).write("hash", value)
        assertEquals(value, CoverEvidenceCache(directory).read("hash"))
    }
}
