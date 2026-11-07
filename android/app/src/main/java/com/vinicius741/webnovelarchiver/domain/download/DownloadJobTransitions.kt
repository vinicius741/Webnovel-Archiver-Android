package com.vinicius741.webnovelarchiver.domain.download

import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus

/**
 * The only code that changes a [DownloadJob]'s lifecycle. Status, retry deadline, retry count, and
 * the error fields are one state, so every transition writes all of them together and names the
 * statuses it may start from. A transition from any other status leaves the job untouched and
 * returns `false`. `DownloadJobTransitionOwnershipTest` keeps other production code from assigning
 * these fields directly.
 *
 * Source-wide access (open circuits, cooldowns) is not job state: it stays owned by
 * `SourceReliabilityCoordinator`, and the scheduler consults it beside [phase].
 */
@Suppress("TooManyFunctions") // One function per legal transition is the point of this owner.
object DownloadJobTransitions {
    const val SOURCE_BLOCKED_CATEGORY = "source_blocked"
    private const val SOURCE_BLOCKED_CODE = "SOURCE_BLOCKED"
    private const val CANCELLED_CATEGORY = "cancelled"
    private const val CANCELLED_CODE = "CANCELLED"

    private val active = setOf(DownloadJobStatus.Pending, DownloadJobStatus.Downloading)
    private val cancellable = active + DownloadJobStatus.Paused
    private val manuallyRetryable = setOf(DownloadJobStatus.Failed, DownloadJobStatus.Cancelled)

    /** What the job is doing now; [now] decides whether a pending job's retry deadline has passed. */
    fun phase(
        job: DownloadJob,
        now: Long,
    ): DownloadJobPhase =
        when (DownloadJobStatus.parse(job.status)) {
            DownloadJobStatus.Pending ->
                job.nextRetryAt?.takeIf { it > now }?.let(DownloadJobPhase::WaitingForRetry) ?: DownloadJobPhase.Runnable
            DownloadJobStatus.Downloading -> DownloadJobPhase.Downloading
            DownloadJobStatus.Paused -> DownloadJobPhase.Paused
            DownloadJobStatus.Completed -> DownloadJobPhase.Completed
            DownloadJobStatus.Cancelled -> DownloadJobPhase.Cancelled
            DownloadJobStatus.Failed ->
                if (job.errorCategory == SOURCE_BLOCKED_CATEGORY) DownloadJobPhase.AwaitingVerification else DownloadJobPhase.Failed
        }

    fun isAwaitingVerification(job: DownloadJob): Boolean =
        job.status == DownloadJobStatus.Failed.wire && job.errorCategory == SOURCE_BLOCKED_CATEGORY

    /** Pending → downloading. The retry deadline was consumed by being scheduled. */
    fun claim(job: DownloadJob): Boolean =
        move(job, setOf(DownloadJobStatus.Pending)) {
            status = DownloadJobStatus.Downloading.wire
            nextRetryAt = null
        }

    fun pause(job: DownloadJob): Boolean =
        move(job, active) {
            status = DownloadJobStatus.Paused.wire
            nextRetryAt = null
        }

    fun resume(job: DownloadJob): Boolean =
        move(job, setOf(DownloadJobStatus.Paused)) {
            status = DownloadJobStatus.Pending.wire
            nextRetryAt = null
        }

    fun cancel(
        job: DownloadJob,
        reason: String,
    ): Boolean =
        move(job, cancellable) {
            status = DownloadJobStatus.Cancelled.wire
            error = reason
            errorCategory = CANCELLED_CATEGORY
            errorCode = CANCELLED_CODE
            nextRetryAt = null
        }

    /** The user's RETRY on a failed or cancelled row; it spends one retry. */
    fun requeueForManualRetry(job: DownloadJob): Boolean =
        move(job, manuallyRetryable) {
            status = DownloadJobStatus.Pending.wire
            retryCount += 1
            clearError()
        }

    /**
     * Applies a worker's failure. Only a job that is still downloading accepts it: a pause or cancel
     * that landed while the request was in flight wins, so a retryable failure cannot quietly put a
     * paused job back in the queue.
     *
     * [retryDelayMillis] sees the job with its retry already counted and returns the auto-retry
     * delay, or null when the failure is terminal (the download package owns that policy).
     */
    fun recordWorkerFailure(
        job: DownloadJob,
        failure: DownloadJobFailure,
        now: Long,
        retryDelayMillis: (DownloadJob) -> Long?,
    ): Boolean =
        move(job, setOf(DownloadJobStatus.Downloading)) {
            retryCount += 1
            error = failure.message
            errorCategory = failure.category
            errorCode = failure.code
            val delay = retryDelayMillis(this)
            if (delay != null) {
                // Pending keeps the last error so the row can say why it is waiting.
                status = DownloadJobStatus.Pending.wire
                nextRetryAt = now + delay
            } else {
                status = if (failure.category == CANCELLED_CATEGORY) DownloadJobStatus.Cancelled.wire else DownloadJobStatus.Failed.wire
                nextRetryAt = null
            }
        }

    /** An open manual-verification circuit fails in-flight work so the solve flow can key off it. */
    fun failSourceBlocked(
        job: DownloadJob,
        message: String?,
    ): Boolean =
        move(job, setOf(DownloadJobStatus.Downloading)) {
            status = DownloadJobStatus.Failed.wire
            error = message
            errorCategory = SOURCE_BLOCKED_CATEGORY
            errorCode = SOURCE_BLOCKED_CODE
            nextRetryAt = null
        }

    fun failUnsupportedSource(
        job: DownloadJob,
        message: String,
        category: String,
        code: String,
    ): Boolean =
        move(job, active) {
            status = DownloadJobStatus.Failed.wire
            error = message
            errorCategory = category
            errorCode = code
            nextRetryAt = null
        }

    /** Pushes a pending job's deadline out to at least [atMillis]; never pulls it earlier. */
    fun deferUntil(
        job: DownloadJob,
        atMillis: Long,
    ): Boolean =
        move(job, setOf(DownloadJobStatus.Pending)) {
            nextRetryAt = maxOf(nextRetryAt ?: 0L, atMillis)
        }

    /**
     * After a passed check: a job failed as blocked returns to pending without spending a retry,
     * and a deferred pending job loses its recheck delay so it starts now.
     */
    fun releaseAfterVerification(job: DownloadJob): Boolean =
        when {
            isAwaitingVerification(job) -> {
                job.status = DownloadJobStatus.Pending.wire
                job.clearError()
                true
            }
            job.status == DownloadJobStatus.Pending.wire -> {
                job.nextRetryAt = null
                true
            }
            else -> false
        }

    /** Work a killed process or timed-out service left downloading becomes runnable again. */
    fun recoverInterrupted(job: DownloadJob): Boolean =
        move(job, setOf(DownloadJobStatus.Downloading)) {
            status = DownloadJobStatus.Pending.wire
            nextRetryAt = null
        }

    /**
     * The committed chapter's row, or null when [job] is no longer downloading. A copy, because the
     * repository publishes immutable snapshots.
     */
    fun completed(job: DownloadJob): DownloadJob? =
        job.copy(chapter = job.chapter.copy()).takeIf { copy ->
            move(copy, setOf(DownloadJobStatus.Downloading)) {
                status = DownloadJobStatus.Completed.wire
                clearError()
            }
        }

    private inline fun move(
        job: DownloadJob,
        from: Set<DownloadJobStatus>,
        apply: DownloadJob.() -> Unit,
    ): Boolean {
        // parse() maps unknown legacy strings to Failed; require an exact wire match so a corrupt
        // status is never treated as a legal source state.
        if (from.none { it.wire == job.status }) return false
        job.apply()
        return true
    }

    private fun DownloadJob.clearError() {
        error = null
        errorCategory = null
        errorCode = null
        nextRetryAt = null
    }
}

/** The error fields a failed attempt records on its job. */
data class DownloadJobFailure(
    val message: String?,
    val category: String,
    val code: String,
)

/** A job's phase derived from status plus retry deadline; see [DownloadJobTransitions.phase]. */
sealed interface DownloadJobPhase {
    data object Runnable : DownloadJobPhase

    data class WaitingForRetry(
        val atMillis: Long,
    ) : DownloadJobPhase

    data object Downloading : DownloadJobPhase

    data object Paused : DownloadJobPhase

    data object Completed : DownloadJobPhase

    data object Cancelled : DownloadJobPhase

    /** Failed because the source needs interactive verification; resumes after a passed check. */
    data object AwaitingVerification : DownloadJobPhase

    data object Failed : DownloadJobPhase
}
