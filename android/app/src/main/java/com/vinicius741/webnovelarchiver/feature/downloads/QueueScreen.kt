package com.vinicius741.webnovelarchiver.feature.downloads

import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus
import com.vinicius741.webnovelarchiver.download.DownloadCounts
import com.vinicius741.webnovelarchiver.download.DownloadManagerPlanning
import com.vinicius741.webnovelarchiver.download.DownloadPacingSnapshot
import com.vinicius741.webnovelarchiver.download.DownloadPacingUiPlanning
import com.vinicius741.webnovelarchiver.download.DownloadVerificationPlanning
import com.vinicius741.webnovelarchiver.download.GlobalQueueAction
import com.vinicius741.webnovelarchiver.download.QueueAction
import com.vinicius741.webnovelarchiver.feature.browser.showSourceAccessBlockedDialog
import com.vinicius741.webnovelarchiver.feature.library.showLibrary
import com.vinicius741.webnovelarchiver.feature.settings.showDownloadSettings
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.ui.AppBarAction
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.MaxWidthFrameLayout
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.currentScreenLayout
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.flow
import com.vinicius741.webnovelarchiver.ui.formatRelativeTime
import com.vinicius741.webnovelarchiver.ui.iconButton
import com.vinicius741.webnovelarchiver.ui.jobStatusDot
import com.vinicius741.webnovelarchiver.ui.layout.queueMaxWidth
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeCountChip
import com.vinicius741.webnovelarchiver.ui.makeDivider
import com.vinicius741.webnovelarchiver.ui.makeEmptyState
import com.vinicius741.webnovelarchiver.ui.makeProgressSummary
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.row
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.statusColor
import com.vinicius741.webnovelarchiver.ui.tickerFlow
import com.vinicius741.webnovelarchiver.ui.verticalFill
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

internal fun ScreenHost.showQueue() {
    val host = this
    val queue =
        repository.downloadState.value.queue
            .ifEmpty { repository.queue() }
    val pacingSnapshots = app.appContainer.downloadPacer.snapshots.value
    val nowMillis = System.currentTimeMillis()
    // Re-render on fold/unfold/rotation so the width cap re-centers for the new window.
    rerender = { showQueue() }
    val layout = currentScreenLayout()
    lateinit var adapter: QueueGroupAdapter
    lateinit var summarySlot: LinearLayout
    lateinit var emptySlot: FrameLayout
    lateinit var list: RecyclerView
    val initialGlobalActions = DownloadManagerPlanning.globalActions(DownloadCounts.from(queue))
    // Gear is a fixed first action; the recomputed global set drives the rest of the bar.
    val appBarActions =
        listOf(AppBarAction(R.drawable.wna_settings, "Download Settings") { showDownloadSettings() }) +
            globalAppBarActions(initialGlobalActions)
    screen(route = AppRoute.Queue, title = "Downloads", onBack = { showLibrary() }, actions = appBarActions) {
        // Width-capped centered column so tablets/Fold don't stretch edge-to-edge; no effect on phones.
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
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        android.view.Gravity.CENTER_HORIZONTAL,
                    ),
                )
            }
        summarySlot = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        emptySlot = FrameLayout(context)
        adapter =
            QueueGroupAdapter(host) {
                adapter.submitQueue(
                    repository.downloadState.value.queue,
                    app.appContainer.downloadPacer.snapshots.value,
                    expansionChanged = true,
                )
            }
        list =
            RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context)
                this.adapter = adapter
                itemAnimator = null
                overScrollMode = View.OVER_SCROLL_NEVER
            }
        centered.addView(
            summarySlot,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = context.dp(Space.MD)
            },
        )
        centered.addView(emptySlot, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        centered.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        updateQueueContent(summarySlot, emptySlot, list, adapter, queue, pacingSnapshots, nowMillis)
        addView(centeredShell, verticalFill())
    }
    observeQueueUpdates(summarySlot, emptySlot, list, adapter, initialGlobalActions)
}

private fun ScreenHost.observeQueueUpdates(
    summarySlot: LinearLayout,
    emptySlot: FrameLayout,
    list: RecyclerView,
    adapter: QueueGroupAdapter,
    initialGlobalActions: List<GlobalQueueAction>,
) {
    if (frame.childCount == 0) return
    val root = frame.getChildAt(0)
    // Capture before launching so an event before collector registration is not dropped.
    var observedQueueVersion = repository.downloadState.value.queueVersion
    var observedGlobalActions = initialGlobalActions
    var hadTimedStatus =
        app.appContainer.downloadPacer.snapshots.value.values.any {
            it.nextRequestAtMillis > System.currentTimeMillis()
        } ||
            repository.downloadState.value.queue.any {
                (it.nextRetryAt ?: 0L) > System.currentTimeMillis()
            }
    screenObserver =
        scope.launch {
            combine(
                repository.downloadState,
                app.appContainer.downloadPacer.snapshots,
                tickerFlow(),
            ) { repositorySnapshot, pacing, now -> Triple(repositorySnapshot, pacing, now) }
                .collect { (snapshot, pacing, now) ->
                    val queueChanged = snapshot.queueVersion != observedQueueVersion
                    observedQueueVersion = snapshot.queueVersion
                    val hasTimedStatus =
                        pacing.values.any { it.nextRequestAtMillis > now } ||
                            snapshot.queue.any { (it.nextRetryAt ?: 0L) > now }
                    val countdownChanged = hasTimedStatus || hadTimedStatus
                    hadTimedStatus = hasTimedStatus
                    if (!queueChanged && !countdownChanged) return@collect
                    if (root.parent === frame) {
                        val nextGlobalActions = DownloadManagerPlanning.globalActions(DownloadCounts.from(snapshot.queue))
                        if (queueChanged && nextGlobalActions != observedGlobalActions) {
                            observedGlobalActions = nextGlobalActions
                            frame.post {
                                if (root.parent === frame) showQueue()
                            }
                            return@collect
                        }
                        updateQueueContent(summarySlot, emptySlot, list, adapter, snapshot.queue, pacing, now)
                    }
                }
        }
}

private fun ScreenHost.updateQueueContent(
    summarySlot: LinearLayout,
    emptySlot: FrameLayout,
    list: RecyclerView,
    adapter: QueueGroupAdapter,
    queue: List<DownloadJob>,
    pacing: Map<String, DownloadPacingSnapshot>,
    nowMillis: Long,
) {
    val counts = DownloadCounts.from(queue)
    summarySlot.removeAllViews()
    emptySlot.removeAllViews()
    if (queue.isEmpty()) {
        summarySlot.visibility = View.GONE
        list.visibility = View.GONE
        emptySlot.visibility = View.VISIBLE
        emptySlot.addView(
            makeEmptyState(
                app,
                message = "Chapters you download will appear here while they download.",
                title = "Nothing downloading",
                iconRes = R.drawable.wna_download,
            ),
        )
    } else {
        summarySlot.visibility = View.VISIBLE
        list.visibility = View.VISIBLE
        emptySlot.visibility = View.GONE
        summarySlot.row {
            addView(makeProgressSummary(context, counts.completed, counts.total).root)
        }
        val waitingByJobId = DownloadPacingUiPlanning.waitingJobs(pacing.values, queue, nowMillis)
        val downloadingNow = counts.downloading - waitingByJobId.size
        summarySlot.flow {
            addView(makeCountChip(context, "downloading", downloadingNow, ThemeManager.colors.primary))
            addView(makeCountChip(context, "waiting for delay", waitingByJobId.size, ThemeManager.colors.secondary))
            addView(makeCountChip(context, "queued", counts.pending, ThemeManager.colors.onSurfaceVariant))
            addView(makeCountChip(context, "paused", counts.paused, ThemeManager.colors.secondary))
            addView(makeCountChip(context, "failed", counts.failed, ThemeManager.colors.error))
            addView(makeCountChip(context, "cancelled", counts.cancelled, ThemeManager.colors.error))
        }
        appendBlockedVerificationBanner(summarySlot, queue)
    }
    adapter.submitQueue(queue, pacing, nowMillis)
}

/** The all-pending blocked state has no failed jobs for the retry-keyed solve flow; offer an explicit verify action. */
private fun ScreenHost.appendBlockedVerificationBanner(
    summarySlot: LinearLayout,
    queue: List<DownloadJob>,
) {
    val evidence =
        DownloadVerificationPlanning.blockedPendingEvidence(
            jobs = queue,
            isSourceBlocked = app.appContainer.network::isSourceBlocked,
        ) { job -> SourceRegistry.getProvider(job.sourceId, job.chapter.url)?.id }
    if (evidence.pendingCount == 0 || evidence.sampleUrl == null) return
    summarySlot.addView(
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(Space.MD), 0, 0)
            addView(
                makeText(app, "Source verification required", Type.TITLE_SMALL, ThemeManager.colors.error),
            )
            addView(
                makeText(
                    app,
                    "Cloudflare is blocking automated access to this source; ${evidence.pendingCount} " +
                        "queued downloads are waiting for one verification.",
                    Type.BODY_MEDIUM,
                    ThemeManager.colors.onSurfaceVariant,
                ).apply { setPadding(0, dp(Space.XS), 0, 0) },
            )
            val verifyButton =
                makeButton(app, "Verify access", Btn.TONAL) {
                    showSourceAccessBlockedDialog(evidence.sampleUrl) {
                        startDownloadForegroundService()
                    }
                }
            verifyButton.layoutParams =
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(Space.SM)
                }
            addView(verifyButton)
        },
    )
}

private fun ScreenHost.globalAppBarActions(actions: List<GlobalQueueAction>): List<AppBarAction> =
    actions.map { action ->
        when (action) {
            GlobalQueueAction.RESUME_ALL ->
                AppBarAction(R.drawable.wna_play, "Resume All") {
                    launchQueueControl("resumeAll") { downloadEngine.resumeAll() }
                    startDownloadForegroundService()
                }
            GlobalQueueAction.PAUSE_ALL ->
                AppBarAction(R.drawable.wna_pause, "Pause All") {
                    launchQueueControl("pauseAll") { downloadEngine.pauseAll() }
                }
            GlobalQueueAction.RETRY_ALL ->
                AppBarAction(R.drawable.wna_refresh, "Retry Failed", ThemeManager.colors.primary) {
                    val blocked =
                        repository
                            .downloadState
                            .value
                            .queue
                            .firstOrNull { it.errorCategory == "source_blocked" }
                            ?: repository.queue().firstOrNull { it.errorCategory == "source_blocked" }
                    if (blocked != null) {
                        showSourceAccessBlockedDialog(blocked.chapter.url) {
                            launchQueueControl("retryFailed") { downloadEngine.retryFailed() }
                            startDownloadForegroundService()
                        }
                        return@AppBarAction
                    }
                    launchQueueControl("retryFailed") { downloadEngine.retryFailed() }
                    startDownloadForegroundService()
                }
            GlobalQueueAction.CANCEL_ALL ->
                AppBarAction(R.drawable.wna_stop, "Cancel All", ThemeManager.colors.error) {
                    confirm("Cancel all active and pending downloads?", confirmLabel = "Cancel All") {
                        launchQueueControl("cancelAll") { downloadEngine.cancelAll() }
                    }
                }
            GlobalQueueAction.CLEAR_FINISHED ->
                AppBarAction(R.drawable.wna_check, "Clear Finished") {
                    confirm(
                        "Remove all finished downloads (completed, failed, and cancelled) from the list?",
                        confirmLabel = "Clear Finished",
                    ) {
                        launchQueueControl("clearFinished") { downloadEngine.clearFinished() }
                    }
                }
        }
    }

/**
 * Runs one queue control on the screen scope; the repository moves the durable work to its I/O
 * dispatcher, so the click handler never touches storage. A failed save is logged, not crashed on.
 */
internal fun ScreenHost.launchQueueControl(
    name: String,
    block: suspend () -> Unit,
) {
    scope.launch {
        runCatching { block() }.onFailure { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            timber.log.Timber.w(error, "Queue control failed: %s", name)
        }
    }
}

/**
 * Recycled card shell for one story's group: heavy views are built once; [bind] repopulates only
 * the dynamic contents instead of rebuilding the subtree on every bind.
 */
internal class QueueGroupCard(
    val view: LinearLayout,
    private val host: ScreenHost,
    private val header: LinearLayout,
    private val chevron: View,
    private val title: TextView,
    private val subtitle: TextView,
    private val progressSlot: LinearLayout,
    private val actionSlot: LinearLayout,
    private val body: LinearLayout,
) {
    /** Per-job status labels from the latest bind, so countdown ticks patch text in place (R23). */
    private val statusLabels = LinkedHashMap<String, TextView>()

    /** The group this card was last bound to; ticks recompute its header countdown (R23). */
    private var boundGroup: QueueStoryGroupUi? = null

    fun bind(
        group: QueueStoryGroupUi,
        allJobs: List<DownloadJob>,
        pacingSnapshots: Collection<DownloadPacingSnapshot>,
        nowMillis: Long,
        onToggle: () -> Unit,
    ) {
        boundGroup = group
        val jobs = group.jobs.map { it.job }
        val storyId = group.storyId
        val storyTitle = jobs.firstOrNull()?.storyTitle ?: "Unknown Story"
        val counts = DownloadCounts.from(jobs)
        val expanded = group.expanded

        title.text = storyTitle
        val providerName = SourceRegistry.getProvider(jobs.first().sourceId, jobs.first().chapter.url)?.name
        val pacingStatus =
            DownloadPacingUiPlanning.storyStatus(
                storyId = storyId,
                providerName = providerName,
                storyJobs = jobs,
                snapshots = pacingSnapshots,
                nowMillis = nowMillis,
                allJobs = allJobs,
            )
        val waitingByJobId = DownloadPacingUiPlanning.waitingJobs(pacingSnapshots, jobs, nowMillis)
        subtitle.text =
            buildString {
                append(DownloadManagerPlanning.storySubtitle(counts, waitingByJobId.size))
                DownloadPacingUiPlanning.groupHeadline(pacingStatus)?.let {
                    append('\n')
                    append(it)
                }
            }
        chevron.rotation = if (expanded) 0f else -90f

        progressSlot.removeAllViews()
        progressSlot.addView(makeProgressSummary(host.app, counts.completed, counts.total).root)
        actionSlot.removeAllViews()
        actionSlot.addView(host.storyActionGroup(storyId, jobs, counts))

        header.setOnClickListener {
            host.storyExpandOverride[storyId] = !expanded
            onToggle()
        }

        body.removeAllViews()
        statusLabels.clear()
        if (expanded) {
            body.addView(makeDivider(host.app))
            jobs.sortedBy { it.chapterIndex }.forEachIndexed { index, job ->
                if (index > 0) body.addView(makeDivider(host.app))
                body.addView(host.addQueueJobRow(job, waitingByJobId[job.id]) { label -> statusLabels[job.id] = label })
            }
        }
    }

    /** Recomputes only the countdown texts (header subtitle + status lines); no rebind, no diff (R23). */
    fun patchCountdownLabels(
        queue: List<DownloadJob>,
        pacingSnapshots: Collection<DownloadPacingSnapshot>,
        nowMillis: Long,
    ) {
        val group = boundGroup ?: return
        val jobs = group.jobs.map { it.job }
        val waitingByJobId = DownloadPacingUiPlanning.waitingJobs(pacingSnapshots, queue, nowMillis)
        val providerName = SourceRegistry.getProvider(jobs.first().sourceId, jobs.first().chapter.url)?.name
        val pacingStatus =
            DownloadPacingUiPlanning.storyStatus(
                storyId = group.storyId,
                providerName = providerName,
                storyJobs = jobs,
                snapshots = pacingSnapshots,
                nowMillis = nowMillis,
                allJobs = queue,
            )
        val nextSubtitle =
            buildString {
                append(DownloadManagerPlanning.storySubtitle(DownloadCounts.from(jobs), waitingByJobId.size))
                DownloadPacingUiPlanning.groupHeadline(pacingStatus)?.let {
                    append('\n')
                    append(it)
                }
            }
        if (subtitle.text.toString() != nextSubtitle) subtitle.text = nextSubtitle
        statusLabels.forEach { (jobId, label) ->
            queue.firstOrNull { it.id == jobId }?.let { job ->
                label.text = queueJobStatusLabel(job, waitingByJobId[jobId])
            }
        }
    }
}

/** Inline header action icons; clickable children consume the touch so they don't toggle expand. */
private fun ScreenHost.storyActionGroup(
    storyId: String,
    jobs: List<DownloadJob>,
    counts: DownloadCounts,
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
                launchQueueControl("pauseStory") { downloadEngine.pauseStory(storyId) }
            }
        QueueAction.RESUME ->
            iconAction(R.drawable.wna_play, ThemeManager.colors.primary, "Resume story", 44) {
                launchQueueControl("resumeStory") { downloadEngine.resumeStory(storyId) }
                startDownloadForegroundService()
            }
        QueueAction.CANCEL ->
            iconAction(R.drawable.wna_stop, ThemeManager.colors.error, "Cancel story", 44) {
                launchQueueControl("cancelStory") { downloadEngine.cancelStory(storyId) }
            }
        QueueAction.RETRY ->
            iconAction(R.drawable.wna_refresh, ThemeManager.colors.primary, "Retry story", 44) {
                if (jobs.any { it.errorCategory == "source_blocked" }) {
                    jobs
                        .firstOrNull { it.errorCategory == "source_blocked" }
                        ?.let {
                            showSourceAccessBlockedDialog(it.chapter.url) {
                                launchQueueControl("retryFailedForStory") { downloadEngine.retryFailedForStory(storyId) }
                                startDownloadForegroundService()
                            }
                        }
                    return@iconAction
                }
                launchQueueControl("retryFailedForStory") { downloadEngine.retryFailedForStory(storyId) }
                startDownloadForegroundService()
            }
        QueueAction.REMOVE ->
            iconAction(R.drawable.wna_close, ThemeManager.colors.onSurfaceVariant, "Remove story", 44) {
                launchQueueControl("removeStory") { downloadEngine.removeStory(storyId) }
            }
    }

internal fun ScreenHost.addChapterActions(
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
                launchQueueControl("pauseJob") { downloadEngine.pauseJob(job.id) }
            }
        QueueAction.RESUME ->
            iconAction(R.drawable.wna_play, ThemeManager.colors.primary, "Resume", 36) {
                launchQueueControl("resumeJob") { downloadEngine.resumeJob(job.id) }
                startDownloadForegroundService()
            }
        QueueAction.RETRY ->
            iconAction(R.drawable.wna_refresh, ThemeManager.colors.primary, "Retry", 36) {
                if (job.errorCategory == "source_blocked") {
                    showSourceAccessBlockedDialog(job.chapter.url) {
                        launchQueueControl("retryJob") { downloadEngine.retryJob(job.id) }
                        startDownloadForegroundService()
                    }
                    return@iconAction
                }
                launchQueueControl("retryJob") { downloadEngine.retryJob(job.id) }
                startDownloadForegroundService()
            }
        QueueAction.CANCEL ->
            iconAction(R.drawable.wna_close, ThemeManager.colors.error, "Cancel", 36) {
                launchQueueControl("cancelJob") { downloadEngine.cancelJob(job.id) }
            }
        QueueAction.REMOVE ->
            iconAction(R.drawable.wna_close, ThemeManager.colors.onSurfaceVariant, "Remove", 36) {
                launchQueueControl("removeJob") { downloadEngine.removeJob(job.id) }
            }
    }

internal fun ScreenHost.iconAction(
    icon: Int,
    tint: Int,
    desc: String,
    sizeDp: Int,
    onClick: () -> Unit,
): View =
    app
        .iconButton(
            icon,
            desc,
            tint,
            style =
                when (sizeDp) {
                    44 -> com.vinicius741.webnovelarchiver.ui.IconButtonStyle.Standard
                    40 -> com.vinicius741.webnovelarchiver.ui.IconButtonStyle.Small
                    else -> com.vinicius741.webnovelarchiver.ui.IconButtonStyle.Compact
                },
            onClick = onClick,
        ).apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(Space.XS)
        }
