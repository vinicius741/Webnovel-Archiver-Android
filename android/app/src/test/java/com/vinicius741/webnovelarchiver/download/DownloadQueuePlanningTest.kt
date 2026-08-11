package com.vinicius741.webnovelarchiver.download

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.ui.size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueuePlanningTest {
    @Test
    fun queueChaptersAddsOnlyValidUndownloadedChapters() {
        val story =
            story(
                Chapter(id = "c1", title = "One", url = "https://example.com/1"),
                Chapter(id = "c2", title = "Two", url = "https://example.com/2", downloaded = true),
                Chapter(id = "c3", title = "Three", url = "https://example.com/3"),
            )

        val plan = DownloadQueuePlanning.queueChapters(emptyList(), story, listOf(0, 1, 2, 9))

        assertTrue(plan.changed)
        assertTrue(plan.hasRunnableWork)
        assertEquals(listOf("story-1_0", "story-1_2"), plan.jobs.map { it.id })
        assertEquals(listOf("One", "Three"), plan.jobs.map { it.chapter.title })
        assertEquals(listOf("example_source", "example_source"), plan.jobs.map { it.sourceId })
    }

    @Test
    fun queueChaptersReplacesTerminalDuplicateWithPendingJob() {
        val story = story(Chapter(id = "c1", title = "Fresh Title", url = "https://example.com/fresh"))
        val existing =
            DownloadJob(
                id = "story-1_0",
                storyId = "story-1",
                storyTitle = "Old Story",
                chapterIndex = 0,
                chapter = Chapter(id = "old", title = "Old Title", url = "https://example.com/old"),
                status = "failed",
                retryCount = 2,
                error = "Timeout",
                errorCategory = "network",
                errorCode = "TIMEOUT",
                nextRetryAt = 123L,
            )

        val plan = DownloadQueuePlanning.queueChapters(listOf(existing), story, listOf(0))
        val replacement = plan.jobs.single()

        assertTrue(plan.changed)
        assertTrue(plan.hasRunnableWork)
        assertEquals("pending", replacement.status)
        assertEquals(2, replacement.retryCount)
        assertEquals("Native Story", replacement.storyTitle)
        assertEquals("Fresh Title", replacement.chapter.title)
        assertNull(replacement.error)
        assertNull(replacement.errorCategory)
        assertNull(replacement.errorCode)
        assertNull(replacement.nextRetryAt)
    }

    @Test
    fun queueChaptersKeepsActiveDuplicateWithoutAddingAnotherJob() {
        val story = story(Chapter(id = "c1", title = "One", url = "https://example.com/1"))
        val existing =
            DownloadJob(
                id = "story-1_0",
                storyId = "story-1",
                storyTitle = "Native Story",
                chapterIndex = 0,
                chapter = story.chapters[0],
                status = "pending",
            )

        val plan = DownloadQueuePlanning.queueChapters(listOf(existing), story, listOf(0))

        assertFalse(plan.changed)
        assertTrue(plan.hasRunnableWork)
        assertEquals(listOf(existing), plan.jobs)
    }

    @Test
    fun queueChaptersReportsNoWorkForDownloadedOrInvalidSelections() {
        val story = story(Chapter(id = "c1", title = "One", url = "https://example.com/1", downloaded = true))

        val plan = DownloadQueuePlanning.queueChapters(emptyList(), story, listOf(0, 3))

        assertFalse(plan.changed)
        assertFalse(plan.hasRunnableWork)
        assertTrue(plan.jobs.isEmpty())
    }

    @Test
    fun queueChaptersRetiresEarlierCompletedBatchWhenAddingNewWorkForStory() {
        val story =
            story(
                Chapter(id = "old-1", downloaded = true),
                Chapter(id = "old-2", downloaded = true),
                Chapter(id = "remaining-1"),
                Chapter(id = "remaining-2"),
            )
        val earlierCompletedBatch =
            listOf(
                job(storyId = story.id, chapterIndex = 0, chapter = story.chapters[0], status = "completed"),
                job(storyId = story.id, chapterIndex = 1, chapter = story.chapters[1], status = "completed"),
                job(storyId = "other-story", chapterIndex = 0, chapter = Chapter(id = "other"), status = "completed"),
            )

        val plan = DownloadQueuePlanning.queueChapters(earlierCompletedBatch, story, listOf(2, 3))

        assertTrue(plan.changed)
        assertTrue(plan.hasRunnableWork)
        assertEquals(
            listOf("other-story_0", "story-1_2", "story-1_3"),
            plan.jobs.map { it.id },
        )
        assertEquals(2, plan.jobs.count { it.storyId == story.id })
    }

    @Test
    fun queueChaptersNoOpPreservesCompletedHistory() {
        val story = story(Chapter(id = "downloaded", downloaded = true))
        val completed = job(story.id, 0, story.chapters[0], status = "completed")

        val plan = DownloadQueuePlanning.queueChapters(listOf(completed), story, listOf(0))

        assertFalse(plan.changed)
        assertFalse(plan.hasRunnableWork)
        assertEquals(listOf(completed), plan.jobs)
    }

    @Test
    fun queueChaptersPreservesNonCompletedRowsWhenRetiringHistory() {
        val story =
            story(
                Chapter(id = "downloaded", downloaded = true),
                Chapter(id = "new"),
                Chapter(id = "active"),
                Chapter(id = "failed"),
                Chapter(id = "cancelled"),
            )
        val existing =
            listOf(
                job(story.id, 0, story.chapters[0], status = "completed"),
                job(story.id, 2, story.chapters[2], status = "pending"),
                job(story.id, 3, story.chapters[3], status = "failed"),
                job(story.id, 4, story.chapters[4], status = "cancelled"),
            )

        val plan = DownloadQueuePlanning.queueChapters(existing, story, listOf(1))

        assertEquals(
            listOf("pending", "failed", "cancelled", "pending"),
            plan.jobs.map { it.status },
        )
        assertEquals(
            listOf("active", "failed", "cancelled", "new"),
            plan.jobs.map { it.chapter.id },
        )
    }

    private fun job(
        storyId: String,
        chapterIndex: Int,
        chapter: Chapter,
        status: String,
    ): DownloadJob =
        DownloadJob(
            id = "${storyId}_$chapterIndex",
            storyId = storyId,
            storyTitle = storyId,
            chapterIndex = chapterIndex,
            chapter = chapter,
            status = status,
        )

    private fun story(vararg chapters: Chapter): Story =
        Story(
            id = "story-1",
            title = "Native Story",
            sourceUrl = "https://example.com/story",
            sourceId = "example_source",
            chapters = chapters.toMutableList(),
            totalChapters = chapters.size,
        )
}
