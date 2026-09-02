package com.vinicius741.webnovelarchiver.domain.story

import com.vinicius741.webnovelarchiver.domain.model.PublicationStatus
import com.vinicius741.webnovelarchiver.domain.model.Story

enum class FollowBlockReason {
    NO_BOOKMARK,
    COMPLETED,
    TOO_FAR,
}

data class FollowReviewEntry(
    val story: Story,
    val chaptersBehindEnd: Int?,
    val isFollowed: Boolean,
    val blockReason: FollowBlockReason?,
)

/**
 * Derives the followed-novel set from reading position: a novel is followed while its bookmark
 * sits within `thresholdChapters` of the local chapter-list end. Nothing is persisted except the
 * threshold itself — sync grows the end, reading advances the bookmark, so the set self-regulates
 * on every recompute.
 */
object FollowedNovelPlanning {
    fun chaptersBehindEnd(story: Story): Int? {
        val bookmarkIndex = story.chapters.indexOfFirst { it.id == story.lastReadChapterId }
        if (bookmarkIndex < 0) return null
        return story.chapters.lastIndex - bookmarkIndex
    }

    fun isFollowed(
        story: Story,
        thresholdChapters: Int,
    ): Boolean {
        if (!StoryActionGuards.canModifyStory(story)) return false
        if (story.publicationStatus == PublicationStatus.completed) return false
        val behind = chaptersBehindEnd(story) ?: return false
        return behind <= thresholdChapters
    }

    fun followedStories(
        stories: List<Story>,
        thresholdChapters: Int,
    ): List<Story> = stories.filter { isFollowed(it, thresholdChapters) }

    fun reviewEntry(
        story: Story,
        thresholdChapters: Int,
    ): FollowReviewEntry {
        val followed = isFollowed(story, thresholdChapters)
        return FollowReviewEntry(
            story = story,
            chaptersBehindEnd = chaptersBehindEnd(story),
            isFollowed = followed,
            blockReason = blockReason(story, followed),
        )
    }

    // Followed first, then by distance asc; no-bookmark/completed land last. Title breaks ties so
    // the list is stable across renders.
    fun reviewEntries(
        stories: List<Story>,
        thresholdChapters: Int,
    ): List<FollowReviewEntry> =
        stories
            .map { reviewEntry(it, thresholdChapters) }
            .sortedWith(
                compareByDescending<FollowReviewEntry> { it.isFollowed }
                    .thenBy { it.chaptersBehindEnd ?: Int.MAX_VALUE }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.story.title },
            )

    private fun blockReason(
        story: Story,
        followed: Boolean,
    ): FollowBlockReason? =
        when {
            followed -> null
            story.publicationStatus == PublicationStatus.completed -> FollowBlockReason.COMPLETED
            chaptersBehindEnd(story) == null -> FollowBlockReason.NO_BOOKMARK
            else -> FollowBlockReason.TOO_FAR
        }
}
