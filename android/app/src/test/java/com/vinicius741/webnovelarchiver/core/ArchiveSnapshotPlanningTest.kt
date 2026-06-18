package com.vinicius741.webnovelarchiver.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveSnapshotPlanningTest {
    @Test
    fun buildArchiveSnapshotPreservesFullStoryAndCopiesDownloadedChapterFiles() {
        val source =
            Story(
                id = "story-1",
                title = "Original Title",
                author = "Author",
                sourceUrl = "https://example.com/story",
                chapters =
                    mutableListOf(
                        Chapter(id = "c1", title = "One", downloaded = true, filePath = "/active/one.html"),
                        Chapter(id = "c2", title = "Two", downloaded = false, filePath = "/stale/two.html"),
                        Chapter(id = "c3", title = "Three", downloaded = true, filePath = "/active/three.html"),
                    ),
                epubPath = "/active/story.epub",
                epubPaths = mutableListOf("/active/story-1.epub"),
                epubStale = true,
                pendingNewChapterIds = mutableListOf("c4"),
                tabId = "tab-1",
            )
        val copied = mutableListOf<String>()

        val archive =
            ArchiveSnapshotPlanning.buildArchiveSnapshot(
                source = source,
                archivedAt = 42L,
                randomSuffix = "abc123",
            ) { archiveId, index, chapter ->
                copied.add("$archiveId:$index:${chapter.id}")
                "/archive/$index-${chapter.id}.html"
            }

        assertEquals("story-1__archive_42_abc123", archive.id)
        assertEquals("Original Title", archive.title)
        assertEquals("story-1", archive.archiveOfStoryId)
        assertEquals(ArchiveSnapshotPlanning.SOURCE_CHAPTERS_REMOVED_REASON, archive.archiveReason)
        assertEquals(42L, archive.archivedAt)
        assertEquals("tab-1", archive.tabId)
        assertTrue(archive.isArchived == true)
        assertEquals(3, archive.totalChapters)
        assertEquals(2, archive.downloadedChapters)
        assertEquals(listOf("story-1__archive_42_abc123:0:c1", "story-1__archive_42_abc123:2:c3"), copied)
        assertEquals("/archive/0-c1.html", archive.chapters[0].filePath)
        assertNull(archive.chapters[1].filePath)
        assertEquals("/archive/2-c3.html", archive.chapters[2].filePath)
        assertNull(archive.epubPath)
        assertNull(archive.epubPaths)
        assertNull(archive.epubStale)
        assertNull(archive.pendingNewChapterIds)
    }

    @Test
    fun buildArchiveSnapshotFallsBackToOriginalDownloadedFilePathWhenCopyFails() {
        val source =
            Story(
                id = "story-1",
                chapters = mutableListOf(Chapter(id = "c1", downloaded = true, filePath = "/active/one.html")),
            )

        val archive =
            ArchiveSnapshotPlanning.buildArchiveSnapshot(source, archivedAt = 42L, randomSuffix = "abc123") { _, _, _ ->
                null
            }

        assertEquals("/active/one.html", archive.chapters.single().filePath)
    }
}
