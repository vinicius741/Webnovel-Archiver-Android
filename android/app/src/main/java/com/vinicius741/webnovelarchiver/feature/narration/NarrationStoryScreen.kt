package com.vinicius741.webnovelarchiver.feature.narration

import android.app.AlertDialog
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.feature.reader.showReader
import com.vinicius741.webnovelarchiver.feature.settings.showTtsSettings
import com.vinicius741.webnovelarchiver.narration.NarrationChapterStatus
import com.vinicius741.webnovelarchiver.narration.NarrationOrigin
import com.vinicius741.webnovelarchiver.narration.NarrationPlanning
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.TtsContinueListening
import com.vinicius741.webnovelarchiver.tts.TtsForegroundService
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.OptionsDialogItem
import com.vinicius741.webnovelarchiver.ui.SCROLL_BODY_VIEW_TAG
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.applyAppTheme
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.fullButton
import com.vinicius741.webnovelarchiver.ui.makeProgress
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.section
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.showStyledOptionsDialog
import com.vinicius741.webnovelarchiver.ui.spacer
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.toast
import com.vinicius741.webnovelarchiver.ui.verticalFill
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * One story's chapter audio: what "Read aloud" opens when a chapter is not generated yet. It shows
 * that chapter first with the quickest ways to generate it and what follows, then every downloaded
 * chapter with its status, to generate, regenerate in another voice, delete or play.
 */
internal fun ScreenHost.showNarrationStory(
    storyId: String,
    chapterId: String? = null,
) {
    val story = repository.story(storyId) ?: return showNarration()
    val route = AppRoute.NarrationStory(storyId, chapterId)
    rerender = { showNarrationStory(storyId, chapterId) }
    val host = this
    val header = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
    header.addView(makeText(app, "Checking audio…", Type.BODY_MEDIUM, ThemeManager.colors.onSurfaceVariant))
    val adapter = NarrationChapterAdapter(header) { chapter, status -> host.showChapterMenu(storyId, chapter, status) }
    screen(route = route, title = "Chapter audio", subtitle = story.title) {
        addView(
            RecyclerView(app).apply {
                layoutManager = LinearLayoutManager(app)
                this.adapter = adapter
                clipToPadding = false
                // The mini-player pads the screen's scroll body so the last rows stay reachable.
                tag = SCROLL_BODY_VIEW_TAG
            },
            verticalFill(),
        )
    }
    val coordinator = narration
    screenObserver =
        scope.launch {
            combine(coordinator.queueState, coordinator.progress, coordinator.packsRevision) { _, _, _ -> Unit }
                .collectLatest {
                    val latest = repository.story(storyId) ?: return@collectLatest
                    val statuses = runCatching { coordinator.statuses(latest) }.getOrNull() ?: return@collectLatest
                    if (navigator.current != route) return@collectLatest
                    header.removeAllViews()
                    host.buildStoryHeader(header, latest, chapterId, statuses)
                    adapter.submit(latest.chapters.filter(TtsContinueListening::isPlayable), statuses)
                }
        }
}

private fun ScreenHost.buildStoryHeader(
    header: LinearLayout,
    story: Story,
    focusId: String?,
    statuses: Map<String, NarrationChapterStatus>,
) {
    val playable = story.chapters.filter(TtsContinueListening::isPlayable)
    val focus = playable.firstOrNull { it.id == focusId }
    val voice = narration.currentVoice()
    val estimate = ChapterEstimate.of(statuses.values.mapNotNull { (it as? NarrationChapterStatus.Ready)?.pack })
    with(header) {
        if (focus != null) focusCard(this@buildStoryHeader, story, focus, statuses[focus.id] ?: NarrationChapterStatus.None)
        addCard {
            text("New audio uses: ${voice.label}", Type.TITLE_SMALL)
            spacer(Space.XS)
            text(
                "Chapters keep the voice they were generated with. Change the voice in Voice & Speech, then regenerate.",
                Type.BODY_SMALL,
                ThemeManager.colors.onSurfaceVariant,
            )
            fullButton("Voice & Speech", Btn.TEXT, bottomMarginDp = 0) { showTtsSettings() }
        }
        val missing = playable.filter { statuses[it.id].needsGeneration() }
        val start = focus?.let { playable.indexOf(it) } ?: 0
        val nextFive = playable.drop(start).take(NEXT_BATCH).filter { statuses[it.id].needsGeneration() }
        addCard {
            text("Generate", Type.TITLE_SMALL)
            spacer(Space.XS)
            text(
                "About ${NarrationPlanning.formatDuration(estimate.audioMs)} of audio and " +
                    "${NarrationPlanning.formatBytes(estimate.bytes)} per chapter.",
                Type.BODY_SMALL,
                ThemeManager.colors.onSurfaceVariant,
            )
            fullButton(
                when {
                    nextFive.isEmpty() -> "The next $NEXT_BATCH are ready or queued"
                    focus != null -> "This chapter and the next ones (${nextFive.size})"
                    else -> "The first ones (${nextFive.size})"
                },
                Btn.TONAL,
                R.drawable.wna_headphones,
                enabled = nextFive.isNotEmpty(),
                bottomMarginDp = 0,
            ) { queueNarration(story.id, nextFive.map { it.id }) }
            fullButton(
                "Everything not generated (${missing.size})",
                Btn.TONAL,
                enabled = missing.isNotEmpty(),
                bottomMarginDp = 0,
            ) {
                confirm(
                    "Generate ${missing.size} chapters? That is about ${NarrationPlanning.formatDuration(
                        estimate.audioMs * missing.size,
                    )} " +
                        "of audio and ${NarrationPlanning.formatBytes(estimate.bytes * missing.size)}.",
                    confirmLabel = "Generate",
                ) { queueNarration(story.id, missing.map { it.id }) }
            }
            fullButton("Select chapters…", Btn.TEXT, bottomMarginDp = 0) { showChapterPicker(story, playable, statuses) }
        }
        val packs = statuses.values.mapNotNull { it.pack() }
        if (packs.isNotEmpty()) {
            val otherVoice = packs.filter { it.recipe.voice != voice }
            addCard {
                text("This story's audio", Type.TITLE_SMALL)
                spacer(Space.XS)
                text(
                    "${packs.size} ${if (packs.size == 1) "chapter" else "chapters"} · " +
                        NarrationPlanning.formatBytes(packs.sumOf { it.index.bytes }),
                    Type.BODY_SMALL,
                    ThemeManager.colors.onSurfaceVariant,
                )
                if (otherVoice.isNotEmpty()) {
                    fullButton("Regenerate ${otherVoice.size} in ${voice.label}", Btn.TONAL, bottomMarginDp = 0) {
                        queueNarration(story.id, otherVoice.map { it.chapterId }, NarrationOrigin.REGENERATE)
                    }
                }
                fullButton("Delete all audio", Btn.ERROR, R.drawable.wna_delete, bottomMarginDp = 0) {
                    confirm("Delete the audio of ${packs.size} chapters?", confirmLabel = "Delete") {
                        narrationAction("Could not delete the audio") { deleteAudio(story.id, packs.map { it.chapterId }) }
                    }
                }
            }
        }
        section("Chapters")
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
        if (status is NarrationChapterStatus.Generating) {
            spacer(Space.SM)
            addView(makeProgress(context, status.progress))
        }
        when (status) {
            is NarrationChapterStatus.Ready ->
                fullButton("Play", Btn.FILLED, R.drawable.wna_play, bottomMarginDp = 0) {
                    TtsForegroundService.start(host.app, story.id, chapter.id)
                    host.toast("Playing ${chapter.title}")
                }
            is NarrationChapterStatus.Queued ->
                fullButton("Generate it next", Btn.TONAL, bottomMarginDp = 0) {
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
            items += OptionsDialogItem.Option("Generate next") { narrationAction("Could not move the chapter") { moveToTop(job.id) } }
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

private fun ScreenHost.showChapterPicker(
    story: Story,
    chapters: List<Chapter>,
    statuses: Map<String, NarrationChapterStatus>,
) {
    if (chapters.isEmpty()) {
        toast("No downloaded chapters")
        return
    }
    val labels = chapters.map { "${it.title} · ${statusLabel(statuses[it.id] ?: NarrationChapterStatus.None)}" }.toTypedArray()
    val checked = BooleanArray(chapters.size)
    AlertDialog
        .Builder(app)
        .setTitle("Select chapters")
        .setMultiChoiceItems(labels, checked) { _, index, isChecked -> checked[index] = isChecked }
        .setPositiveButton("Next") { _, _ ->
            val selected = chapters.filterIndexed { index, _ -> checked[index] }
            if (selected.isEmpty()) return@setPositiveButton
            val ids = selected.map { it.id }
            showStyledOptionsDialog(
                "${selected.size} chapters",
                listOf(
                    OptionsDialogItem.Option("Generate") { queueNarration(story.id, ids) },
                    OptionsDialogItem.Option("Regenerate in ${narration.currentVoice().label}") {
                        queueNarration(story.id, ids, NarrationOrigin.REGENERATE)
                    },
                    OptionsDialogItem.Divider,
                    OptionsDialogItem.Option("Delete audio", destructive = true) {
                        narrationAction("Could not delete the audio") { deleteAudio(story.id, ids) }
                    },
                ),
            )
        }.setNegativeButton("Cancel", null)
        .show()
        .also { it.applyAppTheme() }
}

private fun NarrationChapterStatus?.needsGeneration(): Boolean =
    this == null || this is NarrationChapterStatus.None || this is NarrationChapterStatus.Outdated

private fun NarrationChapterStatus.pack() =
    when (this) {
        is NarrationChapterStatus.Ready -> pack
        is NarrationChapterStatus.Outdated -> pack
        else -> null
    }

internal fun statusLabel(status: NarrationChapterStatus): String =
    when (status) {
        NarrationChapterStatus.None -> "Not generated"
        is NarrationChapterStatus.Queued -> "Queued · #${status.position}"
        is NarrationChapterStatus.Generating -> "Generating · ${(status.progress * 100).toInt()}%"
        is NarrationChapterStatus.Failed -> "Failed · ${status.reason}"
        is NarrationChapterStatus.Ready ->
            "Ready · ${NarrationPlanning.formatDuration(status.pack.index.totalDurationMs)} · ${status.pack.recipe.voice.label}"
        is NarrationChapterStatus.Outdated -> "Text changed · regenerate to play"
    }

private fun statusColor(status: NarrationChapterStatus): Int =
    when (status) {
        is NarrationChapterStatus.Ready -> ThemeManager.colors.primary
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

        fun of(packs: List<com.vinicius741.webnovelarchiver.narration.NarrationPack>): ChapterEstimate {
            if (packs.isEmpty()) return ChapterEstimate(DEFAULT_CHAPTER_MS, NarrationPlanning.estimatedBytes(DEFAULT_CHAPTER_MS))
            return ChapterEstimate(packs.sumOf { it.index.totalDurationMs } / packs.size, packs.sumOf { it.index.bytes } / packs.size)
        }
    }
}

/** The header, then one row per downloaded chapter with its audio status. */
private class NarrationChapterAdapter(
    private val header: LinearLayout,
    private val onTap: (Chapter, NarrationChapterStatus) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var chapters: List<Chapter> = emptyList()
    private var statuses: Map<String, NarrationChapterStatus> = emptyMap()

    @android.annotation.SuppressLint("NotifyDataSetChanged") // Statuses change across the whole list at once.
    fun submit(
        chapters: List<Chapter>,
        statuses: Map<String, NarrationChapterStatus>,
    ) {
        this.chapters = chapters
        this.statuses = statuses
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = chapters.size + 1

    override fun getItemViewType(position: Int): Int = if (position == 0) HEADER else ROW

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder {
        if (viewType == HEADER) {
            (header.parent as? ViewGroup)?.removeView(header)
            header.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            return object : RecyclerView.ViewHolder(header) {}
        }
        val context = parent.context
        val row =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(context.dp(Space.SM), context.dp(Space.SM), context.dp(Space.SM), context.dp(Space.SM))
                background = selectableRipple(ThemeManager.colors.primary and 0x22FFFFFF)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(makeText(context, "", Type.BODY_MEDIUM, ThemeManager.colors.onSurface))
                addView(makeText(context, "", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant))
            }
        return object : RecyclerView.ViewHolder(row) {}
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
    ) {
        if (position == 0) return
        val chapter = chapters[position - 1]
        val status = statuses[chapter.id] ?: NarrationChapterStatus.None
        val row = holder.itemView as LinearLayout
        (row.getChildAt(0) as TextView).text = chapter.title
        (row.getChildAt(1) as TextView).apply {
            text = statusLabel(status)
            setTextColor(statusColor(status))
        }
        row.setOnClickListener { onTap(chapter, status) }
    }

    private companion object {
        const val HEADER = 0
        const val ROW = 1
    }
}

private const val NEXT_BATCH = 5
