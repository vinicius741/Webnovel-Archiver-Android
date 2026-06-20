package com.vinicius741.webnovelarchiver.epub

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.EpubConfig
import com.vinicius741.webnovelarchiver.domain.model.Story
import org.junit.Assert.assertEquals
import org.junit.Test

class EpubSelectionTest {
    @Test
    fun selectsDownloadedChaptersInConfiguredRangeWithOriginalNumbers() {
        val story =
            storyWithChapters(
                downloaded = listOf(true, false, true, true),
            )

        val selected =
            EpubSelection.selectDownloadedChapters(
                story,
                EpubConfig(maxChaptersPerEpub = 150, rangeStart = 2, rangeEnd = 4, startAfterBookmark = false),
            )

        assertEquals(listOf("c3", "c4"), selected.map { it.chapter.id })
        assertEquals(listOf(3, 4), selected.map { it.originalChapterNumber })
    }

    @Test
    fun startAfterBookmarkBeginsAfterLastReadChapter() {
        val story =
            storyWithChapters(
                downloaded = listOf(true, true, true, true),
            ).apply {
                lastReadChapterId = "c2"
            }

        val selected =
            EpubSelection.selectDownloadedChapters(
                story,
                EpubConfig(maxChaptersPerEpub = 150, rangeStart = 1, rangeEnd = 4, startAfterBookmark = true),
            )

        assertEquals(listOf("c3", "c4"), selected.map { it.chapter.id })
        assertEquals(listOf(3, 4), selected.map { it.originalChapterNumber })
    }

    @Test
    fun displayNameForPathDecodesFilenameAndRemovesEpubExtension() {
        assertEquals(
            "My Story Ch1-150",
            EpubSelection.displayNameForPath("/data/user/0/app/files/epubs/My_Story_Ch1-150.epub"),
        )
        assertEquals(
            "Volume 2",
            EpubSelection.displayNameForPath("content://provider/tree/backup%3AVolume_2.epub"),
        )
    }

    @Test
    fun rangeCoverageReportsMissingChaptersWhenDownloadIsIncomplete() {
        val story = storyWithChapters(downloaded = listOf(true, false, true, true))

        val coverage =
            EpubSelection.rangeCoverage(
                story,
                EpubConfig(maxChaptersPerEpub = 150, rangeStart = 1, rangeEnd = 4, startAfterBookmark = false),
            )

        assertEquals(4, coverage.inRange)
        assertEquals(3, coverage.downloaded)
        assertEquals(1, coverage.missing)
    }

    @Test
    fun rangeCoverageHasNoMissingWhenEverythingInRangeIsDownloaded() {
        val story = storyWithChapters(downloaded = listOf(true, true, true, true))

        val coverage =
            EpubSelection.rangeCoverage(
                story,
                EpubConfig(maxChaptersPerEpub = 150, rangeStart = 1, rangeEnd = 4, startAfterBookmark = false),
            )

        assertEquals(0, coverage.missing)
    }

    @Test
    fun rangeCoverageRestrictsToConfiguredRange() {
        val story = storyWithChapters(downloaded = listOf(true, false, false, true))

        // Range 2..4 covers chapters 2 (missing), 3 (missing), 4 (downloaded); chapter 1 is outside.
        val coverage =
            EpubSelection.rangeCoverage(
                story,
                EpubConfig(maxChaptersPerEpub = 150, rangeStart = 2, rangeEnd = 4, startAfterBookmark = false),
            )

        assertEquals(3, coverage.inRange)
        assertEquals(1, coverage.downloaded)
        assertEquals(2, coverage.missing)
    }

    @Test
    fun rangeCoverageStartsAfterBookmarkWhenRequested() {
        val story =
            storyWithChapters(downloaded = listOf(true, true, true, true)).apply {
                lastReadChapterId = "c2"
            }

        // Bookmark on chapter 2 → effective range starts at chapter 3, so chapters 1-2 are excluded.
        val coverage =
            EpubSelection.rangeCoverage(
                story,
                EpubConfig(maxChaptersPerEpub = 150, rangeStart = 1, rangeEnd = 4, startAfterBookmark = true),
            )

        assertEquals(2, coverage.inRange)
        assertEquals(2, coverage.downloaded)
        assertEquals(0, coverage.missing)
    }

    @Test
    fun rangeCoverageReturnsZerosForEmptyStory() {
        val story = Story(id = "empty", title = "Empty", author = "Author", chapters = mutableListOf())

        val coverage =
            EpubSelection.rangeCoverage(
                story,
                EpubConfig(maxChaptersPerEpub = 150, rangeStart = 1, rangeEnd = 10, startAfterBookmark = false),
            )

        assertEquals(0, coverage.inRange)
        assertEquals(0, coverage.downloaded)
        assertEquals(0, coverage.missing)
    }

    private fun storyWithChapters(downloaded: List<Boolean>): Story =
        Story(
            id = "story",
            title = "Story",
            author = "Author",
            chapters =
                downloaded
                    .mapIndexed { index, isDownloaded ->
                        Chapter(
                            id = "c${index + 1}",
                            title = "Chapter ${index + 1}",
                            url = "https://example.com/${index + 1}",
                            downloaded = isDownloaded,
                            content = if (isDownloaded) "<p>Body</p>" else null,
                        )
                    }.toMutableList(),
        )
}
