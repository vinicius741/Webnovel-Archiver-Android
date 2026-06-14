package com.vinicius741.webnovelarchiver

import android.text.TextUtils
import android.view.ViewGroup
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.core.DownloadJob
import com.vinicius741.webnovelarchiver.download.DownloadForegroundService
import com.vinicius741.webnovelarchiver.ui.*

internal fun ScreenHost.showQueue() {
    screen(title = "Download Manager", onBack = { showLibrary() }) {
        val queue = storage.getQueue()
        val stats = queue.groupingBy { it.status }.eachCount()
        if (queue.isNotEmpty()) {
            flow {
                addView(makeStatPill(context, "Total", queue.size.toString()))
                addView(makeStatPill(context, "Active", "${stats["downloading"] ?: 0}"))
                addView(makeStatPill(context, "Queued", "${stats["pending"] ?: 0}"))
                addView(makeStatPill(context, "Done", "${stats["completed"] ?: 0}"))
                addView(makeStatPill(context, "Failed", "${stats["failed"] ?: 0}"))
                addView(makeStatPill(context, "Paused", "${stats["paused"] ?: 0}"))
            }
        }
        flow {
            button("Resume", Btn.TONAL, R.drawable.wna_play) { downloadEngine.resumeAll(); DownloadForegroundService.start(app); showQueue() }
            button("Pause", Btn.TEXT, R.drawable.wna_pause) { downloadEngine.pauseAll(); showQueue() }
            button("Retry Failed", Btn.TEXT, R.drawable.wna_refresh) { downloadEngine.retryFailed(); DownloadForegroundService.start(app); showQueue() }
            button("Clear Done", Btn.TEXT, R.drawable.wna_check) { downloadEngine.clearFinished(); showQueue() }
            button("Cancel All", Btn.ERROR, R.drawable.wna_close) { confirm("Cancel all active and pending downloads?") { downloadEngine.cancelAll(); showQueue() } }
        }
        if (queue.isEmpty()) {
            addView(makeEmptyState(context, "No active downloads. Downloaded chapters will appear here.", R.drawable.wna_download))
            return@screen
        }
        val groupList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        queue.groupBy { it.storyId }
            .values
            .sortedByDescending { group -> group.maxOfOrNull { it.addedAt } ?: 0L }
            .forEach { jobs ->
                val storyTitle = jobs.firstOrNull()?.storyTitle ?: "Unknown Story"
                val summary = jobs.groupingBy { it.status }.eachCount()
                groupList.addView(groupList.card {
                    addView(makeText(context, storyTitle, Type.TITLE_MEDIUM, ThemeManager.colors.onSurface))
                    addView(makeText(context,
                        "${summary["completed"] ?: 0}/${jobs.size} chapters • ${summary["pending"] ?: 0} queued • ${summary["downloading"] ?: 0} active • ${summary["paused"] ?: 0} paused • ${summary["failed"] ?: 0} failed • ${summary["cancelled"] ?: 0} cancelled",
                        Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant).apply { setPadding(0, dp(4), 0, dp(6)) }
                    )
                    if (jobs.any { it.status == "pending" || it.status == "downloading" } ||
                        jobs.any { it.status == "paused" } ||
                        jobs.any { it.status == "failed" || it.status == "cancelled" }
                    ) {
                        flow {
                            if (jobs.any { it.status == "pending" || it.status == "downloading" }) {
                                button("Pause Story", Btn.TEXT, R.drawable.wna_pause) {
                                    jobs.filter { it.status == "pending" || it.status == "downloading" }.forEach { downloadEngine.pauseJob(it.id) }
                                    showQueue()
                                }
                            }
                            if (jobs.any { it.status == "paused" }) {
                                button("Resume Story", Btn.TONAL, R.drawable.wna_play) {
                                    jobs.filter { it.status == "paused" }.forEach { downloadEngine.resumeJob(it.id) }
                                    DownloadForegroundService.start(app)
                                    showQueue()
                                }
                            }
                            if (jobs.any { it.status == "failed" || it.status == "cancelled" }) {
                                button("Retry Story", Btn.TEXT, R.drawable.wna_refresh) {
                                    downloadEngine.retryFailedForStory(jobs.first().storyId)
                                    DownloadForegroundService.start(app)
                                    showQueue()
                                }
                            }
                        }
                    }
                })
                jobs.sortedBy { it.chapterIndex }.forEach { job ->
                    addQueueJobCard(groupList, job)
                }
            }
        addView(scroll(groupList), verticalFill())
    }
}

internal fun ScreenHost.addQueueJobCard(container: LinearLayout, job: DownloadJob) {
    container.addView(container.card {
        row {
            addView(jobStatusDot(job.status))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(makeText(context, "${job.chapterIndex + 1}. ${job.chapter.title}", Type.TITLE_SMALL, ThemeManager.colors.onSurface).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                val retryDetail = if (job.retryCount > 0) " • retries ${job.retryCount}/${job.maxRetries}" else ""
                val nextRetry = job.nextRetryAt?.let { " • next retry ${formatRelativeTime(it)}" }.orEmpty()
                addView(makeText(context, "${job.status}${job.error?.let { " • $it" } ?: ""}$retryDetail$nextRetry", Type.LABEL_SMALL, statusColor(job.status)).apply { setPadding(0, dp(2), 0, 0) })
                job.errorCategory?.let {
                    addView(makeText(context, "Category: $it${job.errorCode?.let { code -> " ($code)" } ?: ""}", Type.LABEL_SMALL, ThemeManager.colors.onSurfaceVariant).apply { setPadding(0, dp(2), 0, 0) })
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        flow {
            if (job.status == "pending" || job.status == "downloading") {
                button("Pause", Btn.TEXT, R.drawable.wna_pause) { downloadEngine.pauseJob(job.id); showQueue() }
            }
            if (job.status == "paused") {
                button("Resume", Btn.TONAL, R.drawable.wna_play) { downloadEngine.resumeJob(job.id); DownloadForegroundService.start(app); showQueue() }
            }
            if (job.status == "pending" || job.status == "downloading" || job.status == "paused") {
                button("Cancel", Btn.TEXT, R.drawable.wna_close) { downloadEngine.cancelJob(job.id); showQueue() }
            }
            if (job.status == "failed" || job.status == "cancelled") {
                button("Retry", Btn.TONAL, R.drawable.wna_refresh) { downloadEngine.retryJob(job.id); DownloadForegroundService.start(app); showQueue() }
            }
            if (job.status in setOf("completed", "failed", "cancelled")) {
                button("Remove", Btn.TEXT, R.drawable.wna_delete) { downloadEngine.removeJob(job.id); showQueue() }
            }
        }
    })
}
