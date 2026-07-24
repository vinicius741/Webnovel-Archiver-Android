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

    /**
     * Resolves the bookmark to a 0..1 fraction of the chapter list (`null` when there is no
     * bookmark or the referenced chapter can't be found). Used by the chapter-coverage bar to
     * place its pin marker at the bookmarked chapter's position.
     */
    fun bookmarkFraction(story: Story): Float? {
        val id = story.lastReadChapterId ?: return null
        if (story.chapters.isEmpty()) return null
        val index = story.chapters.indexOfFirst { it.id == id }
        if (index < 0) return null
        return index.toFloat() / story.chapters.size
    }

    /** Per-chapter `downloaded` flags, in chapter order — the positional truth the coverage bar
     *  fills from, so the last 7 of 100 read at the right end instead of the left. */
    fun downloadedFlags(story: Story): BooleanArray = BooleanArray(story.chapters.size) { story.chapters[it].downloaded }
}
