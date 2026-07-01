package com.vinicius741.webnovelarchiver.download

import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus

data class QueueStatusCounts(
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
        fun from(jobs: Iterable<DownloadJob>): QueueStatusCounts {
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
            return QueueStatusCounts(downloading, pending, paused, completed, failed, cancelled)
        }
    }
}

/** Per-chapter and per-story actions surfaced as inline icons in the download manager. */
enum class QueueAction { PAUSE, RESUME, RETRY, CANCEL, REMOVE }

/** Global (whole-queue) actions surfaced as app-bar icons in the download manager. */
enum class GlobalQueueAction { RESUME_ALL, PAUSE_ALL, RETRY_ALL, CANCEL_ALL, CLEAR_FINISHED }

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

    /** Story-header action group. While a story has active or paused work it shows the in-progress
     *  group (pause-or-resume/cancel/retry-as-relevant); a story with only terminal failures shows a
     *  single retry; an all-completed story shows nothing. */
    fun storyHeaderActions(counts: QueueStatusCounts): List<QueueAction> {
        if (counts.hasActive || counts.hasPaused) {
            return buildList {
                if (counts.hasActive) {
                    add(QueueAction.PAUSE)
                } else if (counts.hasPaused) {
                    add(QueueAction.RESUME)
                }
                add(QueueAction.CANCEL)
                if (counts.hasFailed) add(QueueAction.RETRY)
            }
        }
        if (counts.hasFailed || counts.hasCancelled) return listOf(QueueAction.RETRY)
        return emptyList()
    }

    fun globalActions(counts: QueueStatusCounts): List<GlobalQueueAction> =
        buildList {
            if (counts.hasActive) {
                add(GlobalQueueAction.PAUSE_ALL)
            } else if (counts.hasPaused) {
                add(GlobalQueueAction.RESUME_ALL)
            }
            if (counts.hasFailed) add(GlobalQueueAction.RETRY_ALL)
            if (counts.hasActive || counts.hasPaused) add(GlobalQueueAction.CANCEL_ALL)
            if (!counts.hasActive && !counts.hasPaused && counts.completed + counts.failed + counts.cancelled > 0) {
                add(GlobalQueueAction.CLEAR_FINISHED)
            }
        }

    /** Single-line status summary for a story, e.g. "12/20 chapters • 3 downloading • 2 queued • 1 failed".
     *  Mirrors the legacy RN getSubtitleText: always leads with "X/Y chapters" then appends each
     *  non-zero status segment joined by " • ". */
    fun storySubtitle(counts: QueueStatusCounts): String {
        val segments = mutableListOf<String>()
        counts.downloading.takeIf { it > 0 }?.let { segments += "$it downloading" }
        counts.pending.takeIf { it > 0 }?.let { segments += "$it queued" }
        counts.paused.takeIf { it > 0 }?.let { segments += "$it paused" }
        counts.failed.takeIf { it > 0 }?.let { segments += "$it failed" }
        counts.cancelled.takeIf { it > 0 }?.let { segments += "$it cancelled" }
        val statusLine = segments.joinToString(" • ")
        return "${counts.completed}/${counts.total} chapters" + if (statusLine.isNotEmpty()) " • $statusLine" else ""
    }
}
