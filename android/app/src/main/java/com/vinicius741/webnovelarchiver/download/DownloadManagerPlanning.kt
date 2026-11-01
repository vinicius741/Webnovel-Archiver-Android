package com.vinicius741.webnovelarchiver.download

import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus

/** One canonical reduction of queue lifecycle state used by UI and worker progress projections. */
data class DownloadCounts(
    val downloading: Int = 0,
    val pending: Int = 0,
    val paused: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    val cancelled: Int = 0,
) {
    val active: Int get() = downloading + pending
    val hasActive: Boolean get() = active > 0
    val hasPaused: Boolean get() = paused > 0
    val hasFailed: Boolean get() = failed > 0
    val hasCancelled: Boolean get() = cancelled > 0
    val total: Int get() = downloading + pending + paused + completed + failed + cancelled

    companion object {
        fun from(jobs: Iterable<DownloadJob>): DownloadCounts {
            var downloading = 0
            var pending = 0
            var paused = 0
            var completed = 0
            var failed = 0
            var cancelled = 0
            jobs.forEach { job ->
                when (DownloadJobStatus.parse(job.status)) {
                    DownloadJobStatus.Downloading -> downloading += 1
                    DownloadJobStatus.Pending -> pending += 1
                    DownloadJobStatus.Paused -> paused += 1
                    DownloadJobStatus.Completed -> completed += 1
                    DownloadJobStatus.Failed -> failed += 1
                    DownloadJobStatus.Cancelled -> cancelled += 1
                }
            }
            return DownloadCounts(downloading, pending, paused, completed, failed, cancelled)
        }
    }
}

fun Iterable<DownloadJob>.downloadCounts(): DownloadCounts = DownloadCounts.from(this)

/** Per-chapter and per-story actions offered by the download manager. */
enum class QueueAction { PAUSE, RESUME, RETRY, CANCEL, REMOVE }

/** Global (whole-queue) actions surfaced in the download manager's app bar. */
enum class GlobalQueueAction { RESUME_ALL, PAUSE_ALL, RETRY_ALL, CANCEL_ALL, CLEAR_FINISHED }

/** A fixed toggle slot (null when the queue has no runnable work) plus the overflow menu. */
data class QueueAppBarPlan(
    val toggle: GlobalQueueAction?,
    val overflow: List<GlobalQueueAction>,
)

object DownloadManagerPlanning {
    /** Returns the inline actions for a chapter job. Unknown/legacy statuses yield no actions. */
    fun chapterActions(status: String): List<QueueAction> =
        when (DownloadJobStatus.parse(status)) {
            DownloadJobStatus.Pending -> listOf(QueueAction.PAUSE, QueueAction.CANCEL)
            DownloadJobStatus.Downloading -> listOf(QueueAction.PAUSE)
            DownloadJobStatus.Paused -> listOf(QueueAction.RESUME, QueueAction.CANCEL)
            DownloadJobStatus.Failed, DownloadJobStatus.Cancelled -> listOf(QueueAction.RETRY, QueueAction.REMOVE)
            DownloadJobStatus.Completed -> listOf(QueueAction.REMOVE)
        }.let { actions ->
            // `parse` maps unknown strings to Failed; recover the "no actions for unknown" contract
            // so a status that isn't a real job lifecycle value (e.g. "idle") yields nothing.
            if (DownloadJobStatus.wires.contains(status)) actions else emptyList()
        }

    /** The novel card's one inline action; the rest live in its menu. */
    fun storyPrimaryAction(
        section: QueueSection,
        counts: DownloadCounts,
    ): QueueAction =
        when (section) {
            QueueSection.DOWNLOADING, QueueSection.UP_NEXT -> QueueAction.PAUSE
            QueueSection.PAUSED -> QueueAction.RESUME
            QueueSection.FINISHED -> if (counts.hasFailed || counts.hasCancelled) QueueAction.RETRY else QueueAction.REMOVE
        }

    /**
     * App bar: a Pause all / Resume all toggle and an overflow menu. [retryableFailed] excludes
     * chapters held by a site check, which resume on their own once the check passes.
     */
    fun appBar(
        counts: DownloadCounts,
        retryableFailed: Int,
    ): QueueAppBarPlan =
        QueueAppBarPlan(
            toggle =
                when {
                    counts.hasActive -> GlobalQueueAction.PAUSE_ALL
                    counts.hasPaused -> GlobalQueueAction.RESUME_ALL
                    else -> null
                },
            overflow =
                buildList {
                    if (retryableFailed > 0) add(GlobalQueueAction.RETRY_ALL)
                    if (counts.hasActive || counts.hasPaused) add(GlobalQueueAction.CANCEL_ALL)
                    if (counts.completed + counts.failed + counts.cancelled > 0) add(GlobalQueueAction.CLEAR_FINISHED)
                },
        )
}
