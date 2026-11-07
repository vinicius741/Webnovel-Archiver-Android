package com.vinicius741.webnovelarchiver.domain.download

import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadJobTransitionsTest {
    private val network = DownloadJobFailure("HTTP 503", "network", "503")
    private val retryIn5s: (DownloadJob) -> Long? = { 5_000L }
    private val terminal: (DownloadJob) -> Long? = { null }

    private fun job(
        status: DownloadJobStatus,
        nextRetryAt: Long? = null,
        errorCategory: String? = null,
    ) = DownloadJob(id = "j", status = status.wire, nextRetryAt = nextRetryAt, errorCategory = errorCategory)

    @Test
    fun lateRetryableFailureCannotUnpauseAJobPausedMidRequest() {
        val paused = job(DownloadJobStatus.Paused)

        assertFalse(DownloadJobTransitions.recordWorkerFailure(paused, network, now = 1_000L, retryIn5s))

        assertEquals(DownloadJobStatus.Paused.wire, paused.status)
        assertNull(paused.nextRetryAt)
        assertEquals(0, paused.retryCount)
        assertNull(paused.error)
    }

    @Test
    fun lateFailureCannotRequeueACancelledJob() {
        val cancelled = job(DownloadJobStatus.Downloading)
        DownloadJobTransitions.cancel(cancelled, "cancelled by user")

        assertFalse(DownloadJobTransitions.recordWorkerFailure(cancelled, network, now = 1_000L, retryIn5s))

        assertEquals(DownloadJobStatus.Cancelled.wire, cancelled.status)
        assertEquals("cancelled", cancelled.errorCategory)
    }

    @Test
    fun retryableFailureWaitsWithItsErrorAndTerminalFailureFails() {
        val retrying = job(DownloadJobStatus.Downloading)
        assertTrue(DownloadJobTransitions.recordWorkerFailure(retrying, network, now = 1_000L, retryIn5s))
        assertEquals(DownloadJobStatus.Pending.wire, retrying.status)
        assertEquals(6_000L, retrying.nextRetryAt)
        assertEquals(1, retrying.retryCount)
        assertEquals("network", retrying.errorCategory)
        assertEquals(DownloadJobPhase.WaitingForRetry(6_000L), DownloadJobTransitions.phase(retrying, now = 1_000L))
        assertEquals(DownloadJobPhase.Runnable, DownloadJobTransitions.phase(retrying, now = 6_000L))

        val failing = job(DownloadJobStatus.Downloading)
        assertTrue(DownloadJobTransitions.recordWorkerFailure(failing, network, now = 1_000L, terminal))
        assertEquals(DownloadJobStatus.Failed.wire, failing.status)
        assertNull(failing.nextRetryAt)
        assertEquals(DownloadJobPhase.Failed, DownloadJobTransitions.phase(failing, now = 1_000L))
    }

    @Test
    fun retryPolicySeesTheCountedAttempt() {
        val downloading = job(DownloadJobStatus.Downloading).apply { retryCount = 2 }
        var seen = -1

        DownloadJobTransitions.recordWorkerFailure(downloading, network, now = 0L) {
            seen = it.retryCount
            null
        }

        assertEquals(3, seen)
    }

    @Test
    fun claimConsumesTheRetryDeadline() {
        val pending = job(DownloadJobStatus.Pending, nextRetryAt = 500L)

        assertTrue(DownloadJobTransitions.claim(pending))

        assertEquals(DownloadJobStatus.Downloading.wire, pending.status)
        assertNull(pending.nextRetryAt)
        assertFalse("only pending work can be claimed", DownloadJobTransitions.claim(pending))
    }

    @Test
    fun deferOnlyPushesPendingDeadlinesLater() {
        val pending = job(DownloadJobStatus.Pending, nextRetryAt = 9_000L)
        DownloadJobTransitions.deferUntil(pending, 5_000L)
        assertEquals(9_000L, pending.nextRetryAt)
        DownloadJobTransitions.deferUntil(pending, 12_000L)
        assertEquals(12_000L, pending.nextRetryAt)

        val paused = job(DownloadJobStatus.Paused)
        assertFalse(DownloadJobTransitions.deferUntil(paused, 12_000L))
        assertNull(paused.nextRetryAt)
    }

    @Test
    fun sourceBlockFailsOnlyInFlightWorkAndVerificationReleasesItForFree() {
        val inFlight = job(DownloadJobStatus.Downloading).apply { retryCount = 1 }
        assertTrue(DownloadJobTransitions.failSourceBlocked(inFlight, "challenge"))
        assertEquals(DownloadJobPhase.AwaitingVerification, DownloadJobTransitions.phase(inFlight, now = 0L))
        assertFalse(DownloadJobTransitions.failSourceBlocked(job(DownloadJobStatus.Pending), "challenge"))

        assertTrue(DownloadJobTransitions.releaseAfterVerification(inFlight))
        assertEquals(DownloadJobStatus.Pending.wire, inFlight.status)
        assertEquals("verification must not spend a retry", 1, inFlight.retryCount)
        assertNull(inFlight.errorCategory)

        val deferred = job(DownloadJobStatus.Pending, nextRetryAt = 99_000L)
        assertTrue(DownloadJobTransitions.releaseAfterVerification(deferred))
        assertNull(deferred.nextRetryAt)

        val ordinaryFailure = job(DownloadJobStatus.Failed, errorCategory = "network")
        assertFalse(DownloadJobTransitions.releaseAfterVerification(ordinaryFailure))
        assertEquals(DownloadJobStatus.Failed.wire, ordinaryFailure.status)
    }

    @Test
    fun manualRetrySpendsARetryAndClearsTheError() {
        val failed = job(DownloadJobStatus.Failed, nextRetryAt = 7L, errorCategory = "parse").apply { error = "short" }

        assertTrue(DownloadJobTransitions.requeueForManualRetry(failed))

        assertEquals(DownloadJobStatus.Pending.wire, failed.status)
        assertEquals(1, failed.retryCount)
        assertNull(failed.error)
        assertNull(failed.errorCategory)
        assertNull(failed.nextRetryAt)
        assertFalse(DownloadJobTransitions.requeueForManualRetry(job(DownloadJobStatus.Completed)))
    }

    @Test
    fun interruptedRecoveryTouchesOnlyDownloadingJobs() {
        val statuses = DownloadJobStatus.entries.map { job(it, nextRetryAt = 42L) }

        statuses.forEach(DownloadJobTransitions::recoverInterrupted)

        val downloading = statuses[DownloadJobStatus.Downloading.ordinal]
        assertEquals(DownloadJobStatus.Pending.wire, downloading.status)
        assertNull(downloading.nextRetryAt)
        statuses.filter { it !== downloading }.forEach { assertEquals(42L, it.nextRetryAt) }
    }

    @Test
    fun onlyADownloadingRowCompletes() {
        val completed = DownloadJobTransitions.completed(job(DownloadJobStatus.Downloading, errorCategory = "network"))
        assertEquals(DownloadJobStatus.Completed.wire, completed?.status)
        assertNull(completed?.errorCategory)

        assertNull(DownloadJobTransitions.completed(job(DownloadJobStatus.Cancelled)))
        assertNull(DownloadJobTransitions.completed(job(DownloadJobStatus.Paused)))
    }

    @Test
    fun unknownStatusIsNeverALegalSourceState() {
        val corrupt = DownloadJob(id = "j", status = "half-written")

        assertFalse(DownloadJobTransitions.claim(corrupt))
        assertFalse(DownloadJobTransitions.recoverInterrupted(corrupt))
        assertFalse(DownloadJobTransitions.pause(corrupt))
        assertEquals("half-written", corrupt.status)
    }
}
