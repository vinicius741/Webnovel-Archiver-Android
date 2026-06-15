package com.vinicius741.webnovelarchiver

import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.core.DownloadJob
import com.vinicius741.webnovelarchiver.download.DownloadForegroundService
import com.vinicius741.webnovelarchiver.ui.*

internal fun ScreenHost.showQueue() {
    screen(title = "Download Manager", onBack = { showLibrary() }) {
        val queue = storage.getQueue()
        val stats = queue.groupingBy { it.status }.eachCount()
        if (queue.isNotEmpty()) {
            // Q1: one headline summary instead of six stat pills dominated by zeroes, plus compact
            // count chips that only appear for nonzero buckets.
            val done = stats["completed"] ?: 0
            row {
                addView(makeProgressSummary(context, done, queue.size))
            }
            flow {
                addView(makeCountChip(context, "active", stats["downloading"] ?: 0, ThemeManager.colors.primary))
                addView(makeCountChip(context, "queued", stats["pending"] ?: 0, ThemeManager.colors.onSurfaceVariant))
                addView(makeCountChip(context, "paused", stats["paused"] ?: 0, ThemeManager.colors.secondary))
                addView(makeCountChip(context, "failed", stats["failed"] ?: 0, ThemeManager.colors.error))
                addView(makeCountChip(context, "cancelled", stats["cancelled"] ?: 0, ThemeManager.colors.error))
            }
        }
        // Q2: group global actions by purpose; isolate the destructive Cancel All.
        flow {
            button("Resume All", Btn.TONAL, R.drawable.wna_play) { downloadEngine.resumeAll(); DownloadForegroundService.start(app); showQueue() }
            button("Pause All", Btn.TEXT, R.drawable.wna_pause) { downloadEngine.pauseAll(); showQueue() }
            button("Retry Failed", Btn.TEXT, R.drawable.wna_refresh) { downloadEngine.retryFailed(); DownloadForegroundService.start(app); showQueue() }
            button("Clear Done", Btn.TEXT, R.drawable.wna_check) { downloadEngine.clearFinished(); showQueue() }
        }
        flow {
            button("Cancel All", Btn.ERROR, R.drawable.wna_close) { confirm("Cancel all active and pending downloads?", confirmLabel = "Cancel All") { downloadEngine.cancelAll(); showQueue() } }
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
                    // Q3: scannable progress summary + count chips for nonzero buckets, instead of one run-on line.
                    addView(makeProgressSummary(context, summary["completed"] ?: 0, jobs.size).apply { setPadding(0, dp(Space.XS + 2), 0, dp(Space.XS + 2)) })
                    flow {
                        addView(makeCountChip(context, "active", summary["downloading"] ?: 0, ThemeManager.colors.primary))
                        addView(makeCountChip(context, "queued", summary["pending"] ?: 0, ThemeManager.colors.onSurfaceVariant))
                        addView(makeCountChip(context, "paused", summary["paused"] ?: 0, ThemeManager.colors.secondary))
                        addView(makeCountChip(context, "failed", summary["failed"] ?: 0, ThemeManager.colors.error))
                    }
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
    // Q5: refresh every 30s so the "retry in Xm" countdown and status pills don't go stale while the
    // screen stays open. Tag the root view so the refresher can tell if the user has navigated away
    // (a different screen replaces this view and its tag), in which case it stops itself.
    if (frame.childCount > 0) {
        val root = frame.getChildAt(0)
        root.tag = QUEUE_TAG
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({
            if ((root.tag as? String) == QUEUE_TAG && root.parent === frame) {
                showQueue()
            }
        }, QUEUE_REFRESH_MS)
    }
}

private const val QUEUE_TAG = "queue-screen"
private const val QUEUE_REFRESH_MS = 30_000L

internal fun ScreenHost.addQueueJobCard(container: LinearLayout, job: DownloadJob) {
    container.addView(container.card {
        row {
            addView(jobStatusDot(job.status))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(makeText(context, "${job.chapterIndex + 1}. ${job.chapter.title}", Type.TITLE_SMALL, ThemeManager.colors.onSurface).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                val retryDetail = if (job.retryCount > 0) " • retries ${job.retryCount}/${job.maxRetries}" else ""
                val nextRetry = job.nextRetryAt?.let { " • retry in ${formatRelativeTime(it)}" }.orEmpty()
                addView(makeText(context, "${job.status}${job.error?.let { " • $it" } ?: ""}$retryDetail$nextRetry", Type.LABEL_SMALL, statusColor(job.status)).apply { setPadding(0, dp(2), 0, 0) })
                job.errorCategory?.let {
                    addView(makeText(context, "Category: $it${job.errorCode?.let { code -> " ($code)" } ?: ""}", Type.LABEL_SMALL, ThemeManager.colors.onSurfaceVariant).apply { setPadding(0, dp(2), 0, 0) })
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            // Q4: collapse the per-job Pause/Resume/Cancel/Retry/Remove button wall into a single
            // overflow (⋮) so a 9-job story shows ~9 status rows instead of ~27 buttons.
            addView(ImageView(context).apply {
                setImageDrawable(context.tintedIcon(R.drawable.wna_more_vert, ThemeManager.colors.onSurfaceVariant))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(Space.SM + 2), dp(Space.SM + 2), dp(Space.SM + 2), dp(Space.SM + 2))
                background = selectableRipple(ThemeManager.colors.onSurface)
                isClickable = true
                isFocusable = true
                setOnClickListener { showJobActions(job) }
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            })
        }
    })
}

/** Per-job overflow menu: the actions that previously sat as a 3–5 button flow on every card. */
private fun ScreenHost.showJobActions(job: DownloadJob) {
    val options = mutableListOf<Pair<String, () -> Unit>>()
    if (job.status == "pending" || job.status == "downloading") {
        options += "Pause" to { downloadEngine.pauseJob(job.id); showQueue() }
    }
    if (job.status == "paused") {
        options += "Resume" to { downloadEngine.resumeJob(job.id); DownloadForegroundService.start(app); showQueue() }
    }
    if (job.status == "pending" || job.status == "downloading" || job.status == "paused") {
        options += "Cancel" to { downloadEngine.cancelJob(job.id); showQueue() }
    }
    if (job.status == "failed" || job.status == "cancelled") {
        options += "Retry" to { downloadEngine.retryJob(job.id); DownloadForegroundService.start(app); showQueue() }
    }
    if (job.status in setOf("completed", "failed", "cancelled")) {
        options += "Remove" to { downloadEngine.removeJob(job.id); showQueue() }
    }
    if (options.isEmpty()) return
    showStyledOptionsDialog("${job.chapterIndex + 1}. ${job.chapter.title}", options)
}
