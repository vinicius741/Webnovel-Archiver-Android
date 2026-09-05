package com.vinicius741.webnovelarchiver.feature.downloads

import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.download.DownloadManagerPlanning
import com.vinicius741.webnovelarchiver.download.DownloadPacingUiPlanning
import com.vinicius741.webnovelarchiver.feature.browser.showSourceAccessBlockedDialog
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.formatRelativeTime
import com.vinicius741.webnovelarchiver.ui.jobStatusDot
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.statusColor

internal fun ScreenHost.addQueueJobRow(
    job: DownloadJob,
    waitingForDelay: com.vinicius741.webnovelarchiver.download.DownloadPacingUiStatus? = null,
    onStatusLabel: (android.widget.TextView) -> Unit = {},
): View {
    val row =
        LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(Space.XS), dp(Space.SM), dp(Space.XS), dp(Space.SM))
        }
    row.addView(jobStatusDot(job.status))
    row.addView(
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                makeText(app, "${job.chapterIndex + 1}. ${job.chapter.title}", Type.TITLE_SMALL, ThemeManager.colors.onSurface).apply {
                    maxLines =
                        2
                    ; ellipsize = TextUtils.TruncateAt.END
                },
            )
            val statusLabel =
                makeText(
                    app,
                    queueJobStatusLabel(job, waitingForDelay),
                    Type.LABEL_SMALL,
                    statusColor(job.status),
                ).apply {
                    setPadding(0, dp(2), 0, 0)
                }
            addView(statusLabel)
            onStatusLabel(statusLabel)
            job.errorCategory?.let {
                addView(
                    makeText(
                        app,
                        "Category: $it${job.errorCode?.let { code ->
                            " ($code)"
                        } ?: ""}",
                        Type.LABEL_SMALL,
                        ThemeManager.colors.onSurfaceVariant,
                    ).apply { setPadding(0, dp(2), 0, 0) },
                )
            }
        },
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
    )
    addChapterActions(row, job)
    return row
}

/**
 * The job row's status line, extracted so countdown ticks can recompute it in place (R23) instead
 * of rebuilding and re-diffing the whole queue presentation every second.
 */
internal fun queueJobStatusLabel(
    job: DownloadJob,
    waitingForDelay: com.vinicius741.webnovelarchiver.download.DownloadPacingUiStatus?,
): String {
    val retryDetail = if (job.retryCount > 0) " • retries ${job.retryCount}/${job.maxRetries}" else ""
    val nextRetry = job.nextRetryAt?.let { " • retry in ${formatRelativeTime(it)}" }.orEmpty()
    val statusLabel =
        waitingForDelay?.let {
            "Waiting for delay • next request in ${DownloadPacingUiPlanning.formatDuration(requireNotNull(it.remainingSeconds))}"
        } ?: job.status
    return "$statusLabel${job.error?.let { " • $it" } ?: ""}$retryDetail$nextRetry"
}
