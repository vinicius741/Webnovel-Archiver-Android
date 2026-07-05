package com.vinicius741.webnovelarchiver.download

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.SourceDownloadSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DownloadSchedulerTest {
    @Test
    fun respectsGlobalConcurrency() {
        val selected =
            DownloadScheduler.selectEligibleJobs(
                jobs = listOf(job("a1", "RoyalRoad"), job("b1", "Scribble Hub"), job("a2", "RoyalRoad")),
                now = 1000,
                globalSettings = SourceDownloadSettings(concurrency = 2, delay = 0, delayMax = 0),
                sourceSettings = emptyMap(),
                activeCounts = emptyMap(),
                nextAllowedAt = emptyMap(),
                providerNameForJob = { it.chapter.url },
            )

        assertEquals(listOf("a1", "b1"), selected.map { it.id })
    }

    @Test
    fun respectsPerSourceConcurrencyOverride() {
        val selected =
            DownloadScheduler.selectEligibleJobs(
                jobs = listOf(job("a1", "RoyalRoad"), job("a2", "RoyalRoad"), job("b1", "Scribble Hub")),
                now = 1000,
                globalSettings = SourceDownloadSettings(concurrency = 3, delay = 0, delayMax = 0),
                sourceSettings =
                    mapOf(
                        "RoyalRoad" to SourceDownloadSettings(concurrency = 1, delay = 0, delayMax = 0),
                    ),
                activeCounts = emptyMap(),
                nextAllowedAt = emptyMap(),
                providerNameForJob = { it.chapter.url },
            )

        assertEquals(listOf("a1", "b1"), selected.map { it.id })
    }

    @Test
    fun skipsSourceUntilDelayExpires() {
        val selected =
            DownloadScheduler.selectEligibleJobs(
                jobs = listOf(job("a1", "RoyalRoad"), job("b1", "Scribble Hub")),
                now = 1000,
                globalSettings = SourceDownloadSettings(concurrency = 2, delay = 0, delayMax = 0),
                sourceSettings = emptyMap(),
                activeCounts = emptyMap(),
                nextAllowedAt = mapOf("RoyalRoad" to 2000),
                providerNameForJob = { it.chapter.url },
            )

        assertEquals(listOf("b1"), selected.map { it.id })
        assertEquals(
            2000L,
            DownloadScheduler.nextWakeUpAt(
                jobs = listOf(job("a1", "RoyalRoad")),
                now = 1000,
                nextAllowedAt = mapOf("RoyalRoad" to 2000),
                providerNameForJob = { it.chapter.url },
            ),
        )
    }

    @Test
    fun settingsForKeepsDelayRangeAndCoercesInvalidMax() {
        assertEquals(
            SourceDownloadSettings(concurrency = 2, delay = 800, delayMax = 1200),
            DownloadScheduler.settingsFor(
                providerName = "RoyalRoad",
                globalSettings = SourceDownloadSettings(concurrency = 2, delay = 800, delayMax = 1200),
                sourceSettings = emptyMap(),
            ),
        )
        assertEquals(
            SourceDownloadSettings(concurrency = 1, delay = 1500, delayMax = 1500),
            DownloadScheduler.settingsFor(
                providerName = "RoyalRoad",
                globalSettings = SourceDownloadSettings(concurrency = 2, delay = 800, delayMax = 1200),
                sourceSettings =
                    mapOf(
                        "RoyalRoad" to SourceDownloadSettings(concurrency = 1, delay = 1500, delayMax = 0),
                    ),
            ),
        )
    }

    @Test
    fun randomDelaySamplesWithinRangeAndKeepsFixedDelay() {
        assertEquals(
            500L,
            DownloadScheduler.randomDelayMillis(SourceDownloadSettings(delay = 500, delayMax = 500), Random(1)),
        )

        repeat(100) {
            val delay =
                DownloadScheduler.randomDelayMillis(
                    SourceDownloadSettings(delay = 800, delayMax = 1200),
                    Random(it),
                )
            assertTrue("Delay $delay was outside the configured range", delay in 800L..1200L)
        }
    }

    private fun job(
        id: String,
        providerName: String,
    ): DownloadJob =
        DownloadJob(
            id = id,
            storyId = "story",
            storyTitle = "Story",
            chapterIndex = 0,
            chapter = Chapter(id = id, title = id, url = providerName),
            status = "pending",
        )
}
