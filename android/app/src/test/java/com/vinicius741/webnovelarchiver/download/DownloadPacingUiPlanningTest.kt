package com.vinicius741.webnovelarchiver.download

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadPacingUiPlanningTest {
    @Test
    fun countdownRoundsUpForChapterStatus() {
        val snapshot = snapshot(nextRequestAtMillis = 25_001L)

        val status =
            DownloadPacingUiPlanning
                .waitingJobs(listOf(snapshot), listOf(job("job-a", "story-a")), 1_000L)
                .getValue("job-a")

        assertEquals(25L, status.remainingSeconds)
        assertEquals("25 seconds", DownloadPacingUiPlanning.formatDuration(requireNotNull(status.remainingSeconds)))
    }

    @Test
    fun matchingStoryKeepsConfiguredWaitOffQueueGroupHeader() {
        val status =
            DownloadPacingUiPlanning.storyStatus(
                storyId = "story-a",
                providerName = "Scribble Hub",
                storyJobs = listOf(job("job-a", "story-a")),
                snapshots = listOf(snapshot()),
                nowMillis = 1_000L,
            )

        assertEquals(DownloadPacingUiKind.CONFIGURED_WAIT, status?.kind)
        assertNull(DownloadPacingUiPlanning.groupHeadline(status))
        assertEquals("Chapter 42", status?.chapterTitle)
    }

    @Test
    fun retryTakesPriorityAndIsNotDescribedAsConfiguredDelay() {
        val retryJob =
            job("job-a", "story-a").apply {
                nextRetryAt = 91_000L
                errorCategory = "rate_limit"
            }

        val status =
            DownloadPacingUiPlanning.storyStatus(
                storyId = "story-a",
                providerName = "Scribble Hub",
                storyJobs = listOf(retryJob),
                snapshots = listOf(snapshot()),
                nowMillis = 1_000L,
            )

        assertEquals(DownloadPacingUiKind.RATE_LIMIT_WAIT, status?.kind)
        assertEquals("Rate limited · Retrying in 1m 30s", DownloadPacingUiPlanning.groupHeadline(status))
    }

    @Test
    fun storyWaitingOnSameProviderShowsQueuedBehind() {
        val status =
            DownloadPacingUiPlanning.storyStatus(
                storyId = "story-b",
                providerName = "Scribble Hub",
                storyJobs = listOf(job("job-b", "story-b").apply { status = "pending" }),
                snapshots = listOf(snapshot()),
                nowMillis = 1_000L,
                allJobs = listOf(job("job-b", "story-b").apply { status = "pending" }, job("job-a", "story-a")),
            )

        assertEquals(DownloadPacingUiKind.QUEUED_BEHIND, status?.kind)
        assertEquals(
            "Queued behind other Scribble Hub downloads",
            DownloadPacingUiPlanning.groupHeadline(status),
        )
    }

    @Test
    fun expiredSnapshotProducesNoStatus() {
        assertNull(
            DownloadPacingUiPlanning.storyStatus(
                storyId = "story-a",
                providerName = "Scribble Hub",
                storyJobs = listOf(job("job-a", "story-a")),
                snapshots = listOf(snapshot(nextRequestAtMillis = 999L)),
                nowMillis = 1_000L,
            ),
        )
    }

    @Test
    fun snapshotForPausedJobProducesNoStatus() {
        val paused = job("job-a", "story-a").apply { status = "paused" }

        assertNull(
            DownloadPacingUiPlanning.storyStatus(
                storyId = "story-a",
                providerName = "Scribble Hub",
                storyJobs = listOf(paused),
                snapshots = listOf(snapshot()),
                nowMillis = 1_000L,
            ),
        )
        assertEquals(
            emptyMap<String, DownloadPacingUiStatus>(),
            DownloadPacingUiPlanning.waitingJobs(listOf(snapshot()), listOf(paused), 1_000L),
        )
    }

    @Test
    fun waitingJobsIncludesOnlyClaimedJobsStillInTheConfiguredDelay() {
        assertEquals(
            setOf("job-a"),
            DownloadPacingUiPlanning.waitingJobs(listOf(snapshot()), listOf(job("job-a", "story-a")), 1_000L).keys,
        )
        assertEquals(
            emptyMap<String, DownloadPacingUiStatus>(),
            DownloadPacingUiPlanning.waitingJobs(
                listOf(snapshot()),
                listOf(job("job-a", "story-a").apply { status = "pending" }),
                1_000L,
            ),
        )
    }

    private fun snapshot(nextRequestAtMillis: Long = 30_000L) =
        DownloadPacingSnapshot(
            providerName = "Scribble Hub",
            storyId = "story-a",
            jobId = "job-a",
            chapterTitle = "Chapter 42",
            nextRequestAtMillis = nextRequestAtMillis,
        )

    private fun job(
        id: String,
        storyId: String,
    ) = DownloadJob(
        id = id,
        storyId = storyId,
        status = "downloading",
        chapter = Chapter(title = "Chapter 42", url = "https://scribblehub.com/read/42"),
    )
}
