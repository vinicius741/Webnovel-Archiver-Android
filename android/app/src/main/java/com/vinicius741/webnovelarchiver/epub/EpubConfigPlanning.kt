package com.vinicius741.webnovelarchiver.epub

import com.vinicius741.webnovelarchiver.domain.model.EpubConfig
import com.vinicius741.webnovelarchiver.domain.model.Story

object EpubConfigPlanning {
    fun hasBookmark(story: Story): Boolean = story.lastReadChapterId?.let { id -> story.chapters.any { it.id == id } } == true

    /** Saved settings are explicit choices; only stories without them receive bookmark defaults. */
    fun resolve(
        story: Story,
        maxChaptersPerEpub: Int,
    ): EpubConfig {
        story.epubConfig?.let { return it }
        val hasBookmark = hasBookmark(story)
        return EpubConfig(
            maxChaptersPerEpub = maxChaptersPerEpub,
            rangeStart = 1,
            rangeEnd = story.chapters.size,
            startAtBookmark = hasBookmark,
            chaptersOnly = hasBookmark,
        )
    }
}
