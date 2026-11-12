package com.vinicius741.webnovelarchiver.feature.downloads

import android.app.AlertDialog
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.download.DownloadCounts
import com.vinicius741.webnovelarchiver.download.DownloadManagerPlanning
import com.vinicius741.webnovelarchiver.download.DownloadPacingSnapshot
import com.vinicius741.webnovelarchiver.download.DownloadQueueSectionPlanning
import com.vinicius741.webnovelarchiver.download.DownloadQueueStatusCopy
import com.vinicius741.webnovelarchiver.download.GlobalQueueAction
import com.vinicius741.webnovelarchiver.download.QueueAction
import com.vinicius741.webnovelarchiver.download.QueueAppBarPlan
import com.vinicius741.webnovelarchiver.download.QueueScreenPlan
import com.vinicius741.webnovelarchiver.download.QueueSection
import com.vinicius741.webnovelarchiver.download.SourceCheckCard
import com.vinicius741.webnovelarchiver.download.SourceCheckOutcome
import com.vinicius741.webnovelarchiver.feature.browser.SourceCheckOutcomes
import com.vinicius741.webnovelarchiver.feature.settings.showDownloadSettings
import com.vinicius741.webnovelarchiver.feature.story.showCoverDialog
import com.vinicius741.webnovelarchiver.feature.updates.showUpdates
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.ui.AppBarAction
import com.vinicius741.webnovelarchiver.ui.MaxWidthFrameLayout
import com.vinicius741.webnovelarchiver.ui.OptionsDialogItem
import com.vinicius741.webnovelarchiver.ui.activeCoverSource
import com.vinicius741.webnovelarchiver.ui.applyAppTheme
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.currentScreenLayout
import com.vinicius741.webnovelarchiver.ui.layout.queueMaxWidth
import com.vinicius741.webnovelarchiver.ui.loadImage
import com.vinicius741.webnovelarchiver.ui.makeEmptyState
import com.vinicius741.webnovelarchiver.ui.rememberListPosition
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.showStyledOptionsDialog
import com.vinicius741.webnovelarchiver.ui.tickerFlow
import com.vinicius741.webnovelarchiver.ui.toast
import com.vinicius741.webnovelarchiver.ui.verticalFill
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Per-render state shared by the list callbacks and the observer. */
private class QueueScreenSession {
    /** Queue version when a drag was dropped; renders wait for the saved order instead of snapping back. */
    var reorderVersion: Long? = null
    var reorderAtMillis: Long = 0L
}

private const val REORDER_HOLD_MILLIS = 3_000L

internal fun providerIdForJob(job: DownloadJob): String? = SourceRegistry.getProvider(job.sourceId, job.chapter.url)?.id

internal fun ScreenHost.queuePlan(queue: List<DownloadJob>): QueueScreenPlan =
    DownloadQueueSectionPlanning.plan(queue, ::providerIdForJob, app.appContainer.network::isSourceBlocked)

internal fun ScreenHost.showQueue() {
    val queue =
        repository.downloadState.value.queue
            .ifEmpty { repository.queue() }
    val initialPlan = queuePlan(queue)
    val initialAppBar = DownloadManagerPlanning.appBar(initialPlan.counts, initialPlan.failedChapters)
    // Re-render on fold/unfold/rotation so the width cap re-centers for the new window.
    rerender = { showQueue() }
    val layout = currentScreenLayout()
    val session = QueueScreenSession()
    lateinit var adapter: QueueListAdapter
    lateinit var emptySlot: FrameLayout
    lateinit var list: RecyclerView
    val renderNow: () -> Unit = {
        renderQueue(
            emptySlot,
            list,
            adapter,
            repository.downloadState.value.queue,
            app.appContainer.downloadPacer.snapshots.value,
            SourceCheckOutcomes.state.value,
            app.appContainer.time.nowMillis(),
        )
    }
    screen(
        route = AppRoute.Queue,
        title = "Downloads",
        subtitle = QueueSummaryPlanning.subtitle(initialPlan),
        actions = queueAppBarActions(initialAppBar),
    ) {
        // Width-capped centered column so tablets/Fold don't stretch edge-to-edge; no effect on phones.
        val centered = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
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
        emptySlot = FrameLayout(context)
        adapter = QueueListAdapter(app, queueListActions(session, renderNow)) { view, id -> bindQueueCover(view, id) }
        list =
            RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context)
                this.adapter = adapter
                itemAnimator = null
                overScrollMode = View.OVER_SCROLL_NEVER
            }
        adapter.touchHelper.attachToRecyclerView(list)
        centered.addView(emptySlot, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        centered.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        renderQueue(
            emptySlot,
            list,
            adapter,
            queue,
            app.appContainer.downloadPacer.snapshots.value,
            SourceCheckOutcomes.state.value,
            app.appContainer.time.nowMillis(),
        )
        rememberListPosition(list)
        addView(centeredShell, verticalFill())
    }
    observeQueueUpdates(emptySlot, list, adapter, session, initialAppBar)
}

private fun ScreenHost.observeQueueUpdates(
    emptySlot: FrameLayout,
    list: RecyclerView,
    adapter: QueueListAdapter,
    session: QueueScreenSession,
    initialAppBar: QueueAppBarPlan,
) {
    if (frame.childCount == 0) return
    val root = frame.getChildAt(0)
    // Capture before launching so an event before collector registration is not dropped.
    var observedQueueVersion = repository.downloadState.value.queueVersion
    var observedOutcomes = SourceCheckOutcomes.state.value
    var observedBlocked = blockedProviders(repository.downloadState.value.queue)
    var hadTimedStatus = true
    screenObserver =
        scope.launch {
            combine(
                repository.downloadState,
                app.appContainer.downloadPacer.snapshots,
                SourceCheckOutcomes.state,
                tickerFlow(nowMillis = app.appContainer.time::nowMillis),
            ) { snapshot, pacing, outcomes, now -> QueueTick(snapshot.queue, snapshot.queueVersion, pacing, outcomes, now) }
                .collect { tick ->
                    if (root.parent !== frame) return@collect
                    val queueChanged = tick.queueVersion != observedQueueVersion
                    observedQueueVersion = tick.queueVersion
                    val outcomesChanged = tick.outcomes != observedOutcomes
                    observedOutcomes = tick.outcomes
                    // A site's circuit can open or close without a queue write (sync, cooldown expiry).
                    val blocked = blockedProviders(tick.queue)
                    val blockedChanged = blocked != observedBlocked
                    observedBlocked = blocked
                    val hasTimedStatus = hasTimedStatus(tick)
                    val countdownChanged = hasTimedStatus || hadTimedStatus
                    hadTimedStatus = hasTimedStatus
                    if (!queueChanged && !outcomesChanged && !blockedChanged && !countdownChanged) return@collect
                    val holding = session.reorderVersion
                    if (holding != null) {
                        if (tick.queueVersion == holding && tick.now - session.reorderAtMillis < REORDER_HOLD_MILLIS) return@collect
                        session.reorderVersion = null
                    }
                    if (queueChanged) {
                        val plan = queuePlan(tick.queue)
                        val appBar = DownloadManagerPlanning.appBar(plan.counts, plan.failedChapters)
                        if (appBar.toggle != initialAppBar.toggle || appBar.overflow.isEmpty() != initialAppBar.overflow.isEmpty()) {
                            // Rebuild synchronously. A deferred frame.post let the resume publish
                            // burst interleave with the observer handoff and could strand the
                            // screen on a cancelled collector that never re-registered.
                            showQueue()
                            return@collect
                        }
                    }
                    renderQueue(emptySlot, list, adapter, tick.queue, tick.pacing, tick.outcomes, tick.now)
                }
        }
}

private data class QueueTick(
    val queue: List<DownloadJob>,
    val queueVersion: Long,
    val pacing: Map<String, DownloadPacingSnapshot>,
    val outcomes: Map<String, SourceCheckOutcome>,
    val now: Long,
)

private fun hasTimedStatus(tick: QueueTick): Boolean =
    tick.pacing.values.any { it.nextRequestAtMillis > tick.now } ||
        tick.queue.any { (it.nextRetryAt ?: 0L) > tick.now } ||
        tick.outcomes.values.any { it.verified && tick.now - it.atMillis < DownloadQueueSectionPlanning.VERIFIED_CARD_MILLIS + 1_000L }

private fun ScreenHost.blockedProviders(queue: List<DownloadJob>): Set<String> =
    DownloadQueueSectionPlanning
        .blockedSites(queue, ::providerIdForJob, app.appContainer.network::isSourceBlocked)
        .mapTo(HashSet()) { it.providerId }

@Suppress("LongParameterList")
private fun ScreenHost.renderQueue(
    emptySlot: FrameLayout,
    list: RecyclerView,
    adapter: QueueListAdapter,
    queue: List<DownloadJob>,
    pacing: Map<String, DownloadPacingSnapshot>,
    outcomes: Map<String, SourceCheckOutcome>,
    nowMillis: Long,
) {
    val plan = queuePlan(queue)
    setQueueSubtitle(QueueSummaryPlanning.subtitle(plan))
    if (queue.isEmpty()) {
        list.visibility = View.GONE
        emptySlot.visibility = View.VISIBLE
        if (emptySlot.childCount == 0) {
            emptySlot.addView(
                makeEmptyState(
                    app,
                    message = "Chapters you download will appear here while they download.",
                    title = "Nothing downloading",
                    iconRes = R.drawable.wna_download_done,
                    actionLabel = "Find new chapters",
                    onAction = { showUpdates() },
                ),
            )
        }
        adapter.submit(emptyList())
        return
    }
    list.visibility = View.VISIBLE
    emptySlot.visibility = View.GONE
    val cards = DownloadQueueSectionPlanning.checkCards(plan.blockedSites, outcomes, nowMillis)
    adapter.submit(buildQueueItems(plan, cards, queue, pacing.values, nowMillis, storyExpandOverride))
}

private fun ScreenHost.queueListActions(
    session: QueueScreenSession,
    renderNow: () -> Unit,
): QueueListActions =
    object : QueueListActions {
        override fun onCheck(
            card: SourceCheckCard,
            trouble: Boolean,
        ) {
            if (trouble) {
                showSourceCheckTrouble(card)
            } else {
                card.sampleUrl?.let { startSourceCheck(card.providerId, it) }
            }
        }

        override fun onRetryFailed() = retryFailedChapters()

        override fun onToggleFinished() {
            storyExpandOverride[FINISHED_SECTION_KEY] = storyExpandOverride[FINISHED_SECTION_KEY] != true
            renderNow()
        }

        override fun onClearFinished() = confirmClearFinished()

        override fun onToggleStory(storyId: String) {
            storyExpandOverride[storyId] = storyExpandOverride[storyId] != true
            renderNow()
        }

        override fun onStoryPrimary(item: QueueStoryItem) = runStoryAction(item.storyId, item.primaryAction)

        override fun onStoryMenu(item: QueueStoryItem) = showStoryMenu(item, renderNow)

        override fun onChapterRetry(item: QueueChapterItem) {
            launchQueueControl("retryJob") { downloadEngine.retryJob(item.jobId) }
            startDownloadForegroundService()
        }

        override fun onChapterMenu(item: QueueChapterItem) = showChapterMenu(item)

        override fun onReorder(upNextOrder: List<String>) {
            session.reorderVersion = repository.downloadState.value.queueVersion
            session.reorderAtMillis = app.appContainer.time.nowMillis()
            launchQueueControl("reorderStories") { downloadEngine.reorderStories(upNextOrder) }
        }
    }

/**
 * Runs one queue control on the screen scope; the repository moves the durable work to its I/O
 * dispatcher, so the click handler never touches storage. A failed save is logged, not crashed on.
 */
@Suppress("TooGenericExceptionCaught")
internal fun ScreenHost.launchQueueControl(
    name: String,
    block: suspend () -> Unit,
) {
    scope.launch {
        try {
            block()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            timber.log.Timber.w(error, "Queue control failed: %s", name)
            toast("Couldn't save that change")
        }
    }
}

/** Loads a novel's current cover into its card; tapping the cover opens it full screen. */
private fun ScreenHost.bindQueueCover(
    view: ImageView,
    storyId: String,
) {
    val story = repository.story(storyId)
    val source = story?.let { activeCoverSource(it) }
    if (source == null) {
        view.setImageDrawable(null)
        view.setOnClickListener(null)
        view.isClickable = false
        return
    }
    view.setOnClickListener { repository.story(storyId)?.let { showCoverDialog(it) } }
    loadImage(source, view, fallbackOnError = if (source is java.io.File) story.coverUrl?.takeIf { it.isNotBlank() } else null)
}
