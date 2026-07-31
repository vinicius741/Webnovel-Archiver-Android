package com.vinicius741.webnovelarchiver.download

import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus

enum class DownloadPacingUiKind {
    CONFIGURED_WAIT,
    RETRY_WAIT,
    RATE_LIMIT_WAIT,
    QUEUED_BEHIND,
}

data class DownloadPacingUiStatus(
    val kind: DownloadPacingUiKind,
    val providerName: String,
    val remainingSeconds: Long? = null,
    val chapterTitle: String? = null,
)

/**
 * Pure presentation planning for transient download pacing. The timestamp remains the source of
 * truth; screens only recompute the displayed seconds and never persist countdown ticks.
 */
object DownloadPacingUiPlanning {
    /**
     * Jobs which the engine has claimed but has not started fetching yet because it is honoring
     * the configured per-source delay. The durable queue continues to call these jobs
     * `downloading` so they remain resumable across process death; this transient projection lets
     * the UI describe the more precise state without changing the persisted queue format.
     */
    fun waitingJobs(
        snapshots: Collection<DownloadPacingSnapshot>,
        jobs: List<DownloadJob>,
        nowMillis: Long,
    ): Map<String, DownloadPacingUiStatus> =
        snapshots
            .asSequence()
            .filter { it.nextRequestAtMillis > nowMillis }
            .mapNotNull { snapshot ->
                val job = jobs.firstOrNull { it.id == snapshot.jobId }
                job
                    ?.takeIf { it.status == DownloadJobStatus.Downloading.wire }
                    ?.let {
                        snapshot.jobId to
                            DownloadPacingUiStatus(
                                kind = DownloadPacingUiKind.CONFIGURED_WAIT,
                                providerName = snapshot.providerName,
                                remainingSeconds = remainingSeconds(snapshot.nextRequestAtMillis, nowMillis),
                                chapterTitle = snapshot.chapterTitle,
                            )
                    }
            }.toMap()

    fun storyStatus(
        storyId: String,
        providerName: String?,
        storyJobs: List<DownloadJob>,
        snapshots: Collection<DownloadPacingSnapshot>,
        nowMillis: Long,
        allJobs: List<DownloadJob> = storyJobs,
    ): DownloadPacingUiStatus? {
        val retryJob =
            storyJobs
                .asSequence()
                .filter { it.storyId == storyId }
                .filter { (it.nextRetryAt ?: 0L) > nowMillis }
                .minByOrNull { it.nextRetryAt ?: Long.MAX_VALUE }
        if (retryJob != null) {
            val retryAt = requireNotNull(retryJob.nextRetryAt)
            return DownloadPacingUiStatus(
                kind =
                    if (retryJob.errorCategory == "rate_limit") {
                        DownloadPacingUiKind.RATE_LIMIT_WAIT
                    } else {
                        DownloadPacingUiKind.RETRY_WAIT
                    },
                providerName = providerName.orEmpty(),
                remainingSeconds = remainingSeconds(retryAt, nowMillis),
                chapterTitle = retryJob.chapter.title,
            )
        }

        val active = waitingJobs(snapshots, allJobs, nowMillis)
        active
            .filter { (jobId, _) ->
                allJobs.firstOrNull { it.id == jobId }?.storyId == storyId
            }.minByOrNull { requireNotNull(it.value.remainingSeconds) }
            ?.let { return it.value }

        if (
            providerName != null &&
            storyJobs.any { it.storyId == storyId && it.status == DownloadJobStatus.Pending.wire } &&
            active.any { (jobId, status) ->
                status.providerName == providerName && allJobs.firstOrNull { it.id == jobId }?.storyId != storyId
            }
        ) {
            return DownloadPacingUiStatus(
                kind = DownloadPacingUiKind.QUEUED_BEHIND,
                providerName = providerName,
            )
        }
        return null
    }

    fun storyHeadline(status: DownloadPacingUiStatus): String =
        when (status.kind) {
            DownloadPacingUiKind.CONFIGURED_WAIT ->
                "Waiting for delay · next request starts in ${formatDuration(requireNotNull(status.remainingSeconds))}"
            DownloadPacingUiKind.RETRY_WAIT ->
                "Retrying in ${formatDuration(requireNotNull(status.remainingSeconds))}"
            DownloadPacingUiKind.RATE_LIMIT_WAIT ->
                "Rate limited · Retrying in ${formatDuration(requireNotNull(status.remainingSeconds))}"
            DownloadPacingUiKind.QUEUED_BEHIND ->
                "Queued behind other ${status.providerName} downloads"
        }

    /**
     * Configured-delay countdowns live on the affected chapter row, where the wait is actionable
     * and unambiguous. Story headers still surface retry, rate-limit, and cross-story queue states.
     */
    fun groupHeadline(status: DownloadPacingUiStatus?): String? =
        when (status?.kind) {
            null, DownloadPacingUiKind.CONFIGURED_WAIT -> null
            else -> storyHeadline(status)
        }

    internal fun remainingSeconds(
        targetMillis: Long,
        nowMillis: Long,
    ): Long = ((targetMillis - nowMillis).coerceAtLeast(0L) + 999L) / 1_000L

    internal fun formatDuration(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0L)
        if (safe < 60L) return if (safe == 1L) "1 second" else "$safe seconds"
        val minutes = safe / 60L
        val remainder = safe % 60L
        return if (remainder == 0L) "${minutes}m" else "${minutes}m ${remainder}s"
    }
}
