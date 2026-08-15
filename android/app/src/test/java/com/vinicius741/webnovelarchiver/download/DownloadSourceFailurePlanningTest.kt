package com.vinicius741.webnovelarchiver.download

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadSourceFailurePlanningTest {
    @Test
    fun challengeFailsActiveJobsAndDefersPendingJobsForOnlyThatSource() {
        val jobs =
            listOf(
                job("a", "Scribble Hub", "pending"),
                job("b", "Scribble Hub", "downloading"),
                job("c", "RoyalRoad", "pending"),
            )

        DownloadSourceFailurePlanning.blockSource(jobs, "Scribble Hub", "Verify", 9_000L) { it.chapter.url }

        assertEquals(listOf("pending", "failed", "pending"), jobs.map { it.status })
        assertEquals(listOf(null, "source_blocked", null), jobs.map { it.errorCategory })
        assertEquals(9_000L, jobs[0].nextRetryAt)
        assertNull(jobs[1].nextRetryAt)
        assertNull(jobs[2].nextRetryAt)
    }

    @Test
    fun blockSourceNeverShortensAnExistingDeferral() {
        val job = job("a", "Scribble Hub", "pending").apply { nextRetryAt = 120_000L }

        DownloadSourceFailurePlanning.blockSource(listOf(job), "Scribble Hub", "Verify", 9_000L) { it.chapter.url }

        assertEquals(120_000L, job.nextRetryAt)
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
