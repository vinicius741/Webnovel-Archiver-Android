package com.vinicius741.webnovelarchiver.download

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadSourceFailurePlanningTest {
    @Test
    fun challengeBlocksEveryActiveJobForOnlyThatSource() {
        val jobs =
            listOf(
                job("a", "Scribble Hub", "pending"),
                job("b", "Scribble Hub", "downloading"),
                job("c", "RoyalRoad", "pending"),
            )

        DownloadSourceFailurePlanning.blockActiveJobs(jobs, "Scribble Hub", "Verify") { it.chapter.url }

        assertEquals(listOf("failed", "failed", "pending"), jobs.map { it.status })
        assertEquals(listOf("source_blocked", "source_blocked", null), jobs.map { it.errorCategory })
    }

    @Test
    fun rateLimitDefersAllPendingJobsWithoutShorteningExistingDelay() {
        val first = job("a", "Scribble Hub", "pending").apply { nextRetryAt = 5_000L }
        val second = job("b", "Scribble Hub", "pending")
        val other = job("c", "RoyalRoad", "pending")

        DownloadSourceFailurePlanning.deferPendingJobs(listOf(first, second, other), "Scribble Hub", 4_000L) { it.chapter.url }

        assertEquals(5_000L, first.nextRetryAt)
        assertEquals(4_000L, second.nextRetryAt)
        assertNull(other.nextRetryAt)
    }

    private fun job(
        id: String,
        provider: String,
        status: String,
    ) = DownloadJob(id = id, status = status, chapter = Chapter(url = provider))
}
