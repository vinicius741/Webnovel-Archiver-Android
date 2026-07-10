package com.vinicius741.webnovelarchiver.data.backup

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

    @Test
    fun rejectsUnsupportedVersionsAndDuplicateStoryIds() {
        assertEquals(
            "Invalid backup file: unsupported version 99",
            JsonBackupValidation.validate(mapOf("version" to 99, "library" to emptyList<Any>())),
        )
        assertEquals(
            "Invalid backup file: duplicate story IDs",
            JsonBackupValidation.validate(
                mapOf(
                    "version" to 2,
                    "library" to listOf(mapOf("id" to "same"), mapOf("id" to "same")),
                ),
            ),
        )
        assertEquals(
            "Invalid backup file: missing version",
            JsonBackupValidation.validate(mapOf("version" to 2.5, "library" to emptyList<Any>())),
        )
    }
}
