package com.vinicius741.webnovelarchiver.feature.details

import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.story.StoryActionGuards
import com.vinicius741.webnovelarchiver.feature.library.showLibrary
import com.vinicius741.webnovelarchiver.feature.narration.NarrationScreenPlanning
import com.vinicius741.webnovelarchiver.feature.narration.narrationAction
import com.vinicius741.webnovelarchiver.feature.narration.queueNarration
import com.vinicius741.webnovelarchiver.feature.story.queueDownload
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.TtsContinueListening
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.CardListDecoration
import com.vinicius741.webnovelarchiver.ui.SegmentedSelector
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.button
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.flow
import com.vinicius741.webnovelarchiver.ui.makeCountedChip
import com.vinicius741.webnovelarchiver.ui.makeEmptyState
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.size
import com.vinicius741.webnovelarchiver.ui.tintedIcon

/** Which chapters Select lists, so a long novel shows only the ones an action can apply to. */
private enum class SelectionScope(
    val label: String,
) {
    NOT_DOWNLOADED("Not downloaded"),
    DOWNLOADED("Downloaded"),
    ALL("All"),
    ;

    fun matches(chapter: Chapter): Boolean =
        when (this) {
            NOT_DOWNLOADED -> !chapter.downloaded
            DOWNLOADED -> chapter.downloaded
            ALL -> true
        }
}

/** The scope Select last showed, per story, for this run of the app. */
private val selectionScopes = HashMap<String, SelectionScope>()

/** Filters that narrow the listed chapters within a scope; the audio pair excludes each other. */
private enum class SelectionFilter(
    val label: String,
) {
    FROM_BOOKMARK("From bookmark"),
    HAS_AUDIO("Has audio"),
    NO_AUDIO("No audio"),
    ;

    fun matches(
        index: Int,
        chapter: Chapter,
        bookmarkIndex: Int,
        audio: Map<String, String>,
    ): Boolean =
        when (this) {
            FROM_BOOKMARK -> bookmarkIndex < 0 || index >= bookmarkIndex
            HAS_AUDIO -> audio[chapter.id] == AUDIO_READY_LABEL
            NO_AUDIO -> chapter.id !in audio
        }

    /** The other filter this one turns off when chosen. */
    val excludes: SelectionFilter?
        get() =
            when (this) {
                HAS_AUDIO -> NO_AUDIO
                NO_AUDIO -> HAS_AUDIO
                FROM_BOOKMARK -> null
            }
}

/** The filters Select last used, per story, for this run of the app. */
private val selectionFilters = HashMap<String, Set<SelectionFilter>>()

/**
 * Select chapters: one selection for every chapter action. Tap or long-press-and-drag chapters, then
 * Download them, Generate their audio, or Delete their audio; each action counts only the selected
 * chapters it applies to.
 */
@Suppress("LongMethod") // Keep the layout and selection interactions together.
internal fun ScreenHost.showChapterSelection(
    storyId: String,
    initialSelectedIds: Set<String> = emptySet(),
) {
    val story = repository.story(storyId) ?: return showLibrary()
    val canDownload = StoryActionGuards.canQueueDownloads(story)
    val audio = chapterAudioLabels(story)
    val hasUndownloaded = story.chapters.any { !it.downloaded }
    val scope =
        selectionScopes[storyId]
            ?: if (hasUndownloaded && canDownload) SelectionScope.NOT_DOWNLOADED else SelectionScope.DOWNLOADED
    val bookmarkIndex = story.chapters.indexOfFirst { it.id == story.lastReadChapterId }
    val offeredFilters = SelectionFilter.entries.filter { it != SelectionFilter.FROM_BOOKMARK || bookmarkIndex >= 0 }
    val filters = selectionFilters[storyId].orEmpty().filterTo(mutableSetOf()) { it in offeredFilters }
    val scoped = story.chapters.withIndex().filter { scope.matches(it.value) }
    val items =
        scoped.mapNotNull { (index, chapter) ->
            if (filters.all { it.matches(index, chapter, bookmarkIndex, audio) }) {
                SelectableChapter(index, chapter, audio[chapter.id])
            } else {
                null
            }
        }
    val orderedIds = items.map { it.chapter.id }
    val selectedIds = initialSelectedIds.filterTo(mutableSetOf()) { it in orderedIds }
    val bookmarkPosition = items.indexOfFirst { it.originalIndex == bookmarkIndex }
    val chapterLayout = LinearLayoutManager(app)

    screen(
        route = AppRoute.ChapterSelection(story.id, initialSelectedIds),
        title = "Select chapters",
        subtitle = story.title,
    ) {
        val scopes = SelectionScope.entries
        val counts =
            listOf(story.chapters.count { !it.downloaded }, story.chapters.count { it.downloaded }, story.chapters.size)
        addView(
            SegmentedSelector(context, scopes.map { it.label }, scopes.indexOf(scope), counts) { index ->
                selectionScopes[storyId] = scopes[index]
                showChapterSelection(storyId, selectedIds.toSet())
            }.view,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(Space.SM)
            },
        )
        val filterCounts =
            offeredFilters.associateWith { filter ->
                scoped.count { (index, chapter) -> filter.matches(index, chapter, bookmarkIndex, audio) }
            }
        addView(
            selectionFilterRow(filterCounts, filters) { next ->
                selectionFilters[storyId] = next
                showChapterSelection(storyId, selectedIds.toSet())
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(Space.XS)
            },
        )
        if (items.isEmpty()) {
            addView(
                makeEmptyState(
                    context,
                    message = if (filters.isEmpty()) "No chapters here. Pick another group above." else "No chapters match these filters.",
                    title = "Nothing to select",
                    iconRes = R.drawable.wna_check,
                ),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
            return@screen
        }

        var rangeMode = false
        var rangeAnchor: Int? = null
        lateinit var adapter: ChapterSelectionAdapter
        lateinit var rangeButton: android.widget.Button
        lateinit var actions: SelectionActions

        fun updateSelection(nextSelection: Set<String>) {
            val previous = selectedIds.toSet()
            selectedIds.clear()
            selectedIds.addAll(nextSelection)
            adapter.refreshSelection(previous, selectedIds)
            actions.update(selectedIds)
        }

        fun finishRangeMode() {
            val oldAnchor = rangeAnchor
            rangeMode = false
            rangeAnchor = null
            rangeButton.text = "Select range"
            adapter.setRangeAnchor(null, oldAnchor)
        }

        fun handleTap(position: Int) {
            if (!rangeMode) {
                val id = orderedIds[position]
                updateSelection(
                    selectedIds.toMutableSet().apply {
                        if (!add(id)) remove(id)
                    },
                )
                return
            }

            val anchor = rangeAnchor
            if (anchor == null) {
                rangeAnchor = position
                adapter.setRangeAnchor(position, null)
            } else {
                updateSelection(
                    ChapterSelectionPlanning.applyRange(
                        selectedIds = selectedIds,
                        orderedIds = orderedIds,
                        startPosition = anchor,
                        endPosition = position,
                        selecting = true,
                    ),
                )
                finishRangeMode()
            }
        }

        flow(spacing = Space.SM) {
            rangeButton =
                button("Select range", Btn.TONAL, R.drawable.wna_filter) {
                    if (rangeMode) {
                        finishRangeMode()
                    } else {
                        rangeMode = true
                        rangeAnchor = null
                        rangeButton.text = "Cancel range"
                    }
                }
            button("All", Btn.OUTLINED, R.drawable.wna_check) {
                finishRangeMode()
                updateSelection(orderedIds.toSet())
            }
            button("Clear", Btn.OUTLINED, R.drawable.wna_close) {
                finishRangeMode()
                updateSelection(emptySet())
            }
        }

        val list =
            RecyclerView(context).apply {
                layoutManager = chapterLayout
                itemAnimator = null
                clipToPadding = false
                setPadding(0, context.dp(Space.MD), 0, context.dp(Space.SM))
            }
        adapter = ChapterSelectionAdapter(items, selectedIds, bookmarkPosition, ::handleTap)
        list.adapter = adapter
        list.addItemDecoration(CardListDecoration(context) { true })
        list.addOnItemTouchListener(
            ChapterDragSelectionTouchListener(
                list = list,
                currentSelection = { selectedIds.toSet() },
                isSelected = { position -> orderedIds[position] in selectedIds },
                onDragStarted = { if (rangeMode) finishRangeMode() },
                onRangeChanged = { baseSelection, start, end, selecting ->
                    updateSelection(
                        ChapterSelectionPlanning.applyRange(
                            selectedIds = baseSelection,
                            orderedIds = orderedIds,
                            startPosition = start,
                            endPosition = end,
                            selecting = selecting,
                        ),
                    )
                },
                onDragFinished = {},
            ),
        )
        addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        actions = SelectionActions(this@showChapterSelection, this, story, items, audio, canDownload)
        actions.update(selectedIds)
    }
}

/** One chip per offered filter with its count; a tap hands back the filters to show next. */
private fun ScreenHost.selectionFilterRow(
    counts: Map<SelectionFilter, Int>,
    active: Set<SelectionFilter>,
    onChange: (Set<SelectionFilter>) -> Unit,
): HorizontalScrollView {
    val row = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL }
    counts.entries.forEachIndexed { position, (filter, count) ->
        val chip =
            makeCountedChip(app, filter.label, count, filter in active) {
                onChange(if (filter in active) active - filter else active - setOfNotNull(filter.excludes) + filter)
            }
        row.addView(
            chip,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (position > 0) marginStart = dp(Space.SM)
            },
        )
    }
    return HorizontalScrollView(app).apply {
        isHorizontalScrollBarEnabled = false
        addView(row)
    }
}

/** Download / Generate audio / Delete audio for the selection, each counting the chapters it applies to. */
private class SelectionActions(
    private val host: ScreenHost,
    container: LinearLayout,
    private val story: com.vinicius741.webnovelarchiver.domain.model.Story,
    private val items: List<SelectableChapter>,
    private val audio: Map<String, String>,
    private val canDownload: Boolean,
) {
    private var download: List<SelectableChapter> = emptyList()
    private var generate: List<SelectableChapter> = emptyList()
    private var delete: List<SelectableChapter> = emptyList()
    private val downloadButton: ImageView
    private val generateButton: ImageView
    private val deleteButton: ImageView

    init {
        // Small round icon buttons, centred, so they stay compact on a narrow (folded) screen; the label is spoken.
        val colors = ThemeManager.colors
        val context = container.context
        val actionRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, context.dp(Space.SM), 0, 0)
            }
        downloadButton =
            actionRow.roundAction(R.drawable.wna_download, colors.primary, colors.onPrimary) {
                if (download.isNotEmpty()) host.queueDownload(story, download.map { it.originalIndex })
            }
        generateButton =
            actionRow.roundAction(R.drawable.wna_headphones, colors.primaryContainer, colors.onPrimaryContainer) {
                if (generate.isEmpty()) return@roundAction
                host.queueNarration(story.id, generate.map { it.chapter.id })
                host.showStoryChapters(story.id)
            }
        deleteButton =
            actionRow.roundAction(R.drawable.wna_delete, colors.errorContainer, colors.onErrorContainer) {
                val ids = delete.map { it.chapter.id }
                if (ids.isEmpty()) return@roundAction
                host.confirm("Delete the audio of ${NarrationScreenPlanning.chapters(ids.size)}?", confirmLabel = "Delete") {
                    host.narrationAction("Could not delete the audio") { deleteAudio(story.id, ids) }
                    host.showStoryChapters(story.id)
                }
            }
        container.addView(actionRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun LinearLayout.roundAction(
        iconRes: Int,
        fill: Int,
        tint: Int,
        onClick: () -> Unit,
    ): ImageView {
        val size = context.dp(ROUND_ACTION_DP)
        // An ImageButton so TalkBack announces it as a button.
        val action =
            ImageButton(context).apply {
                setImageDrawable(context.tintedIcon(iconRes, tint))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                val pad = context.dp(Space.MD)
                setPadding(pad, pad, pad, pad)
                background = ripple(roundedBg(fill, size / 2f), size / 2f, tint)
                isFocusable = true
                setOnClickListener { onClick() }
            }
        addView(
            action,
            LinearLayout.LayoutParams(size, size).apply { if (childCount > 0) marginStart = context.dp(Space.LG) },
        )
        return action
    }

    fun update(selected: Set<String>) {
        val chosen = items.filter { it.chapter.id in selected }
        download = if (canDownload) chosen.filter { !it.chapter.downloaded } else emptyList()
        generate = chosen.filter { TtsContinueListening.isPlayable(it.chapter) && it.chapter.id !in audio }
        delete = chosen.filter { it.chapter.id in audio }
        downloadButton.show("Download", download.size)
        generateButton.show("Generate audio", generate.size)
        deleteButton.show("Delete audio", delete.size)
    }

    private fun ImageView.show(
        label: String,
        count: Int,
    ) {
        contentDescription =
            if (count > 0) "$label ${NarrationScreenPlanning.chapters(count)}" else "$label (none of the selected chapters)"
        isEnabled = count > 0
        alpha = if (count > 0) 1f else DISABLED_ALPHA
    }
}

private const val ROUND_ACTION_DP = 52
