package com.vinicius741.webnovelarchiver.core

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

    private fun story(vararg chapters: Chapter): Story =
        Story(
            id = "story-1",
            title = "Native Story",
            sourceUrl = "https://example.com/story",
            chapters = chapters.toMutableList(),
            totalChapters = chapters.size,
        )
}
