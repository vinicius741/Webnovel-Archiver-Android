package com.vinicius741.webnovelarchiver.download

import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus

/** Queue evidence that pending work is being held by an open manual-verification circuit. */
data class BlockedQueueEvidence(
    val pendingCount: Int,
    val sampleUrl: String?,
)

/**
 * Detects pending jobs whose source's manual-verification circuit is open.
 *
 * When the circuit opens mid-download, in-flight jobs fail as `source_blocked` and the solve flow
 * has many triggers. But the queue can reach an all-pending state with the circuit still open —
 * the user retried the failed jobs, the process restarted into persisted circuit state, or the
 * circuit opened outside the download flow (sync, cover fetch). The process loop then re-defers
 * those jobs every [DownloadScheduler.BLOCKED_SOURCE_RECHECK_MILLIS] without any network attempt,
 * so nothing ever fails as `source_blocked` again and every existing verification prompt stays
 * silent. This planning gives the queue screen and the foreground notification a trigger for
 * exactly that state, mirroring the loop's own blocked-source computation.
 */
object DownloadVerificationPlanning {
    fun blockedPendingEvidence(
        jobs: List<DownloadJob>,
        isSourceBlocked: (String) -> Boolean,
        providerNameForJob: (DownloadJob) -> String?,
    ): BlockedQueueEvidence {
        val blockedSources =
            jobs
                .asSequence()
                .filter { it.status == DownloadJobStatus.Pending.wire }
                .mapNotNull(providerNameForJob)
                .toSet()
                .filter(isSourceBlocked)
                .toSet()
        if (blockedSources.isEmpty()) return BlockedQueueEvidence(0, null)
        val pendingBlocked =
            jobs.filter {
                it.status == DownloadJobStatus.Pending.wire && providerNameForJob(it) in blockedSources
            }
        return BlockedQueueEvidence(pendingBlocked.size, pendingBlocked.firstOrNull()?.chapter?.url)
    }
}
