package com.vinicius741.webnovelarchiver.core

object StoryBookmarkPlanning {
    fun withBookmark(story: Story, chapterId: String, toggleExisting: Boolean): Story {
        val nextLastReadChapterId = if (toggleExisting && story.lastReadChapterId == chapterId) null else chapterId
        return story.copy(
            lastReadChapterId = nextLastReadChapterId,
            epubConfig = updatedEpubConfig(story, nextLastReadChapterId),
        )
    }

    private fun updatedEpubConfig(story: Story, nextLastReadChapterId: String?): EpubConfig? {
        val config = story.epubConfig ?: return story.epubConfig
        if (!config.startAfterBookmark || nextLastReadChapterId == null) return config

        val bookmarkIndex = story.chapters.indexOfFirst { it.id == nextLastReadChapterId }
        if (bookmarkIndex < 0) return config

        return config.copy(rangeStart = bookmarkIndex + 2)
    }
}
