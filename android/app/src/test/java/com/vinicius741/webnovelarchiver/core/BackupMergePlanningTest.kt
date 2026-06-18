package com.vinicius741.webnovelarchiver.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupMergePlanningTest {
    @Test
    fun mergeJsonBackupStoryPreservesExistingDownloadedChapterFiles() {
        val existing =
            Story(
                id = "story",
                title = "Old Title",
                author = "Old Author",
                chapters =
                    mutableListOf(
                        Chapter(id = "c1", title = "Old 1", downloaded = true, filePath = "/files/c1.html"),
                        Chapter(id = "c2", title = "Old 2", downloaded = false),
                    ),
                lastReadChapterId = "c1",
                lastUpdated = 1000,
                dateAdded = 500,
                epubPath = "/epubs/story.epub",
                epubStale = true,
                pendingNewChapterIds = mutableListOf("c2"),
            )
        val incoming =
            Story(
                id = "story",
                title = "Imported Title",
                author = "Imported Author",
                chapters =
                    mutableListOf(
                        Chapter(id = "c1", title = "Imported 1", downloaded = false, filePath = null),
                        Chapter(id = "c2", title = "Imported 2", downloaded = false),
                        Chapter(id = "c3", title = "Imported 3", downloaded = false),
                    ),
                pendingNewChapterIds = mutableListOf("c3"),
            )

        val merged = BackupMergePlanning.mergeJsonBackupStory(incoming, existing)

        assertEquals("Imported Title", merged.title)
        assertEquals("Imported Author", merged.author)
        assertEquals(3, merged.totalChapters)
        assertEquals(1, merged.downloadedChapters)
        assertTrue(merged.chapters.first { it.id == "c1" }.downloaded)
        assertEquals("/files/c1.html", merged.chapters.first { it.id == "c1" }.filePath)
        assertEquals("c1", merged.lastReadChapterId)
        assertEquals(1000L, merged.lastUpdated)
        assertEquals(500L, merged.dateAdded)
        assertEquals("/epubs/story.epub", merged.epubPath)
        assertEquals(true, merged.epubStale)
        assertEquals(listOf("c2", "c3"), merged.pendingNewChapterIds)
    }

    @Test
    fun mergeJsonBackupStoryDropsPendingIdsForDownloadedOrMissingChapters() {
        val existing =
            Story(
                id = "story",
                chapters =
                    mutableListOf(
                        Chapter(id = "c1", downloaded = true, filePath = "/files/c1.html"),
                    ),
                pendingNewChapterIds = mutableListOf("c1", "missing"),
            )
        val incoming =
            Story(
                id = "story",
                chapters =
                    mutableListOf(
                        Chapter(id = "c1", downloaded = false),
                    ),
                pendingNewChapterIds = mutableListOf("missing"),
            )

        val merged = BackupMergePlanning.mergeJsonBackupStory(incoming, existing)

        assertNull(merged.pendingNewChapterIds)
    }

    @Test
    fun mergeJsonBackupStoryScrubsLocalStateForNewStories() {
        val incoming =
            Story(
                id = "new-story",
                totalChapters = 99,
                downloadedChapters = 1,
                epubPath = "/other-device/book.epub",
                epubPaths = mutableListOf("/other-device/book-1.epub"),
                epubStale = true,
                chapters =
                    mutableListOf(
                        Chapter(
                            id = "c1",
                            title = "One",
                            content = "<p>cached</p>",
                            filePath = "/other-device/c1.html",
                            downloaded = true,
                        ),
                    ),
            )

        val merged = BackupMergePlanning.mergeJsonBackupStory(incoming, existing = null)

        assertEquals(1, merged.totalChapters)
        assertEquals(0, merged.downloadedChapters)
        assertNull(merged.epubPath)
        assertNull(merged.epubPaths)
        assertNull(merged.epubStale)
        assertNull(merged.chapters.single().content)
        assertNull(merged.chapters.single().filePath)
        assertEquals(false, merged.chapters.single().downloaded)
    }

    @Test
    fun mergeJsonBackupStoryDoesNotTrustIncomingDownloadedStateForNewChapters() {
        val existing =
            Story(
                id = "story",
                chapters = mutableListOf(Chapter(id = "c1", downloaded = false)),
            )
        val incoming =
            Story(
                id = "story",
                chapters =
                    mutableListOf(
                        Chapter(id = "c1", downloaded = true, filePath = "/other/c1.html", content = "stale"),
                        Chapter(id = "c2", downloaded = true, filePath = "/other/c2.html", content = "stale"),
                    ),
            )

        val merged = BackupMergePlanning.mergeJsonBackupStory(incoming, existing)

        assertEquals(0, merged.downloadedChapters)
        assertEquals(false, merged.chapters.first { it.id == "c1" }.downloaded)
        assertNull(merged.chapters.first { it.id == "c1" }.filePath)
        assertNull(merged.chapters.first { it.id == "c1" }.content)
        assertEquals(false, merged.chapters.first { it.id == "c2" }.downloaded)
        assertNull(merged.chapters.first { it.id == "c2" }.filePath)
        assertNull(merged.chapters.first { it.id == "c2" }.content)
    }
}
