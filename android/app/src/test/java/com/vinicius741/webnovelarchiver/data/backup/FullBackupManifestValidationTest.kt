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

    @Test
    fun metricFilesOptionalAndValidatedWhenPresent() {
        // Absent key (backups predating the Trends feature) is accepted — restore yields empty history.
        assertNull(FullBackupManifestValidation.validate(validManifest().minus("metricFiles")))
        // A well-formed entry is accepted.
        assertNull(
            FullBackupManifestValidation.validate(
                validManifest("metricFiles" to listOf(mapOf("storyId" to "story-1", "path" to "metrics/story-1.json"))),
            ),
        )
        // storyId not in library, malformed entry, disallowed path, and duplicates are each rejected.
        assertEquals(
            "Invalid full backup: malformed metric file index",
            FullBackupManifestValidation.validate(
                validManifest("metricFiles" to listOf(mapOf("storyId" to "missing-story", "path" to "metrics/missing-story.json"))),
            ),
        )
        assertEquals(
            "Invalid full backup: malformed metric file index",
            FullBackupManifestValidation.validate(validManifest("metricFiles" to listOf(mapOf("storyId" to "story-1")))),
        )
        assertEquals(
            "Invalid full backup: malformed metric file index",
            FullBackupManifestValidation.validate(
                validManifest("metricFiles" to listOf(mapOf("storyId" to "story-1", "path" to "settings.json"))),
            ),
        )
        val metricEntry = mapOf("storyId" to "story-1", "path" to "metrics/story-1.json")
        // Two distinct stories sharing one path trips the duplicate-path check. The library has both
        // ids so the "too many metric files" guard (size > library size) does not fire first.
        val twoStoryLibrary = listOf(mapOf("id" to "story-1"), mapOf("id" to "story-2"))
        assertEquals(
            "Invalid full backup: duplicate metric paths",
            FullBackupManifestValidation.validate(
                validManifest(
                    "library" to twoStoryLibrary,
                    "metricFiles" to listOf(metricEntry, mapOf("storyId" to "story-2", "path" to "metrics/story-1.json")),
                ),
            ),
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
