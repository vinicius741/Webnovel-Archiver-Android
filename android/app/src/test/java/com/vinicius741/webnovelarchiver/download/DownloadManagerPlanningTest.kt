package com.vinicius741.webnovelarchiver.download

import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadManagerPlanningTest {
    @Test
    fun chapterActionsPauseForActiveStatuses() {
        assertEquals(listOf(QueueAction.PAUSE, QueueAction.CANCEL), DownloadManagerPlanning.chapterActions("pending"))
        assertEquals(listOf(QueueAction.PAUSE), DownloadManagerPlanning.chapterActions("downloading"))
    }

    @Test
    fun chapterActionsResumeAndCancelForPaused() {
        assertEquals(listOf(QueueAction.RESUME, QueueAction.CANCEL), DownloadManagerPlanning.chapterActions("paused"))
    }

    @Test
    fun chapterActionsRetryAndRemoveForTerminalFailures() {
        assertEquals(listOf(QueueAction.RETRY, QueueAction.REMOVE), DownloadManagerPlanning.chapterActions("failed"))
        assertEquals(listOf(QueueAction.RETRY, QueueAction.REMOVE), DownloadManagerPlanning.chapterActions("cancelled"))
    }

    @Test
    fun chapterActionsRemoveOnlyForCompleted() {
        assertEquals(listOf(QueueAction.REMOVE), DownloadManagerPlanning.chapterActions("completed"))
    }

    @Test
    fun chapterActionsEmptyForUnknownStatus() {
        assertTrue(DownloadManagerPlanning.chapterActions("idle").isEmpty())
    }

    @Test
    fun storyPrimaryActionFollowsTheSection() {
        val downloading = DownloadCounts(downloading = 1)
        assertEquals(QueueAction.PAUSE, DownloadManagerPlanning.storyPrimaryAction(QueueSection.DOWNLOADING, downloading))
        assertEquals(QueueAction.PAUSE, DownloadManagerPlanning.storyPrimaryAction(QueueSection.UP_NEXT, DownloadCounts(pending = 1)))
        assertEquals(QueueAction.RESUME, DownloadManagerPlanning.storyPrimaryAction(QueueSection.PAUSED, DownloadCounts(paused = 1)))
        assertEquals(QueueAction.RETRY, DownloadManagerPlanning.storyPrimaryAction(QueueSection.FINISHED, DownloadCounts(failed = 1)))
        assertEquals(QueueAction.REMOVE, DownloadManagerPlanning.storyPrimaryAction(QueueSection.FINISHED, DownloadCounts(completed = 2)))
    }

    @Test
    fun appBarTogglesPauseWhileAnyWorkIsActive() {
        val plan = DownloadManagerPlanning.appBar(DownloadCounts(downloading = 1, paused = 1, failed = 1), retryableFailed = 1)

        assertEquals(GlobalQueueAction.PAUSE_ALL, plan.toggle)
        assertEquals(
            listOf(GlobalQueueAction.RETRY_ALL, GlobalQueueAction.CANCEL_ALL, GlobalQueueAction.CLEAR_FINISHED),
            plan.overflow,
        )
    }

    @Test
    fun appBarTogglesResumeWhenQueueIsOnlyPaused() {
        val plan = DownloadManagerPlanning.appBar(DownloadCounts(paused = 2), retryableFailed = 0)

        assertEquals(GlobalQueueAction.RESUME_ALL, plan.toggle)
        assertEquals(listOf(GlobalQueueAction.CANCEL_ALL), plan.overflow)
    }

    @Test
    fun appBarHidesRetryWhenOnlyBlockedChaptersFailed() {
        val plan = DownloadManagerPlanning.appBar(DownloadCounts(failed = 3), retryableFailed = 0)

        assertEquals(null, plan.toggle)
        assertEquals(listOf(GlobalQueueAction.CLEAR_FINISHED), plan.overflow)
    }

    @Test
    fun appBarIsEmptyForEmptyQueue() {
        val plan = DownloadManagerPlanning.appBar(DownloadCounts(), retryableFailed = 0)

        assertEquals(null, plan.toggle)
        assertTrue(plan.overflow.isEmpty())
    }

    @Test
    fun statusCountsFromJobsTalliesEachBucket() {
        val jobs =
            listOf(
                job("a", "downloading"),
                job("b", "pending"),
                job("c", "pending"),
                job("d", "paused"),
                job("e", "completed"),
                job("f", "completed"),
                job("g", "failed"),
                job("h", "cancelled"),
            )

        val counts = DownloadCounts.from(jobs)

        assertEquals(1, counts.downloading)
        assertEquals(2, counts.pending)
        assertEquals(1, counts.paused)
        assertEquals(2, counts.completed)
        assertEquals(1, counts.failed)
        assertEquals(1, counts.cancelled)
        assertEquals(8, counts.total)
        assertTrue(counts.hasActive)
        assertTrue(counts.hasPaused)
        assertTrue(counts.hasFailed)
        assertTrue(counts.hasCancelled)
    }

    @Test
    fun downloadCountsIsTheSharedProjectionForLegacyStatuses() {
        val counts =
            listOf(
                job("active", "downloading"),
                job("unknown", "legacy-status"),
            ).downloadCounts()

        assertEquals(1, counts.downloading)
        assertEquals(1, counts.failed)
        assertEquals(2, counts.total)
    }

    private fun job(
        id: String,
        status: String,
    ): DownloadJob =
        DownloadJob(
            id = id,
            storyId = "story-1",
            storyTitle = "Story",
            status = status,
        )
}
