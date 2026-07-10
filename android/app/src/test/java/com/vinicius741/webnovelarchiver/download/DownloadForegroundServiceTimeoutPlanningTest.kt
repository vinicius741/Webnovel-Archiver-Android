package com.vinicius741.webnovelarchiver.download

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadForegroundServiceTimeoutPlanningTest {
    @Test
    fun recoversOnlyInFlightJobsForNextExplicitStart() {
        val jobs =
            listOf(
                job("pending", nextRetryAt = 100L),
                job("downloading", nextRetryAt = 200L),
                job("paused", nextRetryAt = 300L),
                job("completed"),
                job("failed"),
                job("cancelled"),
            )

        val recovered = DownloadForegroundServiceTimeoutPlanning.recoverQueue(jobs)

        assertEquals(
            listOf("pending", "pending", "paused", "completed", "failed", "cancelled"),
            recovered.map { it.status },
        )
        assertEquals(100L, recovered[0].nextRetryAt)
        assertNull(recovered[1].nextRetryAt)
        assertEquals(300L, recovered[2].nextRetryAt)
    }

    @Test
    fun doesNotMutatePublishedQueueObjects() {
        val downloading = job("downloading", nextRetryAt = 200L)

        val recovered = DownloadForegroundServiceTimeoutPlanning.recoverQueue(listOf(downloading)).single()

        assertNotSame(downloading, recovered)
        assertNotSame(downloading.chapter, recovered.chapter)
        assertEquals("downloading", downloading.status)
        assertEquals(200L, downloading.nextRetryAt)
    }

    private fun job(
        status: String,
        nextRetryAt: Long? = null,
    ): DownloadJob =
        DownloadJob(
            id = status,
            storyId = "story",
            storyTitle = "Story",
            chapterIndex = 0,
            chapter = Chapter(id = "chapter-$status", title = status),
            status = status,
            nextRetryAt = nextRetryAt,
        )
}
