@file:Suppress("TooManyFunctions") // One screen: its builders, menus and selection bar are small on purpose.

package com.vinicius741.webnovelarchiver.feature.narration

import android.os.Parcelable
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.feature.narration.NarrationScreenPlanning.needsAudio
import com.vinicius741.webnovelarchiver.feature.reader.showReader
import com.vinicius741.webnovelarchiver.feature.settings.showTtsSettings
import com.vinicius741.webnovelarchiver.narration.NarrationChapterStatus
import com.vinicius741.webnovelarchiver.narration.NarrationJobState
import com.vinicius741.webnovelarchiver.narration.NarrationOrigin
import com.vinicius741.webnovelarchiver.narration.NarrationPack
import com.vinicius741.webnovelarchiver.narration.NarrationPlanning
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.TtsContinueListening
import com.vinicius741.webnovelarchiver.tts.TtsForegroundService
import com.vinicius741.webnovelarchiver.ui.AppBarAction
import com.vinicius741.webnovelarchiver.ui.BOTTOM_BAR_VIEW_TAG
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.OptionsDialogItem
import com.vinicius741.webnovelarchiver.ui.SCROLL_BODY_VIEW_TAG
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.button
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.fullButton
import com.vinicius741.webnovelarchiver.ui.makeChip
import com.vinicius741.webnovelarchiver.ui.row
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.setAppBarTitle
import com.vinicius741.webnovelarchiver.ui.showStyledOptionsDialog
import com.vinicius741.webnovelarchiver.ui.spacer
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.toast
import com.vinicius741.webnovelarchiver.ui.verticalFill
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** What the Chapter audio screen keeps per story while the app runs: filter, selection and list position. */
private class ChapterAudioViewState {
    var filter: ChapterAudioFilter? = null
    var selecting = false
    val selected = LinkedHashSet<String>()
    var anchor: String? = null
    var listState: Parcelable? = null
    var scrolledToFocus: String? = null
    var statuses: Map<String, NarrationChapterStatus> = emptyMap()
    var visible: List<Chapter> = emptyList()
}

private val chapterAudioStates = HashMap<String, ChapterAudioViewState>()

/**
 * One story's chapter audio: what "Read aloud" opens when a chapter is not generated yet. The live
 * generation banner stays pinned at the top; the list starts at the bookmark, and long-pressing a
 * chapter selects chapters to generate, regenerate or delete (a second long-press selects the range).
 */
internal fun ScreenHost.showNarrationStory(
    storyId: String,
    chapterId: String? = null,
) {
    val story = repository.story(storyId) ?: return showNarration()
    val route = AppRoute.NarrationStory(storyId, chapterId)
    val state = chapterAudioStates.getOrPut(storyId) { ChapterAudioViewState() }
    rerender = { showNarrationStory(storyId, chapterId) }
    val host = this
    val playable = story.chapters.filter(TtsContinueListening::isPlayable)
    state.filter = resolvedFilter(state.filter, story, playable, chapterId)
    val layoutManager = LinearLayoutManager(app)
    val list =
        RecyclerView(app).apply {
            this.layoutManager = layoutManager
            clipToPadding = false
            // The mini-player pads the screen's scroll body so the last rows stay reachable.
            tag = SCROLL_BODY_VIEW_TAG
        }

    fun rebuild() {
        state.listState = layoutManager.onSaveInstanceState()
        showNarrationStory(storyId, chapterId)
    }

    val selection =
        ChapterAudioSelection(state) { selectedCount ->
            setAppBarTitle(selectionTitle(selectedCount))
        }
    val header = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
    val adapter =
        ChapterAudioAdapter(
            header = header,
            bookmarkId = story.lastReadChapterId,
            selection = selection,
            onTap = { chapter, status -> host.showChapterMenu(storyId, chapter, status) },
            onLongPress = { chapter ->
                if (state.selecting) {
                    selection.selectRange(chapter.id)
                } else {
                    state.selecting = true
                    state.selected.clear()
                    state.selected += chapter.id
                    state.anchor = chapter.id
                    rebuild()
                }
            },
        )
    list.adapter = adapter
    selection.onChanged = { adapter.refreshSelection() }
    val liveStatus = NarrationLiveStatus(this, hideWhenIdle = true) { showNarration() }
    val chips = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL }

    fun exitSelection() {
        state.selecting = false
        state.selected.clear()
        state.anchor = null
        rebuild()
    }

    val actions =
        if (state.selecting) {
            listOf(AppBarAction(R.drawable.wna_check, "Select all shown") { selection.toggleAll() })
        } else {
            listOf(
                AppBarAction(R.drawable.wna_check, "Select chapters") {
                    state.selecting = true
                    rebuild()
                },
                AppBarAction(R.drawable.wna_list, "Generation queue") { showNarration() },
                AppBarAction(R.drawable.wna_more_vert, "More") { host.showStoryAudioMenu(story, playable, state.statuses) },
            )
        }
    screen(
        route = route,
        title = if (state.selecting) selectionTitle(state.selected.size) else "Chapter audio",
        subtitle = story.title,
        actions = actions,
        closeAction = if (state.selecting) ({ exitSelection() }) else null,
    ) {
        addView(liveStatus.view)
        addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(chips)
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(Space.SM)
            },
        )
        addView(list, verticalFill())
        if (state.selecting) addView(host.selectionBar(story, state, ::exitSelection))
    }

    fun applyFilter(filter: ChapterAudioFilter) {
        if (state.filter == filter) return
        state.filter = filter
        state.listState = null
        rerender?.invoke()
    }

    val coordinator = narration
    screenObserver =
        scope.launch {
            liveStatus.observe(host, this)
            launch {
                // Every sentence patches the generating row in place, without re-reading statuses.
                coordinator.progress.collect { progress ->
                    val job = progress?.job ?: return@collect
                    if (job.storyId != storyId || progress.preparing) return@collect
                    adapter.updateProgress(job.chapterId, progress.fraction)
                }
            }
            val runningJob = coordinator.progress.map { it?.job?.id to (it?.preparing == true) }.distinctUntilChanged()
            combine(coordinator.queueState, coordinator.packsRevision, runningJob) { _, _, _ -> Unit }
                .collectLatest {
                    val latest = repository.story(storyId) ?: return@collectLatest
                    val statuses = runCatching { coordinator.statuses(latest) }.getOrNull() ?: return@collectLatest
                    if (navigator.current != route) return@collectLatest
                    val chapters = latest.chapters.filter(TtsContinueListening::isPlayable)
                    val filter = state.filter ?: ChapterAudioFilter.ALL
                    state.statuses = statuses
                    state.visible = NarrationScreenPlanning.filter(latest, chapters, statuses, filter)
                    host.renderFilterChips(chips, latest, chapters, statuses, filter, ::applyFilter)
                    header.removeAllViews()
                    host.buildStoryHeader(header, latest, chapterId, statuses, state)
                    adapter.submit(state.visible, statuses)
                    restorePosition(state, layoutManager, chapterId)
                }
        }
}

/** The remembered filter, unless it would hide the chapter the screen was opened for or no bookmark is left. */
private fun resolvedFilter(
    remembered: ChapterAudioFilter?,
    story: Story,
    playable: List<Chapter>,
    focusId: String?,
): ChapterAudioFilter {
    val fallback = NarrationScreenPlanning.defaultFilter(story, playable, focusId)
    return when {
        remembered == null -> fallback
        remembered == ChapterAudioFilter.FROM_BOOKMARK && fallback != ChapterAudioFilter.FROM_BOOKMARK -> fallback
        else -> remembered
    }
}

private fun restorePosition(
    state: ChapterAudioViewState,
    layoutManager: LinearLayoutManager,
    focusId: String?,
) {
    val saved = state.listState
    if (saved != null) {
        layoutManager.onRestoreInstanceState(saved)
        state.listState = null
        return
    }
    if (focusId == null || state.scrolledToFocus == focusId) return
    val index = state.visible.indexOfFirst { it.id == focusId }
    if (index < 0) return
    // +1 skips the header row; the offset leaves the row clear of the banner's shadow.
    layoutManager.scrollToPositionWithOffset(index + 1, 0)
    state.scrolledToFocus = focusId
}

private fun selectionTitle(count: Int): String = if (count == 0) "Select chapters" else "$count selected"

/** Selection of chapter rows; it never rebuilds the screen, only retitles it and refreshes rows. */
private class ChapterAudioSelection(
    private val state: ChapterAudioViewState,
    private val onCount: (Int) -> Unit,
) : ChapterRowSelection {
    var onChanged: () -> Unit = {}
    override val active: Boolean get() = state.selecting

    override fun isSelected(chapterId: String): Boolean = chapterId in state.selected

    override fun toggle(chapterId: String) {
        if (!state.selected.remove(chapterId)) state.selected += chapterId
        state.anchor = chapterId
        changed()
    }

    fun selectRange(chapterId: String) {
        state.selected += NarrationScreenPlanning.range(state.visible.map { it.id }, state.anchor, chapterId)
        state.anchor = chapterId
        changed()
    }

    /** Selects every chapter the filter shows; when all are already selected, clears them. */
    fun toggleAll() {
        val shown = state.visible.map { it.id }
        if (shown.isNotEmpty() && state.selected.containsAll(shown)) state.selected.removeAll(shown.toSet()) else state.selected += shown
        changed()
    }

    private fun changed() {
        onCount(state.selected.size)
        onChanged()
    }
}

private fun ScreenHost.renderFilterChips(
    container: LinearLayout,
    story: Story,
    chapters: List<Chapter>,
    statuses: Map<String, NarrationChapterStatus>,
    current: ChapterAudioFilter,
    onPick: (ChapterAudioFilter) -> Unit,
) {
    container.removeAllViews()
    val hasBookmark = NarrationScreenPlanning.bookmarkIndex(story) >= 0
    ChapterAudioFilter.entries.forEach { filter ->
        val count = NarrationScreenPlanning.filter(story, chapters, statuses, filter).size
        val enabled = filter != ChapterAudioFilter.FROM_BOOKMARK || hasBookmark
        val label = if (enabled) "${filter.label} ($count)" else "${filter.label} · no bookmark"
        val chip = makeChip(app, label, filter == current) { if (enabled) onPick(filter) }
        if (!enabled) {
            chip.isEnabled = false
            chip.alpha = DISABLED_ALPHA
        }
        container.addView(
            chip,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(Space.SM)
            },
        )
    }
}

private fun ScreenHost.buildStoryHeader(
    header: LinearLayout,
    story: Story,
    focusId: String?,
    statuses: Map<String, NarrationChapterStatus>,
    state: ChapterAudioViewState,
) {
    val focus = story.chapters.firstOrNull { it.id == focusId && TtsContinueListening.isPlayable(it) }
    val voice = narration.currentVoice()
    val packs = statuses.values.mapNotNull { it.pack() }
    with(header) {
        if (focus != null) focusCard(this@buildStoryHeader, story, focus, statuses[focus.id] ?: NarrationChapterStatus.None)
        queuePositionCard(this@buildStoryHeader, story)
        text(
            "New audio uses ${voice.label}" +
                if (packs.isEmpty()) "" else " · ${packs.size} ready · ${NarrationPlanning.formatBytes(packs.sumOf { it.index.bytes })}",
            Type.BODY_SMALL,
            ThemeManager.colors.onSurfaceVariant,
        )
        text(
            if (state.selecting) {
                "Tap chapters to select them. Long-press another chapter to select everything in between."
            } else {
                "Long-press a chapter to select several. Tap one for its options."
            },
            Type.BODY_SMALL,
            ThemeManager.colors.onSurfaceVariant,
        )
        if (state.visible.isEmpty()) {
            spacer(Space.MD)
            text("No chapters match this filter.", Type.BODY_MEDIUM, ThemeManager.colors.onSurfaceVariant)
        }
        spacer(Space.SM)
    }
}

/** Shown while this story's chapters wait behind another story's, with a way to put this one first. */
private fun LinearLayout.queuePositionCard(
    host: ScreenHost,
    story: Story,
) {
    val jobs = host.narration.queueState.value.jobs
    val waiting = jobs.count { it.storyId == story.id && it.state == NarrationJobState.QUEUED }
    if (waiting == 0) return
    val next = NarrationPlanning.next(jobs) ?: return
    if (next.storyId == story.id) return
    val ahead =
        jobs
            .takeWhile { it.storyId != story.id || it.state != NarrationJobState.QUEUED }
            .count { it.state == NarrationJobState.QUEUED }
    addCard {
        text("${NarrationScreenPlanning.chapters(waiting)} of this story queued", Type.TITLE_SMALL)
        spacer(Space.XS)
        text(
            "${NarrationScreenPlanning.chapters(ahead)} from other stories come first. Putting this story first " +
                "pauses the other chapter at its next sentence; it continues from there later.",
            Type.BODY_SMALL,
            ThemeManager.colors.onSurfaceVariant,
        )
        fullButton("Generate this story first", Btn.TONAL, R.drawable.wna_up, bottomMarginDp = 0) {
            host.narrationAction("Could not reorder the queue") { moveStoryToTop(story.id) }
        }
    }
}

private fun LinearLayout.focusCard(
    host: ScreenHost,
    story: Story,
    chapter: Chapter,
    status: NarrationChapterStatus,
) {
    addCard {
        text(chapter.title, Type.TITLE_MEDIUM)
        spacer(Space.XS)
        text(statusLabel(status), Type.BODY_MEDIUM, statusColor(status))
        when (status) {
            is NarrationChapterStatus.Ready ->
                fullButton("Play", Btn.FILLED, R.drawable.wna_play, bottomMarginDp = 0) {
                    TtsForegroundService.start(host.app, story.id, chapter.id)
                    host.toast("Playing ${chapter.title}")
                }
            is NarrationChapterStatus.Queued ->
                fullButton("Generate it next", Btn.TONAL, R.drawable.wna_up, bottomMarginDp = 0) {
                    host.narrationAction("Could not move the chapter") {
                        queueState.value.jobs
                            .firstOrNull { it.storyId == story.id && it.chapterId == chapter.id }
                            ?.let { moveToTop(it.id) }
                    }
                }
            is NarrationChapterStatus.Generating -> Unit
            else ->
                fullButton("Generate now", Btn.FILLED, R.drawable.wna_headphones, bottomMarginDp = 0) {
                    host.queueNarration(story.id, listOf(chapter.id), front = true)
                }
        }
    }
}

/** Bottom bar of selection mode: act on every selected chapter at once. */
private fun ScreenHost.selectionBar(
    story: Story,
    state: ChapterAudioViewState,
    done: () -> Unit,
): View {
    val host = this
    return LinearLayout(app).apply {
        orientation = LinearLayout.VERTICAL
        tag = BOTTOM_BAR_VIEW_TAG
        setBackgroundColor(ThemeManager.colors.background)
        setPadding(0, dp(Space.SM), 0, 0)
        row {
            button("Generate", Btn.FILLED, R.drawable.wna_headphones) {
                val ids = state.selected.filter { state.statuses[it].needsAudio() }
                if (ids.isEmpty()) {
                    host.toast(if (state.selected.isEmpty()) "Select chapters first" else "Those chapters already have audio or are queued")
                    return@button
                }
                host.queueNarration(story.id, ids)
                done()
            }
            button("Regenerate", Btn.TONAL) {
                val ids = state.selected.filter { state.statuses[it]?.pack() != null }
                if (ids.isEmpty()) {
                    host.toast("None of the selected chapters has audio yet")
                    return@button
                }
                host.queueNarration(story.id, ids, NarrationOrigin.REGENERATE)
                done()
            }
            button("Delete", Btn.ERROR, R.drawable.wna_delete) {
                val ids = state.selected.filter { status -> state.statuses[status].let { it?.pack() != null || it.isQueuedOrFailed() } }
                if (ids.isEmpty()) {
                    host.toast("None of the selected chapters has audio or is queued")
                    return@button
                }
                host.confirm(
                    "Delete the audio of ${NarrationScreenPlanning.chapters(ids.size)} and remove them from the queue?",
                    "Delete",
                ) {
                    host.narrationAction("Could not delete the audio") { deleteAudio(story.id, ids) }
                    done()
                }
            }
            for (index in 0 until childCount) {
                (getChildAt(index).layoutParams as LinearLayout.LayoutParams).apply {
                    width = 0
                    weight = 1f
                }
            }
        }
    }
}

private fun ScreenHost.showStoryAudioMenu(
    story: Story,
    playable: List<Chapter>,
    statuses: Map<String, NarrationChapterStatus>,
) {
    val voice = narration.currentVoice()
    val missing = playable.filter { statuses[it.id].needsAudio() }
    val packs = statuses.values.mapNotNull { it.pack() }
    val otherVoice = packs.filter { it.recipe.voice != voice }
    val items = mutableListOf<OptionsDialogItem>()
    if (missing.isNotEmpty()) {
        items +=
            OptionsDialogItem.Option("Generate everything not generated (${missing.size})") {
                val estimate = ChapterEstimate.of(packs)
                confirm(
                    "Generate ${NarrationScreenPlanning.chapters(missing.size)}? That is about " +
                        "${NarrationPlanning.formatDuration(estimate.audioMs * missing.size)} of audio and " +
                        "${NarrationPlanning.formatBytes(estimate.bytes * missing.size)}.",
                    confirmLabel = "Generate",
                ) { queueNarration(story.id, missing.map { it.id }) }
            }
    }
    if (narration.queueState.value.jobs
            .any { it.storyId == story.id }
    ) {
        items +=
            OptionsDialogItem.Option(
                "Generate this story first",
            ) { narrationAction("Could not reorder the queue") { moveStoryToTop(story.id) } }
    }
    if (otherVoice.isNotEmpty()) {
        items +=
            OptionsDialogItem.Option("Regenerate ${otherVoice.size} in ${voice.label}") {
                queueNarration(story.id, otherVoice.map { it.chapterId }, NarrationOrigin.REGENERATE)
            }
    }
    items += OptionsDialogItem.Option("Voice & Speech") { showTtsSettings() }
    items += OptionsDialogItem.Option("Generation queue (all stories)") { showNarration() }
    val queued =
        narration.queueState.value.jobs
            .filter { it.storyId == story.id }
    if (packs.isNotEmpty() || queued.isNotEmpty()) {
        items += OptionsDialogItem.Divider
        if (queued.isNotEmpty()) {
            items +=
                OptionsDialogItem.Option("Remove this story from the queue (${queued.size})", destructive = true) {
                    narrationAction("Could not remove") { remove(queued.map { it.id }.toSet()) }
                }
        }
        if (packs.isNotEmpty()) {
            items +=
                OptionsDialogItem.Option(
                    "Delete all audio (${packs.size} · ${NarrationPlanning.formatBytes(packs.sumOf { it.index.bytes })})",
                    destructive = true,
                ) {
                    confirm("Delete the audio of ${NarrationScreenPlanning.chapters(packs.size)}?", confirmLabel = "Delete") {
                        narrationAction("Could not delete the audio") { deleteAudio(story.id, packs.map { it.chapterId }) }
                    }
                }
        }
    }
    showStyledOptionsDialog("Chapter audio", items)
}

private fun ScreenHost.showChapterMenu(
    storyId: String,
    chapter: Chapter,
    status: NarrationChapterStatus,
) {
    val items = mutableListOf<OptionsDialogItem>()
    val job =
        narration.queueState.value.jobs
            .firstOrNull { it.storyId == storyId && it.chapterId == chapter.id }
    if (status is NarrationChapterStatus.Ready) {
        items += OptionsDialogItem.Option("Play") { TtsForegroundService.start(app, storyId, chapter.id) }
    }
    when {
        job != null -> {
            if (status !is NarrationChapterStatus.Generating) {
                items += OptionsDialogItem.Option("Generate next") { narrationAction("Could not move the chapter") { moveToTop(job.id) } }
            }
            if (status is NarrationChapterStatus.Failed) {
                items += OptionsDialogItem.Option("Retry") { narrationAction("Could not retry") { retry(job.id) } }
            }
        }
        status is NarrationChapterStatus.None || status is NarrationChapterStatus.Outdated ->
            items += OptionsDialogItem.Option("Generate now") { queueNarration(storyId, listOf(chapter.id), front = true) }
        else -> Unit
    }
    if (status.pack() != null) {
        items +=
            OptionsDialogItem.Option("Regenerate in ${narration.currentVoice().label}") {
                queueNarration(storyId, listOf(chapter.id), NarrationOrigin.REGENERATE, front = true)
            }
    }
    items += OptionsDialogItem.Option("Open in reader") { showReader(storyId, chapter.id) }
    if (job != null || status.pack() != null) {
        items += OptionsDialogItem.Divider
        items +=
            OptionsDialogItem.Option(if (status.pack() != null) "Delete audio" else "Remove from queue", destructive = true) {
                narrationAction("Could not delete the audio") { deleteAudio(storyId, listOf(chapter.id)) }
            }
    }
    showStyledOptionsDialog(chapter.title, items)
}

private fun NarrationChapterStatus?.isQueuedOrFailed(): Boolean =
    this is NarrationChapterStatus.Queued || this is NarrationChapterStatus.Generating || this is NarrationChapterStatus.Failed

internal fun NarrationChapterStatus.pack(): NarrationPack? =
    when (this) {
        is NarrationChapterStatus.Ready -> pack
        is NarrationChapterStatus.Outdated -> pack
        else -> null
    }

internal fun statusLabel(status: NarrationChapterStatus): String =
    when (status) {
        NarrationChapterStatus.None -> "Not generated"
        is NarrationChapterStatus.Queued -> "Queued · #${status.position}"
        is NarrationChapterStatus.Generating -> "Generating · ${NarrationScreenPlanning.percent(status.progress)}"
        is NarrationChapterStatus.Failed -> "Failed · ${status.reason}"
        is NarrationChapterStatus.Ready ->
            "Ready · ${NarrationPlanning.formatDuration(status.pack.index.totalDurationMs)} · ${status.pack.recipe.voice.label}"
        is NarrationChapterStatus.Outdated -> "Text changed · regenerate to play"
    }

internal fun statusColor(status: NarrationChapterStatus): Int =
    when (status) {
        is NarrationChapterStatus.Ready, is NarrationChapterStatus.Generating -> ThemeManager.colors.primary
        is NarrationChapterStatus.Failed, is NarrationChapterStatus.Outdated -> ThemeManager.colors.error
        else -> ThemeManager.colors.onSurfaceVariant
    }

/** Typical per-chapter size, from this story's audio when it has some. */
private data class ChapterEstimate(
    val audioMs: Long,
    val bytes: Long,
) {
    companion object {
        private const val DEFAULT_CHAPTER_MS = 20L * 60L * 1000L

        fun of(packs: List<NarrationPack>): ChapterEstimate {
            if (packs.isEmpty()) return ChapterEstimate(DEFAULT_CHAPTER_MS, NarrationPlanning.estimatedBytes(DEFAULT_CHAPTER_MS))
            return ChapterEstimate(packs.sumOf { it.index.totalDurationMs } / packs.size, packs.sumOf { it.index.bytes } / packs.size)
        }
    }
}

private const val DISABLED_ALPHA = 0.4f
