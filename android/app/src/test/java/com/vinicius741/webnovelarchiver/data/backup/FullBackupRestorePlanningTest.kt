package com.vinicius741.webnovelarchiver.data.backup

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FullBackupRestorePlanningTest {
    @Test
    fun scrubTransientStateClearsDownloadContentAndEpubPointers() {
        val stories =
            mutableListOf(
                Story(
                    id = "s1",
                    epubPath = "/old/book.epub",
                    epubPaths = mutableListOf("/old/book-1.epub"),
                    epubStale = true,
                    downloadedChapters = 1,
                    chapters =
                        mutableListOf(
                            Chapter(
                                id = "c1",
                                title = "One",
                                content = "<p>cached</p>",
                                filePath = "/old/chapter.html",
                                downloaded = true,
                                downloadedAt = 1_700_000_000_000L,
                            ),
                            Chapter(id = "c2", title = "Two"),
                        ),
                ),
            )

        FullBackupRestorePlanning.scrubTransientState(stories)

        val story = stories.single()
        assertNull(story.epubPath)
        assertNull(story.epubPaths)
        assertNull(story.epubStale)
        assertEquals(2, story.totalChapters)
        assertEquals(0, story.downloadedChapters)
        assertNull(story.chapters[0].content)
        assertNull(story.chapters[0].filePath)
        assertFalse(story.chapters[0].downloaded)
        assertEquals(1_700_000_000_000L, story.chapters[0].downloadedAt)
    }

    @Test
    fun applyRestoredChapterFilesMarksOnlyExistingBackupFilesAndRefreshesCounts() {
        val stories =
            mutableListOf(
                Story(
                    id = "s1",
                    downloadedChapters = 0,
                    chapters =
                        mutableListOf(
                            Chapter(id = "c1", title = "One", downloadedAt = 1_700_000_000_000L),
                            Chapter(id = "c2", title = "Two", downloadedAt = 1_600_000_000_000L),
                        ),
                ),
            )
        val chapterFiles =
            listOf(
                RestoredChapterFileIndex("s1", "c1", "novels/s1/0000_c1.html"),
                RestoredChapterFileIndex("s1", "c2", "novels/s1/missing.html"),
                RestoredChapterFileIndex("s1", "missing", "novels/s1/other.html"),
            )

        FullBackupRestorePlanning.applyRestoredChapterFiles(stories, chapterFiles) { path ->
            if (path.endsWith("0000_c1.html")) "/restored/$path" else null
        }

        val story = stories.single()
        assertTrue(story.chapters[0].downloaded)
        assertEquals("/restored/novels/s1/0000_c1.html", story.chapters[0].filePath)
        assertEquals(1_700_000_000_000L, story.chapters[0].downloadedAt)
        assertFalse(story.chapters[1].downloaded)
        assertNull(story.chapters[1].filePath)
        assertNull(story.chapters[1].downloadedAt)
        assertEquals(2, story.totalChapters)
        assertEquals(1, story.downloadedChapters)
    }

    @Test
    fun restoreSummaryReportsNovelsAndDownloadedChapters() {
        val stories =
            listOf(
                Story(
                    id = "s1",
                    chapters =
                        mutableListOf(
                            Chapter(id = "c1", downloaded = true),
                            Chapter(id = "c2", downloaded = false),
                        ),
                ),
                Story(
                    id = "s2",
                    chapters = mutableListOf(Chapter(id = "c3", downloaded = true)),
                ),
            )

        assertEquals(
            "Restored 2 novels and 2 downloaded chapters",
            FullBackupRestorePlanning.restoreSummary(stories),
        )
    }
}
