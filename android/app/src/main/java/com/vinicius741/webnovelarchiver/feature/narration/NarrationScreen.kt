@file:Suppress("TooManyFunctions") // One screen: its sections and menus are small on purpose.

package com.vinicius741.webnovelarchiver.feature.narration

import android.text.TextUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.narration.NarrationJob
import com.vinicius741.webnovelarchiver.narration.NarrationJobState
import com.vinicius741.webnovelarchiver.narration.NarrationPack
import com.vinicius741.webnovelarchiver.narration.NarrationPlanning
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
import com.vinicius741.webnovelarchiver.ui.button
import com.vinicius741.webnovelarchiver.ui.chip
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.flow
import com.vinicius741.webnovelarchiver.ui.fullButton
import com.vinicius741.webnovelarchiver.ui.makeProgress
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.row
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.section
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.settingRow
import com.vinicius741.webnovelarchiver.ui.showStyledOptionsDialog
import com.vinicius741.webnovelarchiver.ui.spacer
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.verticalFill
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Scroll position of the queue screen across its rebuilds (each queue change rebuilds it). */
private var narrationScrollY = 0

/**
 * Audio generation across every story: the live banner pinned on top (pause, resume), the queue
 * grouped by story with reordering, keep-ahead, storage, and every story with generated audio
 * (including audio whose story has left the library).
 */
internal fun ScreenHost.showNarration() {
    rerender = { showNarration() }
    val coordinator = narration
    val document = coordinator.queueState.value
    val host = this
    val liveStatus = NarrationLiveStatus(this, hideWhenIdle = false) { banner -> banner.storyId?.let { showNarrationStory(it) } }
    val runningRow = RunningRow()
    var serverRows: ServerRows? = null
    val scroller =
        ScrollView(app).apply {
            isFillViewport = true
            clipToPadding = false
            tag = SCROLL_BODY_VIEW_TAG
            setOnScrollChangeListener { _, _, y, _, _ -> narrationScrollY = y }
        }
    val actions =
        if (document.jobs.isEmpty()) {
            emptyList()
        } else {
            listOf(
                AppBarAction(R.drawable.wna_more_vert, "Queue options") { host.showQueueMenu() },
            )
        }
    screen(route = AppRoute.Narration, title = "Audio generation", actions = actions) {
        addView(liveStatus.view)
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        with(content) {
            queueSection(host, document.jobs, runningRow)
            if (!app.isIgnoringBatteryOptimizations()) batteryCard(host)
            serverRows = generateOnSection(host, document)
            spacer(Space.MD)
            keepAheadSection(host, document.keepAhead)
            storageLine(host)
            generatedSection(host)
        }
        scroller.addView(content)
        addView(scroller, verticalFill())
    }
    val restoreY = narrationScrollY
    if (restoreY > 0) scroller.post { scroller.scrollTo(0, restoreY) }
    runningRow.bind(coordinator.progress.value)
    screenObserver =
        scope.launch {
            liveStatus.observe(host, this)
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
            combine(coordinator.queueState, coordinator.packsRevision, runningJob) { queue, packs, job -> Triple(queue, packs, job) }
                .distinctUntilChanged()
                .drop(1)
                .collect { if (navigator.current == AppRoute.Narration) showNarration() }
        }
}

/** The queue row of the chapter being generated, patched on every sentence. */
private class RunningRow {
    var jobId: String? = null
    var status: TextView? = null
    var bar: ProgressBar? = null

    fun bind(progress: NarrationProgress?) {
        val job = progress?.job ?: return
        if (job.id != jobId) return
        status?.text =
            if (progress.preparing) {
                "Starting · loading the voice"
            } else {
                NarrationScreenPlanning.generatingTitle(progress) + " · ${progress.done} of ${progress.total} sentences"
            }
        bar?.apply {
            visibility = View.VISIBLE
            isIndeterminate = progress.preparing
            this.progress = (progress.fraction * PERCENT).toInt()
        }
    }
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
    showStyledOptionsDialog("Queue", items)
}

private fun LinearLayout.queueSection(
    host: ScreenHost,
    jobs: List<NarrationJob>,
    runningRow: RunningRow,
) {
    if (jobs.isEmpty()) {
        section("Queue")
        text(
            "Nothing is queued. Open a story's Chapter audio (Details → More options → Narration audio, or Read aloud) to generate chapters.",
            Type.BODY_SMALL,
            ThemeManager.colors.onSurfaceVariant,
        )
        return
    }
    section("Queue · ${NarrationScreenPlanning.chapters(jobs.size)}")
    text(
        "Stories generate in this order. Putting another story or chapter first switches at the next sentence; " +
            "the interrupted chapter continues from where it stopped.",
        Type.BODY_SMALL,
        ThemeManager.colors.onSurfaceVariant,
    )
    spacer(Space.SM)
    val running =
        host.narration.progress.value
            ?.job
            ?.id
    val groups = NarrationScreenPlanning.groups(jobs)
    groups.forEachIndexed { index, group ->
        addCard {
            val story = host.repository.story(group.storyId)
            val title = story?.title ?: "Story no longer in the library"
            val queued = group.jobs.size - group.failed
            val summary =
                buildList {
                    if (queued > 0) add("$queued queued")
                    if (group.failed > 0) add("${group.failed} failed")
                }.joinToString(" · ")
            settingRow(R.drawable.wna_headphones, title, summary) { host.showStoryQueueMenu(group, isFirst = index == 0) }
            group.jobs.take(MAX_ROWS_PER_STORY).forEach { job ->
                jobRow(host, job, isRunning = job.id == running, runningRow)
            }
            if (group.jobs.size > MAX_ROWS_PER_STORY) {
                text("and ${group.jobs.size - MAX_ROWS_PER_STORY} more", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant)
            }
            if (index > 0 && queued > 0) {
                fullButton("Generate this story first", Btn.TEXT, R.drawable.wna_up, bottomMarginDp = 0) {
                    host.narrationAction("Could not reorder the queue") { moveStoryToTop(group.storyId) }
                }
            }
        }
    }
}

private fun LinearLayout.jobRow(
    host: ScreenHost,
    job: NarrationJob,
    isRunning: Boolean,
    runningRow: RunningRow,
) {
    val title = makeText(context, host.narration.chapterTitle(job.storyId, job.chapterId), Type.BODY_MEDIUM, ThemeManager.colors.onSurface)
    title.maxLines = 2
    title.ellipsize = TextUtils.TruncateAt.END
    val failed = job.state == NarrationJobState.FAILED
    val status =
        makeText(
            context,
            when {
                isRunning -> "Generating"
                failed -> "Failed · ${job.error.orEmpty()}"
                else -> "Queued · ${job.voice.label}"
            },
            Type.BODY_SMALL,
            when {
                failed -> ThemeManager.colors.error
                isRunning -> ThemeManager.colors.primary
                else -> ThemeManager.colors.onSurfaceVariant
            },
        )
    val bar = makeProgress(context, 0f, visible = false) as ProgressBar
    addView(
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(Space.SM), context.dp(Space.SM), context.dp(Space.SM), context.dp(Space.SM))
            background = selectableRipple(ThemeManager.colors.primary and RIPPLE_ALPHA_MASK)
            addView(title)
            addView(status)
            addView(bar, (bar.layoutParams as LinearLayout.LayoutParams).apply { topMargin = context.dp(Space.XS) })
            setOnClickListener { host.showJobMenu(job, isRunning) }
        },
    )
    if (isRunning) {
        runningRow.jobId = job.id
        runningRow.status = status
        runningRow.bar = bar
    }
}

private fun ScreenHost.showStoryQueueMenu(
    group: QueueGroup,
    isFirst: Boolean,
) {
    val items = mutableListOf<OptionsDialogItem>()
    if (!isFirst) {
        items +=
            OptionsDialogItem.Option(
                "Generate this story first",
            ) { narrationAction("Could not reorder the queue") { moveStoryToTop(group.storyId) } }
    }
    if (group.failed > 0) {
        items +=
            OptionsDialogItem.Option("Retry failed (${group.failed})") {
                narrationAction("Could not retry") {
                    group.jobs.filter { it.state == NarrationJobState.FAILED }.forEach { retry(it.id) }
                }
            }
    }
    if (repository.story(group.storyId) != null) {
        items += OptionsDialogItem.Option("Open chapter audio") { showNarrationStory(group.storyId) }
    }
    items += OptionsDialogItem.Divider
    items +=
        OptionsDialogItem.Option("Remove this story from the queue (${group.jobs.size})", destructive = true) {
            narrationAction("Could not remove") { remove(group.jobs.map { it.id }.toSet()) }
        }
    showStyledOptionsDialog(repository.story(group.storyId)?.title ?: "Story", items)
}

private fun ScreenHost.showJobMenu(
    job: NarrationJob,
    isRunning: Boolean,
) {
    val items = mutableListOf<OptionsDialogItem>()
    if (!isRunning) {
        items += OptionsDialogItem.Option("Generate next") { narrationAction("Could not move the chapter") { moveToTop(job.id) } }
    }
    if (job.state == NarrationJobState.FAILED) {
        items += OptionsDialogItem.Option("Retry") { narrationAction("Could not retry") { retry(job.id) } }
    }
    if (repository.story(job.storyId) != null) {
        items += OptionsDialogItem.Option("Open chapter audio") { showNarrationStory(job.storyId, job.chapterId) }
    }
    items += OptionsDialogItem.Divider
    items +=
        OptionsDialogItem.Option(if (isRunning) "Stop and remove from queue" else "Remove from queue", destructive = true) {
            narrationAction("Could not remove") { remove(setOf(job.id)) }
        }
    showStyledOptionsDialog(narration.chapterTitle(job.storyId, job.chapterId), items)
}

private fun LinearLayout.batteryCard(host: ScreenHost) {
    addCard {
        text("Generating with the screen off", Type.TITLE_SMALL)
        spacer(Space.XS)
        text(
            "Android pauses background work while the phone sleeps. Allow this app to run without battery " +
                "restrictions so queued chapters keep generating with the screen off.",
            Type.BODY_SMALL,
            ThemeManager.colors.onSurfaceVariant,
        )
        fullButton("Allow", Btn.TONAL, bottomMarginDp = 0) { host.app.requestIgnoreBatteryOptimizations() }
    }
}

private fun LinearLayout.keepAheadSection(
    host: ScreenHost,
    keepAhead: Int,
) {
    section("Keep ahead while listening")
    text(
        "While a story plays, the chapters after it are queued so they are ready in time.",
        Type.BODY_SMALL,
        ThemeManager.colors.onSurfaceVariant,
    )
    spacer(Space.SM)
    flow {
        NarrationPlanning.KEEP_AHEAD_CHOICES.forEach { count ->
            val label =
                when (count) {
                    0 -> "Off"
                    1 -> "1 chapter"
                    else -> "$count chapters"
                }
            chip(label, selected = keepAhead == count) {
                host.narrationAction("Could not save the setting") { setKeepAhead(count) }
            }
        }
    }
}

private fun LinearLayout.storageLine(host: ScreenHost) {
    spacer(Space.MD)
    text(
        "Audio uses ${NarrationPlanning.formatBytes(host.narration.store.totalBytes())} · " +
            "${NarrationPlanning.formatBytes(host.narration.freeBytes())} free",
        Type.BODY_SMALL,
        ThemeManager.colors.onSurfaceVariant,
    )
}

private fun LinearLayout.generatedSection(host: ScreenHost) {
    val packs = host.narration.store.allPacks()
    val detached = host.narration.detachedPacks()
    val attached = packs.filter { pack -> detached[pack.storyId]?.contains(pack) != true }.groupBy { it.storyId }
    section("Generated audio")
    if (attached.isEmpty()) {
        text(
            "No chapter audio yet. Open a chapter and tap Read aloud to generate it.",
            Type.BODY_SMALL,
            ThemeManager.colors.onSurfaceVariant,
        )
    }
    attached.entries
        .sortedByDescending { entry -> entry.value.maxOf { it.index.generatedAt } }
        .forEach { (storyId, storyPacks) ->
            val title = host.repository.story(storyId)?.title ?: storyId
            settingRow(R.drawable.wna_headphones, title, packSummary(storyPacks)) { host.showNarrationStory(storyId) }
        }
    if (detached.isNotEmpty()) {
        section("Detached audio")
        text(
            "Audio for chapters no longer in the library, for example after restoring an older backup. It is kept until you delete it.",
            Type.BODY_SMALL,
            ThemeManager.colors.onSurfaceVariant,
        )
        detached.forEach { (storyId, storyPacks) ->
            val title = host.repository.story(storyId)?.title ?: "Deleted story"
            settingRow(R.drawable.wna_delete, title, packSummary(storyPacks)) {
                host.confirm(
                    "Delete this audio (${NarrationPlanning.formatBytes(storyPacks.sumOf { it.index.bytes })})?",
                    confirmLabel = "Delete",
                ) {
                    host.narrationAction("Could not delete the audio") { deleteAudio(storyId, storyPacks.map { it.chapterId }) }
                }
            }
        }
    }
}

private fun packSummary(packs: List<NarrationPack>): String {
    val voices = packs.map { it.recipe.voice.label }.distinct().joinToString(", ")
    val duration = NarrationPlanning.formatDuration(packs.sumOf { it.index.totalDurationMs })
    return "${packs.size} ${if (packs.size == 1) "chapter" else "chapters"} · $duration · " +
        "${NarrationPlanning.formatBytes(packs.sumOf { it.index.bytes })} · $voices"
}

private const val MAX_ROWS_PER_STORY = 5
private const val SERVER_CHECK_INTERVAL_MS = 30_000L
private const val SERVER_CHECK_TICK_MS = 5_000L
private const val PERCENT = 100
private const val RIPPLE_ALPHA_MASK = 0x22FFFFFF
