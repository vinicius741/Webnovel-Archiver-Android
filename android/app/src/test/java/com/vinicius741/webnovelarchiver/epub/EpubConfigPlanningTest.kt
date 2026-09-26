package com.vinicius741.webnovelarchiver.epub

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.EpubConfig
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.StoryBookmarkPlanning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubConfigPlanningTest {
    @Test
    fun bookmarkTurnsOnBothDefaultsUntilSettingsAreSaved() {
        val story = StoryBookmarkPlanning.withBookmark(story(), "c2", toggleExisting = false)

        val config = EpubConfigPlanning.resolve(story, maxChaptersPerEpub = 400)

        assertTrue(config.startAtBookmark)
        assertTrue(config.chaptersOnly)
        assertEquals(1, config.rangeStart)
        assertEquals(3, config.rangeEnd)
        assertEquals(400, config.maxChaptersPerEpub)
        assertEquals(listOf("c2", "c3"), EpubSelection.selectDownloadedChapters(story, config).map { it.chapter.id })
    }

    @Test
    fun noBookmarkLeavesBothDefaultsOff() {
        val config = EpubConfigPlanning.resolve(story(), maxChaptersPerEpub = 150)

        assertFalse(config.startAtBookmark)
        assertFalse(config.chaptersOnly)
    }

    @Test
    fun savedOptOutRemainsOffAfterBookmarkSelection() {
        val saved = EpubConfig(rangeStart = 1, rangeEnd = 3, startAtBookmark = false, chaptersOnly = false)
        val story = StoryBookmarkPlanning.withBookmark(story().apply { epubConfig = saved }, "c2", toggleExisting = false)

        assertEquals(saved, EpubConfigPlanning.resolve(story, maxChaptersPerEpub = 400))
    }

    @Test
    fun staleBookmarkDoesNotEnableDefaults() {
        val story = story().apply { lastReadChapterId = "deleted" }

        assertFalse(EpubConfigPlanning.hasBookmark(story))
        assertFalse(EpubConfigPlanning.resolve(story, maxChaptersPerEpub = 150).startAtBookmark)
    }

    private fun story(): Story =
        Story(
            id = "story",
            chapters =
                mutableListOf(
                    Chapter(id = "c1", downloaded = true),
                    Chapter(id = "c2", downloaded = true),
                    Chapter(id = "c3", downloaded = true),
                ),
        )
}
