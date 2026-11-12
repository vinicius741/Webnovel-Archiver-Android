package com.vinicius741.webnovelarchiver.feature.downloads

import com.vinicius741.webnovelarchiver.download.DownloadQueueStatusCopy
import com.vinicius741.webnovelarchiver.download.QueueScreenPlan

/** The Downloads summary card's pill: work is moving, everything is paused, or nothing is left. */
internal enum class QueueLiveState { LIVE, PAUSED, DONE }

/** What the Downloads summary card shows: overall percent, chapters left, and the live pill. */
internal data class QueueSummary(
    val percent: Int,
    val fraction: Float,
    val detail: String,
    val state: QueueLiveState,
)

internal object QueueSummaryPlanning {
    /** Percent counts downloaded chapters; failed and cancelled ones are not "left" but not done either. */
    fun summary(plan: QueueScreenPlan): QueueSummary {
        val counts = plan.counts
        val fraction = if (counts.total > 0) counts.completed.toFloat() / counts.total else 0f
        val left = counts.downloading + counts.pending + counts.paused
        val active = plan.downloading.size
        val detail =
            buildList {
                add(if (left == 1) "1 chapter left" else "$left chapters left")
                if (active > 0) add("$active active")
                if (counts.failed > 0) add("${counts.failed} failed")
            }.joinToString(" · ")
        val state =
            when {
                counts.hasActive -> QueueLiveState.LIVE
                counts.hasPaused -> QueueLiveState.PAUSED
                else -> QueueLiveState.DONE
            }
        return QueueSummary((fraction * PERCENT).toInt(), fraction, detail, state)
    }

    /** The app bar's line: "5 novels · 16 of 56 chapters", or that the queue is empty. */
    fun subtitle(plan: QueueScreenPlan): String =
        if (plan.storyCount == 0) "Nothing in the queue" else DownloadQueueStatusCopy.summaryLine(plan)

    private const val PERCENT = 100
}
