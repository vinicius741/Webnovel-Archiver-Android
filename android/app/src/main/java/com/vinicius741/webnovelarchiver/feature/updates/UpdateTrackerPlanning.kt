package com.vinicius741.webnovelarchiver.feature.updates

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.FollowBlockReason
import com.vinicius741.webnovelarchiver.domain.story.FollowReviewEntry
import com.vinicius741.webnovelarchiver.domain.story.FollowedNovelPlanning
import com.vinicius741.webnovelarchiver.domain.story.StoryActionGuards

data class UpdatedChapter(
    val index: Int,
    val chapter: Chapter,
)

object UpdateTrackerPlanning {
    fun unavailableSummary(count: Int): String =
        "$count unavailable novel${if (count == 1) "" else "s"} will be skipped. Check manually from its details screen."

    /** Stories that can participate in update following (archives are read-only snapshots). */
    fun followableStories(stories: List<Story>): List<Story> = stories.filter { StoryActionGuards.canModifyStory(it) }

    fun filterStories(
        stories: List<Story>,
        query: String,
    ): List<Story> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return stories
        return stories.filter { story ->
            story.title.contains(trimmed, ignoreCase = true) ||
                story.author.contains(trimmed, ignoreCase = true)
        }
    }

    fun syncableFollowedStories(
        stories: List<Story>,
        thresholdChapters: Int,
    ): List<Story> = FollowedNovelPlanning.followedStories(stories, thresholdChapters).filter(StoryActionGuards::canAutoSync)

    fun syncBatches(
        stories: List<Story>,
        maxConcurrent: Int,
    ): List<List<Story>> = stories.chunked(maxConcurrent.coerceAtLeast(1))

    fun updatedChapters(
        story: Story,
        chapterIds: List<String>? = null,
    ): List<UpdatedChapter> {
        val pending = (chapterIds ?: story.pendingNewChapterIds).orEmpty().toSet()
        if (pending.isEmpty()) return emptyList()
        return story.chapters.mapIndexedNotNull { index, chapter ->
            if (chapter.id in pending) UpdatedChapter(index, chapter) else null
        }
    }

    fun updatedStoryCount(
        stories: List<Story>,
        chapterIdsByStoryId: Map<String, List<String>> = emptyMap(),
    ): Int = stories.count { updatedChapters(it, chapterIdsByStoryId[it.id]).isNotEmpty() }

    fun updatedChapterCount(
        stories: List<Story>,
        chapterIdsByStoryId: Map<String, List<String>> = emptyMap(),
    ): Int = stories.sumOf { updatedChapters(it, chapterIdsByStoryId[it.id]).size }

    fun reviewHeaderLabel(
        followedCount: Int,
        totalCount: Int,
    ): String = "$followedCount of $totalCount following"

    fun reviewNovelsLabel(
        visibleCount: Int,
        totalCount: Int,
    ): String = if (visibleCount == totalCount) "Novels ($visibleCount)" else "Novels ($visibleCount of $totalCount)"

    fun reviewStatusBadgeLabel(entry: FollowReviewEntry): String = if (entry.isFollowed) "Following" else "Not following"

    fun reviewDistanceLabel(entry: FollowReviewEntry): String =
        when (entry.blockReason) {
            null -> chaptersBehindLabel(entry.chaptersBehindEnd ?: 0)
            FollowBlockReason.COMPLETED -> "Completed"
            FollowBlockReason.NO_BOOKMARK -> "No bookmark"
            FollowBlockReason.TOO_FAR -> chaptersBehindLabel(entry.chaptersBehindEnd ?: 0)
        }

    private fun chaptersBehindLabel(behind: Int): String = "$behind chapter${plural(behind)} behind the end"

    fun reviewEmptyCopy(): Pair<String, String> = "No matches" to "Try a different search."

    fun thresholdLabel(threshold: Int): String = "Within $threshold chapter${plural(threshold)} of the end"
}
