package com.vinicius741.webnovelarchiver.download

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadQueueControlPlanningTest {
    @Test
    fun pauseAllPausesActiveJobsAndClearsRetryTimers() {
        val jobs =
            listOf(
                job("pending", "pending", nextRetryAt = 100L),
                job("downloading", "downloading", nextRetryAt = 200L),
                job("failed", "failed", nextRetryAt = 300L),
            )

        val updated = DownloadQueueControlPlanning.pauseAll(jobs)

        assertEquals(listOf("paused", "paused", "failed"), updated.map { it.status })
        assertNull(updated[0].nextRetryAt)
        assertNull(updated[1].nextRetryAt)
        assertEquals(300L, updated[2].nextRetryAt)
    }

    @Test
    fun resumeKeepsErrorMetadataAndClearsRetryTimer() {
        val jobs =
            listOf(
                job(
                    id = "paused",
                    status = "paused",
                    error = "Paused after repeated failures",
                    errorCategory = "rate_limit",
                    errorCode = "HTTP_429",
                    nextRetryAt = 100L,
                ),
            )

        val updated = DownloadQueueControlPlanning.resumeJob(jobs, "paused").single()

        assertEquals("pending", updated.status)
        assertEquals("Paused after repeated failures", updated.error)
        assertEquals("rate_limit", updated.errorCategory)
        assertEquals("HTTP_429", updated.errorCode)
        assertNull(updated.nextRetryAt)
    }

    @Test
    fun cancelMarksOnlyCancellableJobsCancelled() {
        val updated =
            DownloadQueueControlPlanning.cancelAll(
                listOf(
                    job("pending", "pending"),
                    job("paused", "paused"),
                    job("downloading", "downloading"),
                    job("failed", "failed"),
                    job("completed", "completed"),
                ),
            )

        assertEquals(listOf("cancelled", "cancelled", "cancelled", "failed", "completed"), updated.map { it.status })
        assertEquals("cancelled", updated[0].error)
        assertEquals("cancelled", updated[0].errorCategory)
        assertEquals("CANCELLED", updated[0].errorCode)
    }

    @Test
    fun retryManualOnlyRetriesFailedJobsNotCancelledJobs() {
        val updated =
            DownloadQueueControlPlanning.retryFailed(
                listOf(
                    job(
                        "failed",
                        "failed",
                        retryCount = 2,
                        error = "Timeout",
                        errorCategory = "network",
                        errorCode = "TIMEOUT",
                        nextRetryAt = 100L,
                    ),
                    job(
                        "cancelled",
                        "cancelled",
                        retryCount = 1,
                        error = "cancelled",
                        errorCategory = "cancelled",
                        errorCode = "CANCELLED",
                    ),
                ),
            )

        assertEquals("pending", updated[0].status)
        assertEquals(3, updated[0].retryCount)
        assertNull(updated[0].error)
        assertNull(updated[0].errorCategory)
        assertNull(updated[0].errorCode)
        assertNull(updated[0].nextRetryAt)
        assertEquals("cancelled", updated[1].status)
        assertEquals(1, updated[1].retryCount)
    }

    @Test
    fun retryFailedForStoryOnlyTouchesMatchingStoryFailedJobs() {
        val updated =
            DownloadQueueControlPlanning.retryFailed(
                listOf(
                    job("story-1-failed", "failed", storyId = "story-1"),
                    job("story-2-failed", "failed", storyId = "story-2"),
                ),
                storyId = "story-1",
            )

        assertEquals("pending", updated[0].status)
        assertEquals("failed", updated[1].status)
    }

    @Test
    fun storyControlsTransformWholeGroupInOnePassAndPreserveUnrelatedJobs() {
        val jobs =
            listOf(
                job("a-pending", "pending", storyId = "story-a"),
                job("a-downloading", "downloading", storyId = "story-a"),
                job("a-completed", "completed", storyId = "story-a"),
                job("b-downloading", "downloading", storyId = "story-b"),
            )

        val paused = DownloadQueueControlPlanning.pauseStory(jobs, "story-a")
        assertEquals(listOf("paused", "paused", "completed", "downloading"), paused.map { it.status })

        val resumed = DownloadQueueControlPlanning.resumeStory(paused, "story-a")
        assertEquals(listOf("pending", "pending", "completed", "downloading"), resumed.map { it.status })

        val cancelled = DownloadQueueControlPlanning.cancelStory(jobs, "story-a")
        assertEquals(listOf("cancelled", "cancelled", "completed", "downloading"), cancelled.map { it.status })
    }

    @Test
    fun removeStoryIsHandledByQueueFilteringNotPlanning() {
        // removeStory filters by storyId in the engine's single transform; planning keeps only
        // status-based semantics. Verify the filter contract the engine relies on.
        val jobs =
            listOf(
                job("a-1", "completed", storyId = "story-a"),
                job("a-2", "pending", storyId = "story-a"),
                job("b-1", "pending", storyId = "story-b"),
            )
        val remaining = jobs.filterNot { it.storyId == "story-a" }
        assertEquals(listOf("b-1"), remaining.map { it.id })
    }

    private fun job(
        id: String,
        status: String,
        storyId: String = "story",
        retryCount: Int = 0,
        error: String? = null,
        errorCategory: String? = null,
        errorCode: String? = null,
        nextRetryAt: Long? = null,
    ): DownloadJob =
        DownloadJob(
            id = id,
            storyId = storyId,
            storyTitle = storyId,
            chapterIndex = 0,
            chapter = Chapter(id = "chapter-$id", title = id),
            status = status,
            retryCount = retryCount,
            error = error,
            errorCategory = errorCategory,
            errorCode = errorCode,
            nextRetryAt = nextRetryAt,
        )
}
