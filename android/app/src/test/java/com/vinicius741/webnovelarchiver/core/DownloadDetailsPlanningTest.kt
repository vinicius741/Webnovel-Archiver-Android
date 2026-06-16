package com.vinicius741.webnovelarchiver.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadDetailsPlanningTest {

    @Test
    fun summarizeActiveWhenDownloadingOrPending() {
        val jobs = listOf(
            job("c1", "downloading", title = "Chapter 1"),
            job("c2", "pending"),
            job("c3", "completed"),
        )
        val summary = DownloadDetailsPlanning.summarizeStoryDownload(jobs)
        assertTrue(summary.isActive)
        assertFalse(summary.isPaused)
        assertFalse(summary.isFinished)
        assertEquals(1, summary.downloading)
        assertEquals(1, summary.pending)
        assertEquals(1, summary.completed)
        assertEquals(3, summary.total)
        assertEquals("Chapter 1", summary.activeTitle)
        // progress is fraction completed
        assertEquals(1f / 3f, summary.progress, 1e-5f)
    }

    @Test
    fun summarizeActiveUsesQueuedHeadlineWhenNoneDownloadingYet() {
        val summary = DownloadDetailsPlanning.summarizeStoryDownload(
            listOf(job("c1", "pending"), job("c2", "completed")),
        )
        assertTrue(summary.isActive)
        assertNull(summary.activeTitle)
        assertEquals("Queued (1/2)", DownloadDetailsPlanning.headline(summary))
    }

    @Test
    fun headlineDownloadingIncludesActiveTitle() {
        val jobs = listOf(
            job("c1", "downloading", title = "Chapter 1"),
            job("c2", "pending"),
            job("c3", "completed"),
        )
        val summary = DownloadDetailsPlanning.summarizeStoryDownload(jobs)
        assertEquals("Downloading: Chapter 1 (1/3)", DownloadDetailsPlanning.headline(summary))
    }

    @Test
    fun summarizePausedWhenNoActiveButPausedPresent() {
        val jobs = listOf(
            job("c1", "paused"),
            job("c2", "completed"),
        )
        val summary = DownloadDetailsPlanning.summarizeStoryDownload(jobs)
        assertFalse(summary.isActive)
        assertTrue(summary.isPaused)
        assertFalse(summary.isFinished)
        assertEquals("Paused (1/2)", DownloadDetailsPlanning.headline(summary))
    }

    @Test
    fun headlineCompleteWhenAllCompleted() {
        val jobs = listOf(
            job("c1", "completed"),
            job("c2", "completed"),
        )
        val summary = DownloadDetailsPlanning.summarizeStoryDownload(jobs)
        assertTrue(summary.isFinished)
        assertEquals("Download Complete", DownloadDetailsPlanning.headline(summary))
        assertEquals(1f, summary.progress, 1e-5f)
    }

    @Test
    fun headlineFinishedWithFailuresAndCancelled() {
        val jobs = listOf(
            job("c1", "completed"),
            job("c2", "failed"),
            job("c3", "cancelled"),
        )
        val summary = DownloadDetailsPlanning.summarizeStoryDownload(jobs)
        assertTrue(summary.isFinished)
        assertEquals(
            "Finished (1/3 downloaded, 1 failed, 1 cancelled)",
            DownloadDetailsPlanning.headline(summary),
        )
    }

    @Test
    fun headlineEmptyForNoJobs() {
        val summary = DownloadDetailsPlanning.summarizeStoryDownload(emptyList())
        assertEquals(0, summary.total)
        assertFalse(summary.isActive)
        assertFalse(summary.isPaused)
        assertFalse(summary.isFinished)
        assertEquals("", DownloadDetailsPlanning.headline(summary))
    }

    @Test
    fun headlineTrimsLongChapterTitle() {
        val longTitle = "A".repeat(120)
        val summary = DownloadDetailsPlanning.summarizeStoryDownload(
            listOf(job("c1", "downloading", title = longTitle), job("c2", "completed")),
        )
        val headline = DownloadDetailsPlanning.headline(summary)
        // truncated to 45 chars + ellipsis, ratio appended
        assertTrue(headline.startsWith("Downloading: " + "A".repeat(45) + "…"))
        assertTrue(headline.endsWith("(1/2)"))
    }

    @Test
    fun chapterJobStatusesExcludesCompleted() {
        val jobs = listOf(
            job("c1", "downloading"),
            job("c2", "pending"),
            job("c3", "completed"),
            job("c4", "failed"),
        )
        val statuses = DownloadDetailsPlanning.chapterJobStatuses(jobs)
        // completed is filtered out (story downloaded flag already marks it)
        assertEquals(3, statuses.size)
        assertEquals(DownloadJobStatus.Downloading, statuses["c1"])
        assertEquals(DownloadJobStatus.Pending, statuses["c2"])
        assertNull(statuses["c3"])
        assertEquals(DownloadJobStatus.Failed, statuses["c4"])
    }

    @Test
    fun chapterJobStatusesEmptyForNoJobs() {
        assertTrue(DownloadDetailsPlanning.chapterJobStatuses(emptyList()).isEmpty())
    }

    private fun job(chapterId: String, status: String, title: String = "Chapter"): DownloadJob = DownloadJob(
        id = "$chapterId-job",
        storyId = "story-1",
        storyTitle = "Story",
        chapterIndex = 0,
        chapter = Chapter(id = chapterId, title = title, url = "https://example.com/$chapterId"),
        status = status,
    )
}
