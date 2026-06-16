package com.vinicius741.webnovelarchiver.core

/**
 * Pure planning functions for the live download feedback shown on the Details (View Chapter)
 * screen (the native counterpart of the RN `useDownloadProgress` hook). All derivation happens
 * here so it is unit-testable without touching Android views or the queue file.
 *
 * The Details screen re-reads the queue on a periodic refresh tick (see `showDetails`) and calls
 * [summarizeStoryDownload] to decide whether to render the live banner + per-row status, and
 * [chapterJobStatuses] to drive the per-chapter spinner/dot. This module owns no state.
 */
object DownloadDetailsPlanning {

    /**
     * Live, story-scoped download summary derived from that story's queue jobs. [progress] is the
     * fraction of jobs that have reached a terminal success state (0f–1f). [headline] is built by
     * [DownloadDetailsPlanning.headline].
     */
    data class StoryDownloadSummary(
        val isActive: Boolean,
        val isPaused: Boolean,
        val isFinished: Boolean,
        val completed: Int,
        val failed: Int,
        val cancelled: Int,
        val pending: Int,
        val downloading: Int,
        val total: Int,
        val activeTitle: String?,
    ) {
        /** Terminal-success fraction; 0 when there is no work. */
        val progress: Float get() = if (total > 0) completed.toFloat() / total else 0f
    }

    /**
     * Reduces a story's queue jobs to a [StoryDownloadSummary]. Mirrors RN `useDownloadProgress`:
     *   - active (downloading or pending) ⇒ [isActive]
     *   - only paused, no active ⇒ [isPaused]
     *   - no active and no paused ⇒ [isFinished]
     * [activeTitle] is the chapter title of the first `downloading` job, if any, for the live
     * "Downloading: <title>" headline.
     */
    fun summarizeStoryDownload(jobsForStory: List<DownloadJob>): StoryDownloadSummary {
        val downloading = jobsForStory.count { it.status == DownloadJobStatus.Downloading.wire }
        val pending = jobsForStory.count { it.status == DownloadJobStatus.Pending.wire }
        val paused = jobsForStory.count { it.status == DownloadJobStatus.Paused.wire }
        val completed = jobsForStory.count { it.status == DownloadJobStatus.Completed.wire }
        val failed = jobsForStory.count { it.status == DownloadJobStatus.Failed.wire }
        val cancelled = jobsForStory.count { it.status == DownloadJobStatus.Cancelled.wire }
        val total = jobsForStory.size
        val active = downloading + pending
        val activeTitle = jobsForStory.firstOrNull { it.status == DownloadJobStatus.Downloading.wire }?.chapter?.title
        return StoryDownloadSummary(
            isActive = active > 0,
            isPaused = active == 0 && paused > 0,
            isFinished = active == 0 && paused == 0 && total > 0,
            completed = completed,
            failed = failed,
            cancelled = cancelled,
            pending = pending,
            downloading = downloading,
            total = total,
            activeTitle = activeTitle,
        )
    }

    /**
     * Builds the human-readable status line for the live banner, mirroring RN `useDownloadProgress`:
     *   active + downloading job → "Downloading: <title> (completed/total)"
     *   active but none downloading yet → "Queued (completed/total)"
     *   paused → "Paused (completed/total)"
     *   finished with failures → "Finished (X/total downloaded, Y failed[, Z cancelled])"
     *   finished all-complete → "Download Complete"
     * Empty summary → "".
     */
    fun headline(summary: StoryDownloadSummary): String {
        if (summary.total == 0) return ""
        val ratio = "${summary.completed}/${summary.total}"
        return when {
            summary.isActive -> {
                val title = summary.activeTitle?.let { sanitize(it) }
                if (title != null) "Downloading: $title ($ratio)" else "Queued ($ratio)"
            }
            summary.isPaused -> "Paused ($ratio)"
            summary.isFinished -> {
                if (summary.failed == 0 && summary.cancelled == 0) {
                    "Download Complete"
                } else {
                    val parts = mutableListOf("$ratio downloaded")
                    if (summary.failed > 0) parts += "${summary.failed} failed"
                    if (summary.cancelled > 0) parts += "${summary.cancelled} cancelled"
                    "Finished (${parts.joinToString(", ")})"
                }
            }
            else -> ""
        }
    }

    /**
     * Maps the queue to per-chapter live status, keyed by chapter id. Only non-completed job
     * states are surfaced for a row (the story's `downloaded` flag already marks completed rows),
     * so the adapter only needs to render feedback for in-flight work.
     */
    fun chapterJobStatuses(jobs: List<DownloadJob>): Map<String, DownloadJobStatus> =
        jobs.mapNotNull { job ->
            val status = DownloadJobStatus.parse(job.status)
            if (status == DownloadJobStatus.Completed) null else job.chapter.id to status
        }.toMap()

    /** Trims/compacts a chapter title for the headline so very long titles don't overflow the bar. */
    private fun sanitize(title: String): String {
        val oneLine = title.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= 48) oneLine else oneLine.take(45).trimEnd() + "…"
    }
}
