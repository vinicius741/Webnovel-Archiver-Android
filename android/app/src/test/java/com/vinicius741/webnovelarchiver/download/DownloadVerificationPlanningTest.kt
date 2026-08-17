package com.vinicius741.webnovelarchiver.download

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadVerificationPlanningTest {
    @Test
    fun pendingJobsOfABlockedSourceAreCountedWithASampleUrl() {
        val jobs =
            listOf(
                job("a", "scribblehub", "pending"),
                job("b", "scribblehub", "pending"),
                job("c", "royalroad", "pending"),
            )

        val evidence =
            DownloadVerificationPlanning.blockedPendingEvidence(jobs, { it == "scribblehub" }) { it.chapter.url }

        assertEquals(2, evidence.pendingCount)
        assertEquals("scribblehub", evidence.sampleUrl)
    }

    @Test
    fun unblockedSourcesAndNonPendingJobsProduceNoEvidence() {
        val jobs =
            listOf(
                job("a", "scribblehub", "pending"),
                job("b", "royalroad", "downloading"),
            )

        val evidence = DownloadVerificationPlanning.blockedPendingEvidence(jobs, { false }) { it.chapter.url }

        assertEquals(0, evidence.pendingCount)
        assertNull(evidence.sampleUrl)
    }

    @Test
    fun aBlockedSourceWithNoQueuePresenceProducesNoEvidence() {
        val jobs = listOf(job("a", "scribblehub", "pending"))

        val evidence = DownloadVerificationPlanning.blockedPendingEvidence(jobs, { it == "royalroad" }) { it.chapter.url }

        assertEquals(0, evidence.pendingCount)
        assertNull(evidence.sampleUrl)
    }

    @Test
    fun failedSourceBlockedJobsAloneDoNotCountAsPendingEvidence() {
        // The all-pending state is the gap this planning closes; a queue whose blocked jobs already
        // failed has the retry-keyed solve flow, and the banner must not double-report it.
        val jobs =
            listOf(
                job("a", "scribblehub", "failed").apply { errorCategory = "source_blocked" },
            )

        val evidence = DownloadVerificationPlanning.blockedPendingEvidence(jobs, { it == "scribblehub" }) { it.chapter.url }

        assertEquals(0, evidence.pendingCount)
        assertNull(evidence.sampleUrl)
    }

    @Test
    fun aBlockedSourceHoldingDownloadingWorkOnlyIsNotPendingEvidence() {
        val jobs = listOf(job("a", "scribblehub", "downloading"))

        val evidence = DownloadVerificationPlanning.blockedPendingEvidence(jobs, { it == "scribblehub" }) { it.chapter.url }

        assertEquals(0, evidence.pendingCount)
        assertNull(evidence.sampleUrl)
    }

    @Test
    fun jobsWithoutAProviderResolutionAreIgnored() {
        val jobs = listOf(job("a", null, "pending"))

        val evidence =
            DownloadVerificationPlanning.blockedPendingEvidence(jobs, { true }) { it.chapter.url.ifBlank { null } }

        assertEquals(0, evidence.pendingCount)
        assertNull(evidence.sampleUrl)
    }

    private fun job(
        id: String,
        provider: String?,
        status: String,
    ) = DownloadJob(id = id, status = status, chapter = Chapter(url = provider ?: ""))
}
