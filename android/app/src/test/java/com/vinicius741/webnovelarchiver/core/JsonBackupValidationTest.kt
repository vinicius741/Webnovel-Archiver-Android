package com.vinicius741.webnovelarchiver.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonBackupValidationTest {
    @Test
    fun acceptsValidJsonBackupShape() {
        assertNull(
            JsonBackupValidation.validate(
                mapOf(
                    "version" to 2.0,
                    "library" to listOf(mapOf("id" to "story-1")),
                ),
            ),
        )
    }

    @Test
    fun rejectsMissingVersionLibraryAndMalformedStories() {
        assertEquals(
            "Invalid backup file: missing version",
            JsonBackupValidation.validate(mapOf("library" to emptyList<Any>())),
        )
        assertEquals(
            "Invalid backup file: missing library",
            JsonBackupValidation.validate(mapOf("version" to 2.0)),
        )
        assertEquals(
            "Invalid backup file: malformed story data",
            JsonBackupValidation.validate(
                mapOf(
                    "version" to 2.0,
                    "library" to listOf(mapOf("title" to "Missing id")),
                ),
            ),
        )
    }
}
