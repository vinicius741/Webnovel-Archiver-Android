package com.vinicius741.webnovelarchiver.feature.narration

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.narration.NarrationJob
import com.vinicius741.webnovelarchiver.narration.NarrationJobState
import com.vinicius741.webnovelarchiver.narration.NarrationPack
import com.vinicius741.webnovelarchiver.narration.NarrationPlanning
import com.vinicius741.webnovelarchiver.narration.NarrationProgress
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.OptionsDialogItem
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.button
import com.vinicius741.webnovelarchiver.ui.chip
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.flow
import com.vinicius741.webnovelarchiver.ui.fullButton
import com.vinicius741.webnovelarchiver.ui.makeProgress
import com.vinicius741.webnovelarchiver.ui.row
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.section
import com.vinicius741.webnovelarchiver.ui.settingRow
import com.vinicius741.webnovelarchiver.ui.showStyledOptionsDialog
import com.vinicius741.webnovelarchiver.ui.spacer
import com.vinicius741.webnovelarchiver.ui.text
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Generated chapter audio at a glance: what is generating, the queue, keep-ahead, storage, and
 * every story with audio (including audio whose story has left the library).
 */
internal fun ScreenHost.showNarration() {
    rerender = { showNarration() }
    val coordinator = narration
    val document = coordinator.queueState.value
    val host = this
    var progressViews: ProgressViews? = null
    screen(route = AppRoute.Narration, title = "Narration", scrollable = true) {
        progressViews = statusCard(host, document.paused, document.pauseReason, document.jobs)
        if (!app.isIgnoringBatteryOptimizations()) {
            addCard {
                text("Generating with the screen off", Type.TITLE_SMALL)
                spacer(Space.XS)
                text(
                    "Android pauses background work while the phone sleeps. Allow this app to run without battery " +
                        "restrictions so queued chapters keep generating with the screen off.",
                    Type.BODY_SMALL,
                    ThemeManager.colors.onSurfaceVariant,
                )
                fullButton("Allow", Btn.TONAL, bottomMarginDp = 0) { app.requestIgnoreBatteryOptimizations() }
            }
        }
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
                chip(label, selected = document.keepAhead == count) {
                    narrationAction("Could not save the setting") { setKeepAhead(count) }
                }
            }
        }
        queueSection(host, document.jobs)
        generatedSection(host)
    }
    progressViews?.bind(coordinator.progress.value)
    screenObserver =
        scope.launch {
            launch {
                coordinator.progress.collect { progressViews?.bind(it) }
            }
            combine(coordinator.queueState, coordinator.packsRevision) { queue, packs -> queue to packs }
                .distinctUntilChanged()
                .drop(1)
                .collect { if (navigator.current == AppRoute.Narration) showNarration() }
        }
}

/** The status card's live parts, patched in place as each sentence is generated. */
private class ProgressViews(
    val title: TextView,
    val detail: TextView,
    val bar: View,
    val idleText: String,
) {
    fun bind(progress: NarrationProgress?) {
        if (progress == null) {
            title.text = idleText
            detail.visibility = View.GONE
            bar.visibility = View.GONE
            return
        }
        title.text = "Generating · ${progress.storyTitle}"
        detail.text =
            buildString {
                append(progress.chapterTitle)
                append("\n${progress.done} of ${progress.total} sentences")
                progress.etaMs?.let { append(" · ~${NarrationPlanning.formatDuration(it)} left in the queue") }
                append("\nVoice: ${progress.job?.voice?.label.orEmpty()}")
            }
        detail.visibility = View.VISIBLE
        bar.visibility = View.VISIBLE
        (bar as? android.widget.ProgressBar)?.progress = (progress.fraction * 100).toInt()
    }
}

private fun LinearLayout.statusCard(
    host: ScreenHost,
    paused: Boolean,
    pauseReason: String?,
    jobs: List<NarrationJob>,
): ProgressViews {
    lateinit var views: ProgressViews
    val queued = jobs.count { it.state == NarrationJobState.QUEUED }
    val idle =
        when {
            paused -> "Paused" + (pauseReason?.let { " · $it" } ?: "")
            queued > 0 -> "Starting…"
            else -> "Nothing to generate"
        }
    addCard {
        val title = text(idle, Type.TITLE_SMALL)
        spacer(Space.XS)
        val detail = text("", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant)
        val bar = makeProgress(context, 0f).also { addView(it) }
        views = ProgressViews(title, detail, bar, idle)
        spacer(Space.SM)
        val used = host.narration.store.totalBytes()
        text(
            "Audio uses ${NarrationPlanning.formatBytes(used)} · ${NarrationPlanning.formatBytes(host.narration.freeBytes())} free",
            Type.BODY_SMALL,
            ThemeManager.colors.onSurfaceVariant,
        )
        if (queued > 0 || paused) {
            row {
                if (paused) {
                    button("Resume", Btn.FILLED, R.drawable.wna_play) { host.narrationAction("Could not resume") { resume() } }
                } else {
                    button("Pause", Btn.TONAL, R.drawable.wna_pause) { host.narrationAction("Could not pause") { pause() } }
                }
            }
        }
    }
    return views
}

private fun LinearLayout.queueSection(
    host: ScreenHost,
    jobs: List<NarrationJob>,
) {
    if (jobs.isEmpty()) return
    section("Queue · ${jobs.size}")
    val failed = jobs.count { it.state == NarrationJobState.FAILED }
    if (failed > 0) {
        row {
            button("Retry failed", Btn.TONAL, R.drawable.wna_refresh) { host.narrationAction("Could not retry") { retryAll() } }
            button("Clear failed", Btn.TEXT) { host.narrationAction("Could not clear") { clearFailed() } }
        }
    }
    val running =
        host.narration.progress.value
            ?.job
            ?.id
    jobs.take(MAX_QUEUE_ROWS).forEach { job ->
        val state =
            when {
                job.id == running -> "Generating"
                job.state == NarrationJobState.FAILED -> "Failed: ${job.error.orEmpty()}"
                else -> "Queued · ${job.voice.label}"
            }
        val storyTitle = host.repository.story(job.storyId)?.title ?: "Story no longer in the library"
        settingRow(R.drawable.wna_headphones, "${host.narration.chapterTitle(job.storyId, job.chapterId)} · $storyTitle", state) {
            host.showJobMenu(job)
        }
    }
    if (jobs.size > MAX_QUEUE_ROWS) {
        text("and ${jobs.size - MAX_QUEUE_ROWS} more", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant)
    }
}

private fun ScreenHost.showJobMenu(job: NarrationJob) {
    val items = mutableListOf<OptionsDialogItem>()
    items += OptionsDialogItem.Option("Generate next") { narrationAction("Could not move the chapter") { moveToTop(job.id) } }
    if (job.state == NarrationJobState.FAILED) {
        items += OptionsDialogItem.Option("Retry") { narrationAction("Could not retry") { retry(job.id) } }
    }
    if (repository.story(job.storyId) != null) {
        items += OptionsDialogItem.Option("Open story audio") { showNarrationStory(job.storyId, job.chapterId) }
    }
    items += OptionsDialogItem.Divider
    items +=
        OptionsDialogItem.Option("Remove from queue", destructive = true) { narrationAction("Could not remove") { remove(setOf(job.id)) } }
    showStyledOptionsDialog(narration.chapterTitle(job.storyId, job.chapterId), items)
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

private const val MAX_QUEUE_ROWS = 100
