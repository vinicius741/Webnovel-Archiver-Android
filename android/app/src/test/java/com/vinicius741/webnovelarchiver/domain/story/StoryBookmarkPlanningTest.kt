package com.vinicius741.webnovelarchiver.domain.story

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.EpubConfig
import com.vinicius741.webnovelarchiver.domain.model.Story
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoryBookmarkPlanningTest {
    @Test
    fun withBookmarkAnchorsEpubRangeStartAtBookmarkWhenStartAtBookmarkIsEnabled() {
        val story =
            story().apply {
                epubConfig =
                    EpubConfig(
                        maxChaptersPerEpub = 150,
                        rangeStart = 1,
                        rangeEnd = 4,
                        startAtBookmark = true,
                    )
            }

        val updated = StoryBookmarkPlanning.withBookmark(story, "c2", toggleExisting = false)

        assertEquals("c2", updated.lastReadChapterId)
        // Chapter c2 is at index 1 → 1-based range start is 2, so the bookmarked chapter is included.
        assertEquals(2, updated.epubConfig?.rangeStart)
        assertEquals(4, updated.epubConfig?.rangeEnd)
    }

    @Test
    fun withBookmarkKeepsEpubRangeWhenStartAfterBookmarkIsDisabled() {
        val story =
            story().apply {
                epubConfig =
                    EpubConfig(
                        maxChaptersPerEpub = 150,
                        rangeStart = 1,
                        rangeEnd = 4,
                        startAtBookmark = false,
                    )
            }

        val updated = StoryBookmarkPlanning.withBookmark(story, "c2", toggleExisting = false)

        assertEquals("c2", updated.lastReadChapterId)
        assertEquals(1, updated.epubConfig?.rangeStart)
    }

    @Test
    fun withBookmarkTogglesExistingBookmarkOffWithoutMovingEpubRange() {
        val story =
            story().apply {
                lastReadChapterId = "c2"
                epubConfig =
                    EpubConfig(
                        maxChaptersPerEpub = 150,
                        rangeStart = 3,
                        rangeEnd = 4,
                        startAtBookmark = true,
                    )
            }

        val updated = StoryBookmarkPlanning.withBookmark(story, "c2", toggleExisting = true)

        assertNull(updated.lastReadChapterId)
        assertEquals(3, updated.epubConfig?.rangeStart)
    }

    @Test
    fun withBookmarkCanKeepExistingBookmarkWhenToggleIsDisabled() {
        val story =
            story().apply {
                lastReadChapterId = "c2"
                epubConfig =
                    EpubConfig(
                        maxChaptersPerEpub = 150,
                        rangeStart = 1,
                        rangeEnd = 4,
                        startAtBookmark = true,
                    )
            }

        val updated = StoryBookmarkPlanning.withBookmark(story, "c2", toggleExisting = false)

        assertEquals("c2", updated.lastReadChapterId)
        // Bookmark on c2 (index 1) → range start anchors at chapter 2 (1-based), including it.
        assertEquals(2, updated.epubConfig?.rangeStart)
    }

    private fun story(): Story =
        Story(
            id = "story",
            title = "Story",
            chapters =
                mutableListOf(
                    Chapter(id = "c1", title = "One"),
                    Chapter(id = "c2", title = "Two"),
                    Chapter(id = "c3", title = "Three"),
                    Chapter(id = "c4", title = "Four"),
                ),
        )
}
