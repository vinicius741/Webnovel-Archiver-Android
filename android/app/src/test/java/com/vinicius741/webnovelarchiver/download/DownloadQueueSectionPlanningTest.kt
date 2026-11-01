package com.vinicius741.webnovelarchiver.download

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueueSectionPlanningTest {
    private val provider: (DownloadJob) -> String? = { it.sourceId }

    @Test
    fun sectionsFollowEngineOrderNotNewestFirst() {
        val jobs =
            listOf(
                job("a", 0, "rr", "completed"),
                job("a", 1, "rr", "downloading"),
                job("b", 0, "rr", "pending"),
                job("c", 0, "sh", "pending"),
                job("d", 0, "rr", "paused"),
                job("e", 0, "rr", "completed"),
            )

        val plan = DownloadQueueSectionPlanning.plan(jobs, provider) { false }

        assertEquals(listOf("a"), plan.downloading.map { it.storyId })
        assertEquals(listOf("b", "c"), plan.upNext.map { it.storyId })
        assertEquals(listOf("d"), plan.paused.map { it.storyId })
        assertEquals(listOf("e"), plan.finished.map { it.storyId })
        assertEquals(5, plan.storyCount)
    }

    @Test
    fun upNextNamesTheNovelAheadOnTheSameSiteOnly() {
        val jobs =
            listOf(
                job("a", 0, "rr", "downloading"),
                job("b", 0, "sh", "pending"),
                job("c", 0, "rr", "pending"),
            )

        val plan = DownloadQueueSectionPlanning.plan(jobs, provider) { false }

        assertNull(plan.upNext.first { it.storyId == "b" }.behindTitle)
        assertEquals("Story a", plan.upNext.first { it.storyId == "c" }.behindTitle)
    }

    @Test
    fun blockedFailuresStayUpNextAndAnnounceTheirSite() {
        val jobs =
            listOf(
                job("a", 0, "rr", "failed", errorCategory = "source_blocked", url = "https://rr/1"),
                job("a", 1, "rr", "pending", url = "https://rr/2"),
                job("b", 0, "rr", "failed", errorCategory = "network"),
                job("c", 0, "sh", "pending"),
            )

        val plan = DownloadQueueSectionPlanning.plan(jobs, provider) { false }

        assertEquals(listOf("a", "c"), plan.upNext.map { it.storyId })
        assertTrue(plan.upNext.first().blocked)
        assertEquals(1, plan.failedChapters)
        val site = plan.blockedSites.single()
        assertEquals("rr", site.providerId)
        assertEquals(listOf("a"), site.storyIds)
        assertEquals(2, site.chapterCount)
        assertEquals("https://rr/1", site.sampleUrl)
    }

    @Test
    fun pendingWorkBehindAnOpenCircuitIsBlockedWithoutAnyFailure() {
        val jobs = listOf(job("a", 0, "rr", "pending"), job("b", 0, "sh", "pending"))

        val sites = DownloadQueueSectionPlanning.blockedSites(jobs, provider) { it == "sh" }

        assertEquals(listOf("sh"), sites.map { it.providerId })
    }

    @Test
    fun checkCardsMoveFromNeedsCheckToStillBlockedToVerified() {
        val site = BlockedSitePlan("rr", listOf("a"), 3, "https://rr/1")

        val fresh = DownloadQueueSectionPlanning.checkCards(listOf(site), emptyMap(), nowMillis = 1_000)
        val failedCheck =
            DownloadQueueSectionPlanning.checkCards(listOf(site), mapOf("rr" to SourceCheckOutcome(false, 0, 900)), nowMillis = 1_000)
        val passed =
            DownloadQueueSectionPlanning.checkCards(emptyList(), mapOf("rr" to SourceCheckOutcome(true, 3, 900)), nowMillis = 1_000)
        val expired =
            DownloadQueueSectionPlanning.checkCards(
                emptyList(),
                mapOf("rr" to SourceCheckOutcome(true, 3, 900)),
                nowMillis = 900 + DownloadQueueSectionPlanning.VERIFIED_CARD_MILLIS,
            )

        assertEquals(SourceCheckPhase.NEEDS_CHECK, fresh.single().phase)
        assertEquals(SourceCheckPhase.STILL_BLOCKED, failedCheck.single().phase)
        assertEquals(SourceCheckPhase.VERIFIED, passed.single().phase)
        assertEquals(3, passed.single().chapterCount)
        assertTrue(expired.isEmpty())
    }

    @Test
    fun reorderPermutesOnlyTheNamedNovelsInTheirOwnSlots() {
        val jobs =
            listOf(
                job("a", 0, "rr", "downloading"),
                job("b", 0, "rr", "pending"),
                job("b", 1, "rr", "pending"),
                job("c", 0, "rr", "pending"),
                job("d", 0, "rr", "paused"),
            )

        val reordered = DownloadQueueOrderPlanning.reorderStories(jobs, listOf("c", "b"))

        assertEquals(listOf("a_0", "c_0", "b_0", "b_1", "d_0"), reordered.map { it.id })
    }

    @Test
    fun downloadNextAndMoveToBottomMoveWholeNovels() {
        val jobs = listOf(job("a", 0, "rr", "pending"), job("b", 0, "rr", "pending"), job("a", 1, "rr", "failed"))

        assertEquals(listOf("b_0", "a_0", "a_1"), DownloadQueueOrderPlanning.moveStoryToFront(jobs, "b").map { it.id })
        assertEquals(listOf("b_0", "a_0", "a_1"), DownloadQueueOrderPlanning.moveStoryToEnd(jobs, "a").map { it.id })
        assertEquals(jobs, DownloadQueueOrderPlanning.moveStoryToFront(jobs, "missing"))
    }

    @Test
    fun resumeAfterVerificationRequeuesBlockedFailuresWithoutSpendingRetries() {
        val jobs =
            listOf(
                job("a", 0, "rr", "failed", errorCategory = "source_blocked", retryCount = 1),
                job("a", 1, "rr", "pending", nextRetryAt = 99_000),
                job("b", 0, "rr", "failed", errorCategory = "network"),
                job("c", 0, "sh", "failed", errorCategory = "source_blocked"),
            )

        val resume = DownloadQueueOrderPlanning.resumeAfterVerification(jobs, "rr", provider)

        assertEquals(2, resume.resumedChapters)
        val requeued = resume.jobs[0]
        assertEquals("pending", requeued.status)
        assertEquals(1, requeued.retryCount)
        assertNull(requeued.errorCategory)
        assertNull(resume.jobs[1].nextRetryAt)
        assertEquals("failed", resume.jobs[2].status)
        assertEquals("failed", resume.jobs[3].status)
        assertEquals("failed", jobs[0].status)
    }

    @Test
    fun storyStatusSpeaksPlainly() {
        val behind = story(QueueSection.UP_NEXT, behindTitle = "Worm")
        val wait = DownloadPacingUiStatus(DownloadPacingUiKind.CONFIGURED_WAIT, "Royal Road", remainingSeconds = 8)
        val rateLimit = DownloadPacingUiStatus(DownloadPacingUiKind.RATE_LIMIT_WAIT, "Royal Road", remainingSeconds = 240)

        assertEquals("After Worm", DownloadQueueStatusCopy.storyStatusLine(behind, null))
        assertEquals("Starting soon", DownloadQueueStatusCopy.storyStatusLine(story(QueueSection.UP_NEXT), null))
        assertEquals("Next chapter in 8 seconds", DownloadQueueStatusCopy.storyStatusLine(story(QueueSection.DOWNLOADING), wait))
        assertEquals(
            "Site asked to slow down · resuming in 4m",
            DownloadQueueStatusCopy.storyStatusLine(story(QueueSection.UP_NEXT), rateLimit),
        )
        assertEquals("Waiting for verification", DownloadQueueStatusCopy.storyStatusLine(story(QueueSection.UP_NEXT, blocked = true), null))
        assertEquals("Done", DownloadQueueStatusCopy.storyStatusLine(story(QueueSection.FINISHED), null))
    }

    @Test
    fun chapterStatusHidesRawCategories() {
        val failed = job("a", 0, "rr", "failed", errorCategory = "network")
        val retrying = job("a", 1, "rr", "pending", errorCategory = "rate_limit", nextRetryAt = 31_000)

        assertEquals("Failed · network problem", DownloadQueueStatusCopy.chapterStatusLabel(failed, false, null, 1_000))
        assertEquals(
            "Site asked to slow down · retry in 30 seconds",
            DownloadQueueStatusCopy.chapterStatusLabel(retrying, false, null, 1_000),
        )
        assertEquals("Queued", DownloadQueueStatusCopy.chapterStatusLabel(job("a", 2, "rr", "pending"), false, null, 1_000))
        assertFalse(DownloadQueueStatusCopy.chapterStatusLabel(failed, false, null, 1_000).contains("network ("))
    }

    @Test
    fun summaryLineCountsNovelsAndChapters() {
        val jobs = listOf(job("a", 0, "rr", "completed"), job("a", 1, "rr", "pending"))
        val plan = DownloadQueueSectionPlanning.plan(jobs, provider) { false }

        assertEquals("1 novel · 1 of 2 chapters", DownloadQueueStatusCopy.summaryLine(plan))
    }

    private fun story(
        section: QueueSection,
        behindTitle: String? = null,
        blocked: Boolean = false,
    ) = QueueStoryPlan(
        storyId = "s",
        title = "S",
        providerId = "rr",
        section = section,
        counts = DownloadCounts(completed = 1),
        jobs = emptyList(),
        blocked = blocked,
        behindTitle = behindTitle,
    )

    @Suppress("LongParameterList")
    private fun job(
        storyId: String,
        index: Int,
        source: String,
        status: String,
        errorCategory: String? = null,
        url: String = "https://$source/$storyId/$index",
        retryCount: Int = 0,
        nextRetryAt: Long? = null,
    ) = DownloadJob(
        id = "${storyId}_$index",
        storyId = storyId,
        storyTitle = "Story $storyId",
        sourceId = source,
        chapterIndex = index,
        chapter = Chapter(title = "Chapter $index", url = url),
        status = status,
        retryCount = retryCount,
        errorCategory = errorCategory,
        nextRetryAt = nextRetryAt,
    )
}
