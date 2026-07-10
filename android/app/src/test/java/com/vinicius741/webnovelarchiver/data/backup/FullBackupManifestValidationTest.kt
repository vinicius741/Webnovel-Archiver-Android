package com.vinicius741.webnovelarchiver.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FullBackupManifestValidationTest {
    @Test
    fun acceptsValidManifestShape() {
        assertNull(FullBackupManifestValidation.validate(validManifest()))
    }

    @Test
    fun exposesMissingManifestMessage() {
        assertEquals(
            "Invalid full backup: missing manifest",
            FullBackupManifestValidation.MISSING_MANIFEST_MESSAGE,
        )
    }

    @Test
    fun rejectsUnsupportedFormatAndMissingVersion() {
        assertEquals(
            "Invalid full backup: unsupported format",
            FullBackupManifestValidation.validate(validManifest("format" to "other")),
        )
        assertEquals(
            "Invalid full backup: missing version",
            FullBackupManifestValidation.validate(validManifest().minus("version")),
        )
    }

    @Test
    fun rejectsMissingIndexesAndConfiguration() {
        assertEquals(
            "Invalid full backup: missing library",
            FullBackupManifestValidation.validate(validManifest("library" to "bad")),
        )
        assertEquals(
            "Invalid full backup: missing chapter file index",
            FullBackupManifestValidation.validate(validManifest("chapterFiles" to "bad")),
        )
        assertEquals(
            "Invalid full backup: missing configuration",
            FullBackupManifestValidation.validate(validManifest("config" to mapOf<String, Any>())),
        )
    }

    @Test
    fun rejectsMalformedStories() {
        assertEquals(
            "Invalid full backup: malformed story data",
            FullBackupManifestValidation.validate(validManifest("library" to listOf(mapOf("title" to "Missing id")))),
        )
    }

    @Test
    fun rejectsUnsupportedVersionsAndDuplicateStoryIds() {
        assertEquals(
            "Invalid full backup: unsupported version 2",
            FullBackupManifestValidation.validate(validManifest("version" to 2)),
        )
        assertEquals(
            "Invalid full backup: duplicate story IDs",
            FullBackupManifestValidation.validate(
                validManifest("library" to listOf(mapOf("id" to "same"), mapOf("id" to "same"))),
            ),
        )
        assertEquals(
            "Invalid full backup: missing version",
            FullBackupManifestValidation.validate(validManifest("version" to 1.5)),
        )
    }

    @Test
    fun rejectsMalformedAndDuplicateChapterFileEntries() {
        assertEquals(
            "Invalid full backup: malformed chapter file index",
            FullBackupManifestValidation.validate(
                validManifest(
                    "chapterFiles" to
                        listOf(
                            mapOf(
                                "storyId" to "missing-story",
                                "chapterId" to "chapter-1",
                                "path" to "novels/story-1/chapter-1.html",
                            ),
                        ),
                ),
            ),
        )
        val entry =
            mapOf(
                "storyId" to "story-1",
                "chapterId" to "chapter-1",
                "path" to "novels/story-1/chapter-1.html",
            )
        assertEquals(
            "Invalid full backup: duplicate chapter paths",
            FullBackupManifestValidation.validate(validManifest("chapterFiles" to listOf(entry, entry))),
        )
    }

    private fun validManifest(vararg replacements: Pair<String, Any>): Map<String, Any> =
        mapOf(
            "format" to "webnovel-archiver-full-backup",
            "version" to 1,
            "library" to listOf(mapOf("id" to "story-1")),
            "config" to mapOf("tabs" to emptyList<Any>()),
            "chapterFiles" to emptyList<Any>(),
        ) + replacements.toMap()
}
