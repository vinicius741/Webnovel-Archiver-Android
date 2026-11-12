package com.vinicius741.webnovelarchiver.feature.narration

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.feature.player.PlayerProgressLine
import com.vinicius741.webnovelarchiver.feature.settings.showTtsSettings
import com.vinicius741.webnovelarchiver.narration.NarrationJobState
import com.vinicius741.webnovelarchiver.narration.NarrationProgress
import com.vinicius741.webnovelarchiver.narration.NarrationQueueDocument
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.AppBarAction
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.OptionsDialogItem
import com.vinicius741.webnovelarchiver.ui.SCROLL_BODY_VIEW_TAG
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeCard
import com.vinicius741.webnovelarchiver.ui.makeEmptyState
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.showStyledOptionsDialog
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import com.vinicius741.webnovelarchiver.ui.verticalFill
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The Listen hub's selected section and per-section scroll, across its rebuilds (each queue change rebuilds it). */
private var listenSegment = ListenSegment.QUEUE
private val listenScrollY = mutableMapOf<ListenSegment, Int>()

/** Width from which the sections sit side by side and the segmented control goes away. */
private const val SIDE_BY_SIDE_MIN_DP = 720

/**
 * The Listen destination (v3): the Now playing / Continue listening card, then Queue (the
 * generation queue grouped by story, with the live banner, reordering, retry and removal),
 * Generated (every story's audio and its storage) and Setup (where to generate, computers, and
 * chapters ahead). A segmented control picks one section on a phone; the inner display shows the
 * queue beside the other two.
 */
internal fun ScreenHost.showNarration() {
    rerender = { showNarration() }
    val coordinator = narration
    val document = coordinator.queueState.value
    val host = this
    val sideBySide = app.resources.configuration.screenWidthDp >= SIDE_BY_SIDE_MIN_DP
    val liveStatus = NarrationLiveStatus(this, hideWhenIdle = false) { banner -> banner.storyId?.let { showNarrationStory(it) } }
    val runningRow = RunningJobRow()
    val nowCard = ListenNowCard(this)
    var serverRows: ServerRows? = null
    val scroller =
        ScrollView(app).apply {
            isFillViewport = true
            clipToPadding = false
            tag = SCROLL_BODY_VIEW_TAG
            setOnScrollChangeListener { _, _, y, _, _ -> listenScrollY[listenSegment] = y }
        }
    val actions =
        buildList {
            add(AppBarAction(R.drawable.wna_record_voice, "Voice & Speech") { showTtsSettings() })
            if (document.jobs.isNotEmpty()) add(AppBarAction(R.drawable.wna_more_vert, "Queue options") { host.showQueueMenu() })
        }
    screen(route = AppRoute.Narration, title = "Listen", subtitle = "Player and audio generation", actions = actions) {
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        content.addView(nowCard.view)
        val audio = host.generatedAudio()
        // On a phone the banner sits above the sections, so a paused or failed queue shows on all of them.
        if (!sideBySide) {
            content.addView(
                liveStatus.view,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(Space.MD)
                },
            )
        }
        val queueBox = section { queueSection(host, document, runningRow, liveStatus.takeIf { sideBySide }) }
        val generatedBox = section { generatedSection(host, audio) }
        val setupBox = section { serverRows = setupSection(host, document) }
        if (sideBySide) {
            content.addView(sideBySide(queueBox, generatedBox, setupBox))
        } else {
            val boxes = mapOf(ListenSegment.QUEUE to queueBox, ListenSegment.GENERATED to generatedBox, ListenSegment.SETUP to setupBox)

            fun show(segment: ListenSegment) =
                boxes.forEach { (key, box) ->
                    box.visibility =
                        if (key == segment) View.VISIBLE else View.GONE
                }
            val counts = mapOf(ListenSegment.QUEUE to document.jobs.size, ListenSegment.GENERATED to audio.attached.size)
            val segments =
                ListenSegmentedControl(context, listenSegment, counts) { segment ->
                    listenSegment = segment
                    show(segment)
                    scroller.post { scroller.scrollTo(0, listenScrollY[segment] ?: 0) }
                }
            content.addView(
                segments.view,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(Space.LG)
                },
            )
            boxes.values.forEach { content.addView(it) }
            show(listenSegment)
        }
        scroller.addView(content)
        addView(scroller, verticalFill())
    }
    val restoreY = listenScrollY[listenSegment] ?: 0
    if (restoreY > 0) scroller.post { scroller.scrollTo(0, restoreY) }
    runningRow.bind(coordinator.progress.value)
    screenObserver =
        scope.launch {
            liveStatus.observe(host, this)
            launch { ttsEngine.playbackState.collect { nowCard.bind(it.snapshot) } }
            launch { coordinator.progress.collect(runningRow::bind) }
            launch { coordinator.serverStatus.collect { statuses -> serverRows?.bind(statuses) } }
            // Online checks while the screen is visible. Each rebuild restarts this loop, so it ticks
            // often and asks only computers not checked within the interval.
            if (document.servers.isNotEmpty()) {
                launch {
                    app.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        while (true) {
                            coordinator.refreshServerStatus(SERVER_CHECK_INTERVAL_MS)
                            delay(SERVER_CHECK_TICK_MS)
                        }
                    }
                }
            }
            val runningJob = coordinator.progress.map { it?.job?.id }.distinctUntilChanged()
            // Keep-ahead and speed measurements change without anything to rebuild (the stepper and
            // the live banner show them), so they are left out; a rebuild would also cut a held stepper.
            val queue = coordinator.queueState.map { it.withoutLiveFields() }
            combine(queue, coordinator.packsRevision, runningJob) { q, packs, job -> Triple(q, packs, job) }
                .distinctUntilChanged()
                .drop(1)
                .collect { if (navigator.current == AppRoute.Narration) showNarration() }
        }
}

private fun NarrationQueueDocument.withoutLiveFields() = copy(keepAhead = 0, msPerChar = emptyMap(), speed = emptyMap())

private fun LinearLayout.section(block: LinearLayout.() -> Unit): LinearLayout =
    LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        block()
    }

/** Queue on the left; Generated and Setup stacked on the right. */
private fun LinearLayout.sideBySide(
    queue: View,
    generated: View,
    setup: View,
): View =
    LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        val right =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(generated)
                addView(setup)
            }
        addView(
            queue,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, QUEUE_WEIGHT).apply {
                marginEnd =
                    context.dp(Space.XL - 4)
            },
        )
        addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

private fun LinearLayout.queueSection(
    host: ScreenHost,
    document: NarrationQueueDocument,
    runningRow: RunningJobRow,
    liveStatus: NarrationLiveStatus?,
) {
    liveStatus?.let { addView(it.view.apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = context.dp(Space.MD) }) }
    if (!host.app.isIgnoringBatteryOptimizations()) batteryCard(host)
    val jobs = document.jobs
    val failed = jobs.count { it.state == NarrationJobState.FAILED }
    if (jobs.isEmpty()) {
        listenSectionLabel("Queue")
        addView(
            makeEmptyState(
                context,
                title = "Queue is empty",
                message = "Generate chapters from a story's Audio tab.",
                iconRes = R.drawable.wna_headphones,
            ),
        )
        return
    }
    listenSectionLabel(
        "Queue · ${NarrationScreenPlanning.chapters(jobs.size)}",
        aside = if (failed > 0) "Retry failed" else null,
        onAside = if (failed > 0) ({ host.narrationAction("Could not retry") { retryAll() } }) else null,
    )
    listenHint("Chapters generate in this order. Moving a story first switches at the next sentence.")
    val running =
        host.narration.progress.value
            ?.job
            ?.id
    NarrationScreenPlanning.groups(jobs).forEachIndexed { index, group ->
        addView(queueStoryCard(host, group, isFirst = index == 0, running, runningRow))
    }
}

private fun LinearLayout.batteryCard(host: ScreenHost) {
    val colors = ThemeManager.colors
    val card =
        makeCard(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(
                android.widget
                    .ImageView(
                        context,
                    ).apply { setImageDrawable(context.tintedIcon(R.drawable.wna_battery_alert, colors.primary)) },
                LinearLayout
                    .LayoutParams(
                        context.dp(BATTERY_ICON_DP),
                        context.dp(BATTERY_ICON_DP),
                    ).apply { marginEnd = context.dp(Space.MD) },
            )
            val texts =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(makeText(context, "Generating with the screen off", Type.TITLE_SMALL, colors.onSurface))
                    addView(
                        makeText(
                            context,
                            "Allow background use so audio keeps generating while the phone is locked.",
                            Type.BODY_SMALL,
                            colors.onSurfaceVariant,
                        ),
                    )
                }
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = context.dp(Space.SM) })
            addView(makeButton(context, "Allow", Btn.TONAL) { host.app.requestIgnoreBatteryOptimizations() })
        }
    addView(
        card,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(Space.XS)
        },
    )
}

private fun ScreenHost.showQueueMenu() {
    val jobs = narration.queueState.value.jobs
    val failed = jobs.count { it.state == NarrationJobState.FAILED }
    val items = mutableListOf<OptionsDialogItem>()
    if (narration.queueState.value.paused) {
        items += OptionsDialogItem.Option("Resume generating") { narrationAction("Could not resume") { resume() } }
    } else {
        items += OptionsDialogItem.Option("Pause generating") { narrationAction("Could not pause") { pause() } }
    }
    if (failed > 0) {
        items += OptionsDialogItem.Option("Retry failed ($failed)") { narrationAction("Could not retry") { retryAll() } }
        items += OptionsDialogItem.Option("Clear failed ($failed)") { narrationAction("Could not clear") { clearFailed() } }
    }
    items += OptionsDialogItem.Divider
    items +=
        OptionsDialogItem.Option("Clear the queue (${NarrationScreenPlanning.chapters(jobs.size)})", destructive = true) {
            confirm(
                "Stop generating and remove all ${NarrationScreenPlanning.chapters(jobs.size)} from the queue? " +
                    "Audio already generated stays.",
                confirmLabel = "Clear",
            ) { narrationAction("Could not clear the queue") { clearQueue() } }
        }
    showStyledOptionsDialog("Queue options", items)
}

private const val QUEUE_WEIGHT = 1.15f
private const val BATTERY_ICON_DP = 24

/** The queue row of the chapter being generated, patched on every sentence. */
internal class RunningJobRow {
    var jobId: String? = null
    var status: TextView? = null
    var line: PlayerProgressLine? = null

    fun bind(progress: NarrationProgress?) {
        val job = progress?.job ?: return
        if (job.id != jobId) return
        status?.text =
            if (progress.preparing) {
                "Starting · loading the voice"
            } else {
                NarrationScreenPlanning.generatingTitle(progress) + " · ${progress.done} of ${progress.total} sentences"
            }
        line?.apply {
            visibility = View.VISIBLE
            fraction = if (progress.preparing) 0f else progress.fraction
        }
    }
}

// Online checks for the user's computers while Listen is visible (see NarrationServerMonitor).
private const val SERVER_CHECK_INTERVAL_MS = 30_000L
private const val SERVER_CHECK_TICK_MS = 5_000L
