package com.vinicius741.webnovelarchiver.feature.narration

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.feature.details.showStoryChapters
import com.vinicius741.webnovelarchiver.feature.player.PlayerProgressLine
import com.vinicius741.webnovelarchiver.feature.player.RepositoryPlayerData
import com.vinicius741.webnovelarchiver.feature.player.ServicePlaybackControls
import com.vinicius741.webnovelarchiver.feature.player.showPlayer
import com.vinicius741.webnovelarchiver.feature.settings.showTtsSettings
import com.vinicius741.webnovelarchiver.narration.NarrationJobState
import com.vinicius741.webnovelarchiver.narration.NarrationProgress
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.AppBarAction
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.OptionsDialogItem
import com.vinicius741.webnovelarchiver.ui.SCROLL_BODY_VIEW_TAG
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.bindWhileAttached
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeCard
import com.vinicius741.webnovelarchiver.ui.makeEmptyState
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.showStyledOptionsDialog
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import com.vinicius741.webnovelarchiver.ui.toast
import com.vinicius741.webnovelarchiver.ui.verticalFill

/**
 * The Listen hub's selected section and per-section scroll. View memory only: it outlives the
 * screen's model, so leaving Listen and coming back reopens the section the user left (main thread).
 */
private var listenSegment = ListenSegment.QUEUE
private val listenScrollY = mutableMapOf<ListenSegment, Int>()

/** Width from which the sections sit side by side and the segmented control goes away. */
private const val SIDE_BY_SIDE_MIN_DP = 720

/** The Listen screen's model, kept while Listen is in the history. */
private fun ScreenHost.listenModel(): ListenScreenModel =
    screenModels.get(AppRoute.Narration.stableKey) {
        ListenScreenModel(
            RepositoryListenData(narration, RepositoryPlayerData(repository, narration, ttsEngine), repository),
            ServicePlaybackControls(this),
        )
    }

/**
 * The Listen destination (v3): the Now playing / Continue listening card, then Queue (the
 * generation queue grouped by story, with the live banner, reordering, retry and removal) and
 * Generated (every story's audio and its storage). Where audio generates lives in Settings ›
 * Listening. A segmented control picks one section on a phone; the inner display shows both.
 * Built from [ListenScreenModel]'s state; see [ListenRenderer] for how it follows it.
 */
internal fun ScreenHost.showNarration() {
    rerender = { showNarration() }
    // The app bar's actions depend on the state, so the model is looked up before screen() builds
    // the bar. screen() navigates to this route before it prunes the store, so the model is kept.
    val model = listenModel()
    ListenRenderer(this, model, model.refresh()).render()
}

/**
 * One render of the Listen screen, bound to [model] while its views are on screen. A new
 * [ListenContent] (the queue, the packs or the running job changed) rebuilds the screen; the live
 * banner, the running job's row, the top card and the compression status are patched in place on
 * every update, so a progress tick never rebuilds anything.
 */
private class ListenRenderer(
    private val host: ScreenHost,
    private val model: ListenScreenModel,
    /** The state this render shows. */
    private var applied: ListenUiState,
) {
    private val app = host.app
    private val content = applied.content
    private val twoPane = app.resources.configuration.screenWidthDp >= SIDE_BY_SIDE_MIN_DP

    // Idle says nothing the empty queue does not, so the banner shows only while something happens.
    private val liveStatus =
        NarrationLiveStatus(
            app,
            hideWhenIdle = true,
            onPause = model::pauseGeneration,
            onResume = model::resumeGeneration,
        ) { banner -> banner.storyId?.let { host.showStoryChapters(it) } }
    private val runningRow = RunningJobRow()
    private val nowCard =
        ListenNowCard(host, onPlayPause = model::playPause, onResume = model::resume, onOpenPlayer = { host.showPlayer() })
    private val compression = CompressionStatusView(app)

    fun render() {
        val scroller =
            ScrollView(app).apply {
                isFillViewport = true
                clipToPadding = false
                tag = SCROLL_BODY_VIEW_TAG
                setOnScrollChangeListener { _, _, y, _, _ -> listenScrollY[listenSegment] = y }
            }
        val actions =
            buildList {
                add(AppBarAction(R.drawable.wna_settings, "Listening settings") { host.showTtsSettings() })
                if (content.queue.jobs.isNotEmpty()) add(AppBarAction(R.drawable.wna_more_vert, "Queue options") { showQueueMenu() })
            }
        host.screen(route = AppRoute.Narration, title = "Listen", subtitle = "Player and audio generation", actions = actions) {
            val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            column.addView(nowCard.view)
            // On a phone the banner sits above the sections, so a paused or failed queue shows on all of them.
            if (!twoPane) {
                column.addView(
                    liveStatus.view,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = context.dp(Space.MD)
                    },
                )
            }
            val queueBox = section { queueSection() }
            val generatedBox = section { generatedSection(host, model, content, compression) }
            if (twoPane) {
                column.addView(sideBySide(queueBox, generatedBox))
            } else {
                val boxes = mapOf(ListenSegment.QUEUE to queueBox, ListenSegment.GENERATED to generatedBox)

                fun show(segment: ListenSegment) =
                    boxes.forEach { (key, box) ->
                        box.visibility =
                            if (key == segment) View.VISIBLE else View.GONE
                    }
                val counts = mapOf(ListenSegment.QUEUE to content.queue.jobs.size, ListenSegment.GENERATED to content.audio.attached.size)
                val segments =
                    ListenSegmentedControl(context, listenSegment, counts) { segment ->
                        listenSegment = segment
                        show(segment)
                        scroller.post { scroller.scrollTo(0, listenScrollY[segment] ?: 0) }
                    }
                column.addView(
                    segments.view,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = context.dp(Space.LG)
                    },
                )
                boxes.values.forEach { column.addView(it) }
                show(listenSegment)
            }
            scroller.addView(column)
            addView(scroller, verticalFill())
        }
        val restoreY = listenScrollY[listenSegment] ?: 0
        if (restoreY > 0) scroller.post { scroller.scrollTo(0, restoreY) }
        patch(applied)
        scroller.bindWhileAttached(host.scope, model.state) { apply(it) }
        scroller.bindWhileAttached(host.scope, model.messages) { host.toast(it) }
    }

    private fun apply(next: ListenUiState) {
        if (next == applied) return
        applied = next
        if (next.content.key != content.key) {
            if (host.navigator.current == AppRoute.Narration) host.showNarration()
            return
        }
        patch(next)
    }

    /** The parts that change without a rebuild. */
    private fun patch(state: ListenUiState) {
        liveStatus.bind(state.banner)
        runningRow.bind(state.progress)
        nowCard.bind(state.now)
        compression.bind(state.compression.status, state.compression.totalBytes)
    }

    private fun LinearLayout.queueSection() {
        if (twoPane) {
            addView(liveStatus.view.apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = context.dp(Space.MD) })
        }
        if (!app.isIgnoringBatteryOptimizations()) batteryCard()
        if (content.showGenerateOn) {
            listenSectionLabel("Generate on")
            generateOnChips(content.queue, model::chooseGenerateOn)
        }
        val jobs = content.queue.jobs
        val failed = content.failed
        if (jobs.isEmpty()) {
            listenSectionLabel("Queue")
            addView(
                makeEmptyState(
                    context,
                    title = "Queue is empty",
                    message = NarrationScreenPlanning.HOW_TO_GENERATE,
                    iconRes = R.drawable.wna_headphones,
                ),
            )
            return
        }
        listenSectionLabel(
            "Queue · ${NarrationScreenPlanning.chapters(jobs.size)}",
            aside = if (failed > 0) "Retry failed" else null,
            onAside = if (failed > 0) model::retryAll else null,
        )
        listenHint("Chapters generate in this order. Moving a story first switches at the next sentence.")
        content.cards.forEach { card -> addView(queueStoryCard(host, model, card, content.runningJobId, runningRow)) }
    }

    private fun LinearLayout.batteryCard() {
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
                addView(
                    texts,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd =
                            context.dp(Space.SM)
                    },
                )
                addView(makeButton(context, "Allow", Btn.TONAL) { app.requestIgnoreBatteryOptimizations() })
            }
        addView(
            card,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(Space.XS)
            },
        )
    }

    private fun showQueueMenu() {
        val queue = model.state.value.content.queue
        val jobs = queue.jobs
        val failed = jobs.count { it.state == NarrationJobState.FAILED }
        val items = mutableListOf<OptionsDialogItem>()
        if (queue.paused) {
            items += OptionsDialogItem.Option("Resume generating") { model.resumeGeneration() }
        } else {
            items += OptionsDialogItem.Option("Pause generating") { model.pauseGeneration() }
        }
        if (failed > 0) {
            items += OptionsDialogItem.Option("Retry failed ($failed)") { model.retryAll() }
            items += OptionsDialogItem.Option("Clear failed ($failed)") { model.clearFailed() }
        }
        items += OptionsDialogItem.Divider
        items +=
            OptionsDialogItem.Option("Clear the queue (${NarrationScreenPlanning.chapters(jobs.size)})", destructive = true) {
                host.confirm(
                    "Stop generating and remove all ${NarrationScreenPlanning.chapters(jobs.size)} from the queue? " +
                        "Audio already generated stays.",
                    confirmLabel = "Clear",
                ) { model.clearQueue() }
            }
        host.showStyledOptionsDialog("Queue options", items)
    }
}

private fun LinearLayout.section(block: LinearLayout.() -> Unit): LinearLayout =
    LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        block()
    }

/** Queue on the left; Generated on the right. */
private fun LinearLayout.sideBySide(
    queue: View,
    generated: View,
): View =
    LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        val right = generated
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
