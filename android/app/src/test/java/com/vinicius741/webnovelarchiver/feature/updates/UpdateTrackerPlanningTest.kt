package com.vinicius741.webnovelarchiver.feature.updates

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateTrackerPlanningTest {
    @Test
    fun normalizeFollowedIdsDropsMissingAndDuplicates() {
        val stories = listOf(story("a"), story("b"))

        val normalized = UpdateTrackerPlanning.normalizeFollowedIds(stories, listOf("missing", "b", "a", "b"))

        assertEquals(listOf("b", "a"), normalized)
    }

    @Test
    fun followedStoriesKeepFollowedOrder() {
        val stories = listOf(story("a", title = "A"), story("b", title = "B"))

        val followed = UpdateTrackerPlanning.followedStories(stories, listOf("b", "a"))

        assertEquals(listOf("B", "A"), followed.map { it.title })
    }

    @Test
    fun updatedChaptersReturnPendingChaptersInStoryOrder() {
        val story =
            story(
                "story",
                chapters =
                    mutableListOf(
                        Chapter(id = "c1", title = "One"),
                        Chapter(id = "c2", title = "Two"),
                        Chapter(id = "c3", title = "Three"),
                    ),
                pending = mutableListOf("c3", "missing", "c1"),
            )

        val updates = UpdateTrackerPlanning.updatedChapters(story)

        assertEquals(listOf(0, 2), updates.map { it.index })
        assertEquals(listOf("One", "Three"), updates.map { it.chapter.title })
    }

    private fun story(
        id: String,
        title: String = id,
        chapters: MutableList<Chapter> = mutableListOf(),
        pending: MutableList<String>? = null,
    ): Story =
        Story(
            id = id,
            title = title,
            sourceUrl = "https://example.com/$id",
            chapters = chapters,
            pendingNewChapterIds = pending,
        )
}
