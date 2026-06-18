package com.vinicius741.webnovelarchiver

import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.core.DownloadJob
import com.vinicius741.webnovelarchiver.core.DownloadJobStatus
import com.vinicius741.webnovelarchiver.core.DownloadManagerPlanning
import com.vinicius741.webnovelarchiver.core.GlobalQueueAction
import com.vinicius741.webnovelarchiver.core.QueueAction
import com.vinicius741.webnovelarchiver.core.QueueStatusCounts
import com.vinicius741.webnovelarchiver.core.queueMaxWidth
import com.vinicius741.webnovelarchiver.download.DownloadForegroundService
import com.vinicius741.webnovelarchiver.ui.*

internal fun ScreenHost.showQueue() {
    val queue = storage.getQueue()
    val counts = QueueStatusCounts.from(queue)
    // Re-render on fold/unfold/rotation so the width cap re-centers for the new window.
    rerender = { showQueue() }
    val layout = currentScreenLayout()
    screen(title = "Downloads", onBack = { showLibrary() }, actions = globalAppBarActions(counts)) {
        // Center everything in a width-capped column (920/1080dp by width class) so the queue doesn't
        // stretch edge-to-edge on tablets/the Fold inner display. On phone widths the cap is larger
        // than the screen so it has no effect.
        val centered =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
        val centeredShell =
            MaxWidthFrameLayout(context).apply {
                maxContentWidthDp = queueMaxWidth(layout.widthClass)
                addView(
                    centered,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.CENTER_HORIZONTAL,
                    ),
                )
            }
        if (queue.isNotEmpty()) {
            centered.row {
                addView(makeProgressSummary(context, counts.completed, counts.total))
            }
            centered.flow {
                addView(makeCountChip(context, "active", counts.downloading, ThemeManager.colors.primary))
                addView(makeCountChip(context, "queued", counts.pending, ThemeManager.colors.onSurfaceVariant))
                addView(makeCountChip(context, "paused", counts.paused, ThemeManager.colors.secondary))
                addView(makeCountChip(context, "failed", counts.failed, ThemeManager.colors.error))
                addView(makeCountChip(context, "cancelled", counts.cancelled, ThemeManager.colors.error))
            }
        }
        if (queue.isEmpty()) {
            centered.addView(makeEmptyState(context, "No active downloads. Downloaded chapters will appear here.", R.drawable.wna_download))
            addView(centeredShell, verticalFill())
            return@screen
        }
        val groupList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        queue
            .groupBy { it.storyId }
            .values
            .sortedByDescending { group -> group.maxOfOrNull { it.addedAt } ?: 0L }
            .forEach { jobs -> groupList.addView(addStoryGroup(jobs)) }
        centered.addView(
            scroll(groupList),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        addView(centeredShell, verticalFill())
    }
    // Real-time refresh (formerly a 30s dumb timer). The actual download work runs in the foreground
    // service's DownloadEngine — a *different* instance from this activity's — so onChanged/onProgress
    // only update the notification, never this UI. With no cross-component signal available, we poll
    // storage every second and rebuild only when the queue snapshot actually changed (or a "retry in
    // Xs" countdown is ticking, which mutates the rendered string even when statuses don't). This
    // keeps the screen live while a download is running without burning CPU when it's idle.
    scheduleQueueRefresh(queue)
}

private const val QUEUE_TAG = "queue-screen"
private const val QUEUE_REFRESH_MS = 1_000L

/**
 * Whether the rendered output can still change without any [DownloadJob] field changing: only the
 * "retry in Xm/Xs" countdown (driven by [formatRelativeTime] against the wall clock) does, so a tick
 * is needed while any job is waiting on [DownloadJob.nextRetryAt].
 */
private fun List<DownloadJob>.hasActiveCountdown(): Boolean = any { it.nextRetryAt != null }

private fun ScreenHost.scheduleQueueRefresh(renderedQueue: List<DownloadJob>) {
    if (frame.childCount == 0) return
    val root = frame.getChildAt(0)
    // Tag the root so the refresher can tell if the user has navigated away: a different screen
    // replaces this view (and clears the tag), in which case the refresher stops itself.
    root.tag = QUEUE_TAG
    // Capture scroll across the rebuild so a 1s tick doesn't snap the list back to the top.
    val savedScrollY = findScrollView(root)?.scrollY ?: 0
    val handler = Handler(Looper.getMainLooper())
    handler.postDelayed({
        if ((root.tag as? String) != QUEUE_TAG || root.parent !== frame) return@postDelayed
        val currentQueue = storage.getQueue()
        val changed = !currentQueue.queueEquals(renderedQueue)
        // Even with no status change, the "retry in Xs" countdown drifts each second, so keep
        // ticking while any job has a nextRetryAt — otherwise the pill goes stale.
        if (changed || currentQueue.hasActiveCountdown()) {
            showQueue()
            if (savedScrollY > 0) {
                val target = findScrollView(frame)
                target?.post { target.scrollTo(0, savedScrollY) }
            }
        } else {
            // No change since last render: re-arm without rebuilding, still holding `renderedQueue`.
            scheduleQueueRefresh(renderedQueue)
        }
    }, QUEUE_REFRESH_MS)
}

/**
 * Structural equality on the fields the queue screen renders. [DownloadJob] is a `data class` with
 * `var` fields, so `==` compares them all — sufficient to detect any status/progress/countdown
 * mutation written by the service's process loop. Compared on every 1s tick to decide whether the
 * rebuild can be skipped.
 */
private fun List<DownloadJob>.queueEquals(other: List<DownloadJob>): Boolean {
    if (size != other.size) return false
    for (i in indices) if (this[i] != other[i]) return false
    return true
}

/** Global (whole-queue) actions rendered as the app-bar icon strip, conditional on queue state. */
private fun ScreenHost.globalAppBarActions(counts: QueueStatusCounts): List<AppBarAction> =
    DownloadManagerPlanning.globalActions(counts).map { action ->
        when (action) {
            GlobalQueueAction.RESUME_ALL ->
                AppBarAction(R.drawable.wna_play, "Resume All") {
                    downloadEngine.resumeAll()
                    DownloadForegroundService.start(app)
                    showQueue()
                }
            GlobalQueueAction.PAUSE_ALL ->
                AppBarAction(R.drawable.wna_pause, "Pause All") {
                    downloadEngine.pauseAll()
                    showQueue()
                }
            GlobalQueueAction.RETRY_ALL ->
                AppBarAction(R.drawable.wna_refresh, "Retry Failed", ThemeManager.colors.primary) {
                    downloadEngine.retryFailed()
                    DownloadForegroundService.start(app)
                    showQueue()
                }
            GlobalQueueAction.CANCEL_ALL ->
                AppBarAction(R.drawable.wna_stop, "Cancel All", ThemeManager.colors.error) {
                    confirm("Cancel all active and pending downloads?", confirmLabel = "Cancel All") {
                        downloadEngine.cancelAll()
                        showQueue()
                    }
                }
            GlobalQueueAction.CLEAR_DONE ->
                AppBarAction(R.drawable.wna_check, "Clear Done") {
                    downloadEngine.clearFinished()
                    showQueue()
                }
        }
    }

/** One collapsible card per story: a clickable header (chevron + title/subtitle + story action
 *  icons) followed by its chapter rows when expanded. Novel grouping is unmistakable because the
 *  header and its chapters share a single card. */
private fun ScreenHost.addStoryGroup(jobs: List<DownloadJob>): LinearLayout {
    val storyId = jobs.first().storyId
    val storyTitle = jobs.firstOrNull()?.storyTitle ?: "Unknown Story"
    val counts = QueueStatusCounts.from(jobs)
    val expanded = storyExpandOverride[storyId] ?: (counts.hasActive || counts.hasFailed)
    val card =
        makeCard(app).apply {
            val header =
                row {
                    addView(chevronIcon(expanded))
                    addView(
                        LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(
                                makeText(context, storyTitle, Type.TITLE_MEDIUM, ThemeManager.colors.onSurface).apply {
                                    maxLines = 2
                                    ellipsize = TextUtils.TruncateAt.END
                                },
                            )
                            addView(
                                makeText(
                                    context,
                                    DownloadManagerPlanning.storySubtitle(counts),
                                    Type.BODY_SMALL,
                                    ThemeManager.colors.onSurfaceVariant,
                                ).apply {
                                    setPadding(0, dp(2), 0, 0)
                                },
                            )
                            addView(
                                makeProgressSummary(
                                    context,
                                    counts.completed,
                                    counts.total,
                                ).apply { setPadding(0, dp(Space.XS + 2), 0, 0) },
                            )
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(storyActionGroup(storyId, jobs, counts))
                }
            header.isClickable = true
            header.isFocusable = true
            header.background = selectableRipple(ThemeManager.colors.onSurface)
            header.setOnClickListener {
                storyExpandOverride[storyId] = !expanded
                showQueue()
            }
            if (expanded) {
                divider()
                jobs.sortedBy { it.chapterIndex }.forEachIndexed { index, job ->
                    if (index > 0) divider()
                    addView(addQueueJobRow(job))
                }
            }
        }
    card.layoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = app.dp(Space.LG)
        }
    return card
}

/** Inline story-header action icons (pause/resume/cancel/retry as relevant). Tapping the header
 *  row toggles expand; these icons are clickable children so they consume the touch and don't. */
private fun ScreenHost.storyActionGroup(
    storyId: String,
    jobs: List<DownloadJob>,
    counts: QueueStatusCounts,
): View =
    LinearLayout(app).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        DownloadManagerPlanning.storyHeaderActions(counts).forEach { action -> addView(storyActionButton(action, storyId, jobs)) }
    }

private fun ScreenHost.storyActionButton(
    action: QueueAction,
    storyId: String,
    jobs: List<DownloadJob>,
): View =
    when (action) {
        QueueAction.PAUSE ->
            iconAction(R.drawable.wna_pause, ThemeManager.colors.onSurfaceVariant, "Pause story", 44) {
                jobs.filter { it.status in DownloadJobStatus.activeWires }.forEach { downloadEngine.pauseJob(it.id) }
                showQueue()
            }
        QueueAction.RESUME ->
            iconAction(R.drawable.wna_play, ThemeManager.colors.primary, "Resume story", 44) {
                jobs.filter { it.status == DownloadJobStatus.Paused.wire }.forEach { downloadEngine.resumeJob(it.id) }
                DownloadForegroundService.start(app)
                showQueue()
            }
        QueueAction.CANCEL ->
            iconAction(R.drawable.wna_stop, ThemeManager.colors.error, "Cancel story", 44) {
                jobs.filter { it.status in DownloadJobStatus.cancellableWires }.forEach { downloadEngine.cancelJob(it.id) }
                showQueue()
            }
        QueueAction.RETRY ->
            iconAction(R.drawable.wna_refresh, ThemeManager.colors.primary, "Retry story", 44) {
                downloadEngine.retryFailedForStory(storyId)
                DownloadForegroundService.start(app)
                showQueue()
            }
        QueueAction.REMOVE ->
            iconAction(R.drawable.wna_close, ThemeManager.colors.onSurfaceVariant, "Remove story", 44) {
                jobs.forEach { downloadEngine.removeJob(it.id) }
                showQueue()
            }
    }

/** Down chevron rotated to point left (collapsed) when the group is folded. */
private fun ScreenHost.chevronIcon(expanded: Boolean): View =
    ImageView(app).apply {
        setImageDrawable(app.tintedIcon(R.drawable.wna_chevron_down, ThemeManager.colors.onSurfaceVariant))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(0, dp(Space.SM), dp(Space.SM), dp(Space.SM))
        rotation = if (expanded) 0f else -90f
        layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
    }

internal fun ScreenHost.addQueueJobRow(job: DownloadJob): View {
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
            val retryDetail = if (job.retryCount > 0) " • retries ${job.retryCount}/${job.maxRetries}" else ""
            val nextRetry = job.nextRetryAt?.let { " • retry in ${formatRelativeTime(it)}" }.orEmpty()
            addView(
                makeText(
                    app,
                    "${job.status}${job.error?.let { " • $it" } ?: ""}$retryDetail$nextRetry",
                    Type.LABEL_SMALL,
                    statusColor(job.status),
                ).apply {
                    setPadding(0, dp(2), 0, 0)
                },
            )
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

/** Inline status-driven action icons for a single chapter, mirroring the legacy RN layout: at most
 *  two icons (e.g. pause + cancel, retry + remove) sit at the row's right edge. */
private fun ScreenHost.addChapterActions(
    container: LinearLayout,
    job: DownloadJob,
) {
    DownloadManagerPlanning.chapterActions(job.status).forEach { action ->
        container.addView(chapterActionButton(action, job))
    }
}

private fun ScreenHost.chapterActionButton(
    action: QueueAction,
    job: DownloadJob,
): View =
    when (action) {
        QueueAction.PAUSE ->
            iconAction(R.drawable.wna_pause, ThemeManager.colors.onSurfaceVariant, "Pause", 36) {
                downloadEngine.pauseJob(job.id)
                showQueue()
            }
        QueueAction.RESUME ->
            iconAction(R.drawable.wna_play, ThemeManager.colors.primary, "Resume", 36) {
                downloadEngine.resumeJob(job.id)
                DownloadForegroundService.start(app)
                showQueue()
            }
        QueueAction.RETRY ->
            iconAction(R.drawable.wna_refresh, ThemeManager.colors.primary, "Retry", 36) {
                downloadEngine.retryJob(job.id)
                DownloadForegroundService.start(app)
                showQueue()
            }
        QueueAction.CANCEL ->
            iconAction(R.drawable.wna_close, ThemeManager.colors.error, "Cancel", 36) {
                downloadEngine.cancelJob(job.id)
                showQueue()
            }
        QueueAction.REMOVE ->
            iconAction(R.drawable.wna_close, ThemeManager.colors.onSurfaceVariant, "Remove", 36) {
                downloadEngine.removeJob(job.id)
                showQueue()
            }
    }

/** Compact tappable icon used by the inline action groups. */
internal fun ScreenHost.iconAction(
    icon: Int,
    tint: Int,
    desc: String,
    sizeDp: Int,
    onClick: () -> Unit,
): View =
    ImageView(app).apply {
        contentDescription = desc
        setImageDrawable(app.tintedIcon(icon, tint))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val pad = dp(Space.SM)
        setPadding(pad, pad, pad, pad)
        background = selectableRipple(ThemeManager.colors.onSurface)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams =
            LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply {
                marginStart = dp(Space.XS)
            }
    }
