package com.vinicius741.webnovelarchiver.feature.updates

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.StoryActionGuards

data class UpdatedChapter(
    val index: Int,
    val chapter: Chapter,
)

object UpdateTrackerPlanning {
    /** Stories that can participate in update following (archives are read-only snapshots). */
    fun followableStories(stories: List<Story>): List<Story> = stories.filter { StoryActionGuards.canSync(it) }

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

    fun normalizeFollowedIds(
        stories: List<Story>,
        followedIds: List<String>,
    ): List<String> {
        // Drop missing ids and archived snapshots — following an archive cannot sync.
        val followableIds = followableStories(stories).map { it.id }.toSet()
        return followedIds.filter { it in followableIds }.distinct()
    }

    fun followedStories(
        stories: List<Story>,
        followedIds: List<String>,
    ): List<Story> {
        val byId = stories.associateBy { it.id }
        return normalizeFollowedIds(stories, followedIds).mapNotNull { byId[it] }
    }

    fun syncableFollowedStories(
        stories: List<Story>,
        followedIds: List<String>,
    ): List<Story> = followedStories(stories, followedIds).filter(StoryActionGuards::canSync)

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
}
