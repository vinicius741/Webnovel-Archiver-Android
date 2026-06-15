package com.vinicius741.webnovelarchiver.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueueMaintenanceTest {
    @Test
    fun failUnsupportedSourceJobsMarksOnlyActiveQueueItemsFailed() {
        val jobs = mutableListOf(
            job("supported-pending", "story-1", "https://www.royalroad.com/fiction/1/story", "pending"),
            job("unsupported-pending", "story-2", "https://example.com/chapter", "pending"),
            job("unsupported-downloading", "story-2", "https://example.com/active", "downloading"),
            job("unsupported-paused", "story-3", "https://example.com/paused", "paused"),
        )

        val result = DownloadQueueMaintenance.failUnsupportedSourceJobs(jobs) { job ->
            SourceRegistry.getProvider(job.chapter.url)?.name
        }

        assertEquals(2, result.cleanedJobCount)
        assertEquals(listOf("story-2"), result.affectedStoryIds)
        assertEquals("pending", jobs[0].status)
        assertEquals("failed", jobs[1].status)
        assertEquals(DownloadQueueMaintenance.NO_PROVIDER_MESSAGE, jobs[1].error)
        assertEquals(DownloadQueueMaintenance.NO_PROVIDER_CATEGORY, jobs[1].errorCategory)
        assertEquals(DownloadQueueMaintenance.NO_PROVIDER_CODE, jobs[1].errorCode)
        assertEquals("failed", jobs[2].status)
        assertEquals("paused", jobs[3].status)
    }

    @Test
    fun recoverStuckDownloadingStoryMovesToPartialOrIdleWhenNoActiveJobsRemain() {
        val storyWithDownload = Story(
            id = "s1",
            status = DownloadStatus.downloading,
            chapters = mutableListOf(
                Chapter(id = "c1", downloaded = true),
                Chapter(id = "c2", downloaded = false),
            ),
        )
        val recoveredPartial = DownloadQueueMaintenance.recoverStuckDownloadingStory(
            storyWithDownload,
            listOf(job("j1", "s1", "https://example.com/failed", "failed")),
        )

        assertTrue(recoveredPartial)
        assertEquals(DownloadStatus.partial, storyWithDownload.status)
        assertEquals(1, storyWithDownload.downloadedChapters)

        val storyWithoutDownloads = Story(
            id = "s2",
            status = DownloadStatus.downloading,
            chapters = mutableListOf(Chapter(id = "c1", downloaded = false)),
        )
        val recoveredIdle = DownloadQueueMaintenance.recoverStuckDownloadingStory(storyWithoutDownloads, emptyList())

        assertTrue(recoveredIdle)
        assertEquals(DownloadStatus.idle, storyWithoutDownloads.status)
        assertEquals(0, storyWithoutDownloads.downloadedChapters)
    }

    @Test
    fun recoverStuckDownloadingStoryKeepsActiveStoriesDownloading() {
        val story = Story(
            id = "s1",
            status = DownloadStatus.downloading,
            chapters = mutableListOf(Chapter(id = "c1", downloaded = true)),
        )

        val changed = DownloadQueueMaintenance.recoverStuckDownloadingStory(
            story,
            listOf(job("j1", "s1", "https://www.royalroad.com/fiction/1/story", "pending")),
        )

        assertFalse(changed)
        assertEquals(DownloadStatus.downloading, story.status)
    }

    private fun job(id: String, storyId: String, url: String, status: String): DownloadJob =
        DownloadJob(
            id = id,
            storyId = storyId,
            storyTitle = storyId,
            chapterIndex = 0,
            chapter = Chapter(id = id, title = id, url = url),
            status = status,
        )
}
