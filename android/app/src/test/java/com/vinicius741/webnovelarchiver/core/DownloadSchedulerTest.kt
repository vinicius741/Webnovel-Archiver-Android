package com.vinicius741.webnovelarchiver.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadSchedulerTest {
    @Test
    fun respectsGlobalConcurrency() {
        val selected = DownloadScheduler.selectEligibleJobs(
            jobs = listOf(job("a1", "RoyalRoad"), job("b1", "Scribble Hub"), job("a2", "RoyalRoad")),
            now = 1000,
            globalConcurrency = 2,
            globalDelay = 0,
            sourceSettings = emptyMap(),
            activeCounts = emptyMap(),
            nextAllowedAt = emptyMap(),
            providerNameForJob = { it.chapter.url },
        )

        assertEquals(listOf("a1", "b1"), selected.map { it.id })
    }

    @Test
    fun respectsPerSourceConcurrencyOverride() {
        val selected = DownloadScheduler.selectEligibleJobs(
            jobs = listOf(job("a1", "RoyalRoad"), job("a2", "RoyalRoad"), job("b1", "Scribble Hub")),
            now = 1000,
            globalConcurrency = 3,
            globalDelay = 0,
            sourceSettings = mapOf("RoyalRoad" to SourceDownloadSettings(concurrency = 1, delay = 0)),
            activeCounts = emptyMap(),
            nextAllowedAt = emptyMap(),
            providerNameForJob = { it.chapter.url },
        )

        assertEquals(listOf("a1", "b1"), selected.map { it.id })
    }

    @Test
    fun skipsSourceUntilDelayExpires() {
        val selected = DownloadScheduler.selectEligibleJobs(
            jobs = listOf(job("a1", "RoyalRoad"), job("b1", "Scribble Hub")),
            now = 1000,
            globalConcurrency = 2,
            globalDelay = 0,
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

    private fun job(id: String, providerName: String): DownloadJob {
        return DownloadJob(
            id = id,
            storyId = "story",
            storyTitle = "Story",
            chapterIndex = 0,
            chapter = Chapter(id = id, title = id, url = providerName),
            status = "pending",
        )
    }
}
