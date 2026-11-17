package com.vinicius741.webnovelarchiver.feature.narration

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.narration.NarrationChapterStatus
import com.vinicius741.webnovelarchiver.narration.NarrationJob
import com.vinicius741.webnovelarchiver.narration.NarrationJobState
import com.vinicius741.webnovelarchiver.narration.NarrationPlanning
import com.vinicius741.webnovelarchiver.narration.NarrationProgress
import com.vinicius741.webnovelarchiver.narration.NarrationQueueDocument
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NarrationScreenPlanningTest {
    private fun job(
        id: String,
        storyId: String,
        state: NarrationJobState = NarrationJobState.QUEUED,
    ) = NarrationJob(id = id, storyId = storyId, chapterId = id, state = state)

    @Test
    fun `the queue groups by story in first-appearance order`() {
        val jobs = listOf(job("a1", "A"), job("b1", "B"), job("a2", "A", NarrationJobState.FAILED))
        val groups = NarrationScreenPlanning.groups(jobs)
        assertEquals(listOf("A", "B"), groups.map { it.storyId })
        assertEquals(listOf("a1", "a2"), groups[0].jobs.map { it.id })
        assertEquals(1, groups[0].failed)
    }

    @Test
    fun `putting a story first keeps the order inside each story`() {
        val jobs = listOf(job("a1", "A"), job("b1", "B"), job("a2", "A"), job("b2", "B"))
        assertEquals(listOf("b1", "b2", "a1", "a2"), NarrationPlanning.storyFirst(jobs, "B").map { it.id })
    }

    @Test
    fun `the banner says what is generating, how far and how fast`() {
        val running = job("a1", "A")
        val progress =
            NarrationProgress(
                job = running,
                storyTitle = "Story A",
                chapterTitle = "Chapter 1",
                done = 30,
                total = 120,
                chapterEtaMs = 6 * 60_000L,
                speed = 2.46,
                queuedAfter = 2,
                etaMs = 30 * 60_000L,
            )
        val banner = NarrationScreenPlanning.banner(progress, NarrationQueueDocument(jobs = listOf(running)), running = true)
        assertEquals(GenerationBanner.State.GENERATING, banner.state)
        assertEquals("Generating · 25%", banner.headline)
        assertEquals("A", banner.storyId)
        assertTrue(banner.detail!!.contains("30 of 120 sentences"))
        assertTrue(banner.detail!!.contains("2.5× real time"))
        assertTrue(banner.detail!!.contains("2 chapters after this"))
    }

    @Test
    fun `the banner says when a computer holds back for memory`() {
        val running = job("a1", "A")
        val progress =
            NarrationProgress(
                job = running,
                chapterTitle = "Chapter 1",
                done = 60,
                total = 120,
                generatingOn = "Bazzite",
                waitingFor = "Bazzite is low on memory and has stopped generating (800 MB free)",
            )
        val banner = NarrationScreenPlanning.banner(progress, NarrationQueueDocument(jobs = listOf(running)), running = true)
        assertEquals(GenerationBanner.State.GENERATING, banner.state)
        assertEquals("Waiting for Bazzite · 50%", banner.headline)
        assertTrue(banner.detail!!.startsWith("Bazzite is low on memory"))
        assertTrue(banner.detail!!.contains("resumes by itself"))
        assertEquals("Generating on Bazzite · 50%", NarrationScreenPlanning.generatingTitle(progress.copy(waitingFor = null)))
    }

    @Test
    fun `the banner distinguishes paused, loading, waiting on Android and idle`() {
        val queued = NarrationQueueDocument(jobs = listOf(job("a1", "A")))
        assertEquals(GenerationBanner.State.PAUSED, NarrationScreenPlanning.banner(null, queued.copy(paused = true), true).state)
        val loading = NarrationProgress(job = job("a1", "A"), chapterTitle = "Chapter 1", preparing = true)
        assertEquals(GenerationBanner.State.STARTING, NarrationScreenPlanning.banner(loading, queued, true).state)
        assertEquals(GenerationBanner.State.WAITING, NarrationScreenPlanning.banner(null, queued, false).state)
        val failed = NarrationQueueDocument(jobs = listOf(job("a1", "A", NarrationJobState.FAILED)))
        assertEquals(GenerationBanner.State.FAILED, NarrationScreenPlanning.banner(null, failed, true).state)
        assertEquals(GenerationBanner.State.IDLE, NarrationScreenPlanning.banner(null, NarrationQueueDocument(), true).state)
    }

    @Test
    fun `changesSince keeps a change that landed before subscribing`() =
        runTest {
            // Built while the queue still held the finishing job; it was gone by the first collected value.
            assertEquals(listOf(0), NarrationScreenPlanning.changesSince(flowOf(0), shown = 1).toList())
        }

    @Test
    fun `changesSince skips what the screen already shows, then every distinct change`() =
        runTest {
            assertEquals(listOf(2, 1), NarrationScreenPlanning.changesSince(flowOf(1, 1, 2, 2, 1), shown = 1).toList())
        }
}
