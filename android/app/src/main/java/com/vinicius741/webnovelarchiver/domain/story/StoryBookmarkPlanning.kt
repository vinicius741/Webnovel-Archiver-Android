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
            epubConfig = clearLegacyAnchoredStart(story),
        )
    }

    // Older versions rewrote rangeStart to the bookmark's chapter number on every bookmark move, so a
    // saved start equal to the previous bookmark position is machine-derived; clear it so the bookmark
    // alone drives the effective range and backward bookmark moves keep tracking.
    private fun clearLegacyAnchoredStart(story: Story): EpubConfig? {
        val config = story.epubConfig ?: return null
        if (!config.startAtBookmark) return config
        val previousBookmarkNumber = story.chapters.indexOfFirst { it.id == story.lastReadChapterId } + 1
        if (previousBookmarkNumber <= 0 || config.rangeStart != previousBookmarkNumber) return config
        return config.copy(rangeStart = 1)
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
