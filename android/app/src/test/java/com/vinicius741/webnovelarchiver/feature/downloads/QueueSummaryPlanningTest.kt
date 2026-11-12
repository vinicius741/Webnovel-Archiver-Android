package com.vinicius741.webnovelarchiver.feature.downloads

import com.vinicius741.webnovelarchiver.download.DownloadCounts
import com.vinicius741.webnovelarchiver.download.QueueScreenPlan
import com.vinicius741.webnovelarchiver.download.QueueSection
import com.vinicius741.webnovelarchiver.download.QueueStoryPlan
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueSummaryPlanningTest {
    private fun plan(
        counts: DownloadCounts,
        downloading: Int = 0,
    ) = QueueScreenPlan(
        counts = counts,
        storyCount = 1,
        blockedSites = emptyList(),
        failedChapters = counts.failed,
        downloading =
            List(downloading) {
                QueueStoryPlan("s$it", "Story $it", "rr", QueueSection.DOWNLOADING, counts, emptyList(), blocked = false)
            },
        upNext = emptyList(),
        paused = emptyList(),
        finished = emptyList(),
    )

    @Test
    fun liveQueueShowsPercentLeftAndActiveNovels() {
        val summary = QueueSummaryPlanning.summary(plan(DownloadCounts(downloading = 2, pending = 38, completed = 16), downloading = 2))
        assertEquals(28, summary.percent)
        assertEquals("40 chapters left · 2 active", summary.detail)
        assertEquals(QueueLiveState.LIVE, summary.state)
    }

    @Test
    fun pausedQueueReadsPausedAndCountsPausedChaptersAsLeft() {
        val summary = QueueSummaryPlanning.summary(plan(DownloadCounts(paused = 1, completed = 3)))
        assertEquals(75, summary.percent)
        assertEquals("1 chapter left", summary.detail)
        assertEquals(QueueLiveState.PAUSED, summary.state)
    }

    @Test
    fun finishedQueueWithFailuresIsDoneAndMentionsThem() {
        val summary = QueueSummaryPlanning.summary(plan(DownloadCounts(completed = 8, failed = 2)))
        assertEquals(80, summary.percent)
        assertEquals("0 chapters left · 2 failed", summary.detail)
        assertEquals(QueueLiveState.DONE, summary.state)
    }

    @Test
    fun emptyCountsDoNotDivideByZero() {
        assertEquals(0, QueueSummaryPlanning.summary(plan(DownloadCounts())).percent)
    }
}
