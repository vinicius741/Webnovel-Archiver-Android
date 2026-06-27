package com.vinicius741.webnovelarchiver.domain.story

import com.vinicius741.webnovelarchiver.domain.model.EpubConfig
import com.vinicius741.webnovelarchiver.domain.model.Story

object StoryBookmarkPlanning {
    fun withBookmark(
        story: Story,
        chapterId: String,
        toggleExisting: Boolean,
    ): Story {
        val nextLastReadChapterId = if (toggleExisting && story.lastReadChapterId == chapterId) null else chapterId
        return story.copy(
            lastReadChapterId = nextLastReadChapterId,
            epubConfig = updatedEpubConfig(story, nextLastReadChapterId),
        )
    }

    private fun updatedEpubConfig(
        story: Story,
        nextLastReadChapterId: String?,
    ): EpubConfig? {
        val config = story.epubConfig ?: return story.epubConfig
        if (!config.startAtBookmark || nextLastReadChapterId == null) return config

        val bookmarkIndex = story.chapters.indexOfFirst { it.id == nextLastReadChapterId }
        if (bookmarkIndex < 0) return config

        // Anchor the range start AT the bookmarked chapter (1-based), so it is included rather than skipped.
        return config.copy(rangeStart = bookmarkIndex + 1)
    }
}
