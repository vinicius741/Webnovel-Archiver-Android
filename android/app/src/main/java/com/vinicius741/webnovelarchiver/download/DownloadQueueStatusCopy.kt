package com.vinicius741.webnovelarchiver.download

import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus

/** Plain-language status lines for the Downloads screen; raw categories stay behind "Error details". */
object DownloadQueueStatusCopy {
    /** "4 novels · 128 of 420 chapters". */
    fun summaryLine(plan: QueueScreenPlan): String {
        val novels = if (plan.storyCount == 1) "1 novel" else "${plan.storyCount} novels"
        return "$novels · ${plan.counts.completed} of ${plan.counts.total} chapters"
    }

    /** The novel card's status line, in plain words. [pacing] comes from [DownloadPacingUiPlanning.storyStatus]. */
    fun storyStatusLine(
        story: QueueStoryPlan,
        pacing: DownloadPacingUiStatus?,
    ): String {
        if (story.blocked) return "Waiting for verification"
        val seconds = pacing?.remainingSeconds?.let(DownloadPacingUiPlanning::formatDuration)
        if (seconds != null && pacing?.kind == DownloadPacingUiKind.RATE_LIMIT_WAIT) {
            return "Site asked to slow down · resuming in $seconds"
        }
        if (seconds != null && pacing?.kind == DownloadPacingUiKind.RETRY_WAIT) return "Retrying in $seconds"
        return when (story.section) {
            QueueSection.DOWNLOADING ->
                if (seconds != null && pacing?.kind == DownloadPacingUiKind.CONFIGURED_WAIT) {
                    "Next chapter in $seconds"
                } else {
                    story.jobs
                        .firstOrNull { it.status == DownloadJobStatus.Downloading.wire }
                        ?.let { "Downloading chapter ${it.chapterIndex + 1}" } ?: "Downloading"
                }
            QueueSection.UP_NEXT -> story.behindTitle?.let { "After $it" } ?: "Starting soon"
            QueueSection.PAUSED -> "Paused"
            QueueSection.FINISHED ->
                when {
                    story.counts.failed > 0 -> "${story.counts.failed} failed"
                    story.counts.cancelled > 0 -> "${story.counts.cancelled} cancelled"
                    else -> "Done"
                }
        }
    }

    /** A chapter row's status line. [waitSeconds] is the configured-delay countdown for a claimed job. */
    fun chapterStatusLabel(
        job: DownloadJob,
        blocked: Boolean,
        waitSeconds: Long?,
        nowMillis: Long,
    ): String {
        val retryIn =
            job.nextRetryAt
                ?.takeIf { it > nowMillis }
                ?.let { DownloadPacingUiPlanning.formatDuration(DownloadPacingUiPlanning.remainingSeconds(it, nowMillis)) }
        return when (DownloadJobStatus.parse(job.status)) {
            DownloadJobStatus.Pending ->
                when {
                    blocked -> "Waiting for verification"
                    retryIn != null && job.errorCategory == "rate_limit" -> "Site asked to slow down · retry in $retryIn"
                    retryIn != null -> "Retry in $retryIn"
                    else -> "Queued"
                }
            DownloadJobStatus.Downloading ->
                waitSeconds?.let { "Next request in ${DownloadPacingUiPlanning.formatDuration(it)}" } ?: "Downloading"
            DownloadJobStatus.Paused -> "Paused"
            DownloadJobStatus.Completed -> "Downloaded"
            DownloadJobStatus.Cancelled -> "Cancelled"
            DownloadJobStatus.Failed ->
                if (DownloadQueueSectionPlanning.isBlockedFailure(job)) "Waiting for verification" else "Failed · ${failureReason(job)}"
        }
    }

    /** Error categories in the user's words; the raw message and code stay behind "Error details". */
    fun failureReason(job: DownloadJob): String =
        when (job.errorCategory) {
            "source_blocked" -> "blocked by Cloudflare"
            "rate_limit" -> "the site is limiting requests"
            "network" -> "network problem"
            "parse" -> "couldn't read the chapter"
            "missing_story" -> "novel no longer in the library"
            "missing_provider" -> "site not supported"
            "invalid_chapter" -> "chapter has no link"
            else -> job.error?.takeIf { it.isNotBlank() } ?: "unknown error"
        }
}
