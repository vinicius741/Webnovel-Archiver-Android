package com.vinicius741.webnovelarchiver.feature.details

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story

/**
 * The Chapters tab's search and filter chips, shared by the first render, filter and search
 * changes, and the in-place download refresh so they all apply the identical view.
 */
internal object ChapterFilterPlanning {
    /**
     * The chapters [filter] and [query] keep, with their 0-based reading position. The query matches
     * a title (ignoring case) or the exact chapter number. A filter that does not apply to this novel
     * (no bookmark, nothing removed) shows every chapter.
     */
    fun filter(
        story: Story,
        query: String,
        filter: String,
    ): List<Pair<Int, Chapter>> {
        val bookmarkIndex = bookmarkIndex(story)
        val hasRemoved = story.chapters.any { it.isRemovedFromSource }
        val trimmed = query.trim()
        return story.chapters
            .mapIndexed { index, chapter -> index to chapter }
            .filter { (index, chapter) ->
                trimmed.isEmpty() || chapter.title.contains(trimmed, ignoreCase = true) || (index + 1).toString() == trimmed
            }.filter { (index, chapter) ->
                when (filter) {
                    DOWNLOADED_FILTER -> chapter.downloaded
                    FROM_BOOKMARK_FILTER -> bookmarkIndex < 0 || index >= bookmarkIndex
                    NOT_DOWNLOADED_FILTER -> !chapter.downloaded
                    REMOVED_FROM_SOURCE_FILTER -> chapter.isRemovedFromSource || !hasRemoved
                    else -> true
                }
            }
    }

    /** Chapters from the bookmark onward (inclusive); 0 without a valid bookmark. */
    fun fromBookmarkCount(story: Story): Int {
        val index = bookmarkIndex(story)
        return if (index < 0) 0 else story.chapters.size - index
    }

    private fun bookmarkIndex(story: Story): Int = story.lastReadChapterId?.let { id -> story.chapters.indexOfFirst { it.id == id } } ?: -1
}

internal const val ALL_FILTER = "all"
internal const val DOWNLOADED_FILTER = "hideNonDownloaded"
internal const val FROM_BOOKMARK_FILTER = "hideAboveBookmark"
internal const val NOT_DOWNLOADED_FILTER = "notDownloaded"
internal const val REMOVED_FROM_SOURCE_FILTER = "removedFromSource"
