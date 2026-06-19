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
    fun storyHeaderActionsShowsInProgressGroupWhileActive() {
        val counts = QueueStatusCounts(downloading = 1, pending = 2, completed = 3)
        assertEquals(
            listOf(QueueAction.PAUSE, QueueAction.CANCEL),
            DownloadManagerPlanning.storyHeaderActions(counts),
        )
    }

    @Test
    fun storyHeaderActionsShowsResumeWhenOnlyPaused() {
        val counts = QueueStatusCounts(paused = 2, completed = 1)
        assertEquals(
            listOf(QueueAction.RESUME, QueueAction.CANCEL),
            DownloadManagerPlanning.storyHeaderActions(counts),
        )
    }

    @Test
    fun storyHeaderActionsAppendsRetryWhenActiveAndFailed() {
        val counts = QueueStatusCounts(pending = 1, failed = 2)
        assertEquals(
            listOf(QueueAction.PAUSE, QueueAction.CANCEL, QueueAction.RETRY),
            DownloadManagerPlanning.storyHeaderActions(counts),
        )
    }

    @Test
    fun storyHeaderActionsRetryOnlyForFailedWithNoActiveWork() {
        val counts = QueueStatusCounts(failed = 2, completed = 1)
        assertEquals(listOf(QueueAction.RETRY), DownloadManagerPlanning.storyHeaderActions(counts))
    }

    @Test
    fun storyHeaderActionsEmptyForAllCompleted() {
        val counts = QueueStatusCounts(completed = 3)
        assertTrue(DownloadManagerPlanning.storyHeaderActions(counts).isEmpty())
    }

    @Test
    fun globalActionsSurfaceEachBucketConditionally() {
        val counts =
            QueueStatusCounts(
                downloading = 1,
                paused = 1,
                completed = 2,
                failed = 1,
            )
        assertEquals(
            listOf(
                GlobalQueueAction.RESUME_ALL,
                GlobalQueueAction.PAUSE_ALL,
                GlobalQueueAction.RETRY_ALL,
                GlobalQueueAction.CANCEL_ALL,
                GlobalQueueAction.CLEAR_DONE,
            ),
            DownloadManagerPlanning.globalActions(counts),
        )
    }

    @Test
    fun globalActionsHideCancelAllWhenQueueIsIdle() {
        val counts = QueueStatusCounts(completed = 2, failed = 1)
        assertEquals(
            listOf(GlobalQueueAction.RETRY_ALL, GlobalQueueAction.CLEAR_DONE),
            DownloadManagerPlanning.globalActions(counts),
        )
    }

    @Test
    fun globalActionsShowsClearDoneOnlyForAllCompleted() {
        assertEquals(
            listOf(GlobalQueueAction.CLEAR_DONE),
            DownloadManagerPlanning.globalActions(QueueStatusCounts(completed = 1)),
        )
    }

    @Test
    fun globalActionsEmptyForEmptyQueue() {
        assertTrue(DownloadManagerPlanning.globalActions(QueueStatusCounts()).isEmpty())
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

        val counts = QueueStatusCounts.from(jobs)

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
    fun storySubtitleLeadsWithChaptersAndAppendsNonZeroSegments() {
        val counts = QueueStatusCounts(downloading = 1, pending = 2, paused = 0, completed = 3, failed = 1)
        assertEquals(
            "3/7 chapters • 1 downloading • 2 queued • 1 failed",
            DownloadManagerPlanning.storySubtitle(counts),
        )
    }

    @Test
    fun storySubtitleHasNoStatusTailWhenAllCompleted() {
        val counts = QueueStatusCounts(completed = 3)
        assertEquals("3/3 chapters", DownloadManagerPlanning.storySubtitle(counts))
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
