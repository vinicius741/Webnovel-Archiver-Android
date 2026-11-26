package com.vinicius741.webnovelarchiver.feature.details

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.story.StoryActionGuards
import com.vinicius741.webnovelarchiver.feature.library.showLibrary
import com.vinicius741.webnovelarchiver.feature.narration.NarrationScreenPlanning
import com.vinicius741.webnovelarchiver.feature.narration.narration
import com.vinicius741.webnovelarchiver.feature.narration.queueNarration
import com.vinicius741.webnovelarchiver.feature.story.queueDownload
import com.vinicius741.webnovelarchiver.narration.NarrationPlanning
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.TtsContinueListening
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.CardListDecoration
import com.vinicius741.webnovelarchiver.ui.SegmentedSelector
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeCountedChip
import com.vinicius741.webnovelarchiver.ui.makeEmptyState
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.strokeBg
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

/** Where Pick chapters… was opened from, which decides the chapters it lists first. */
internal enum class SelectionPurpose { DOWNLOAD, AUDIO }

/**
 * Select, opened from the On this phone card's sheets: Download lists the chapters not downloaded
 * from the bookmark on; Audio lists every chapter with audio.
 */
internal fun showChapterSelectionFor(
    host: ScreenHost,
    storyId: String,
    purpose: SelectionPurpose,
) {
    when (purpose) {
        SelectionPurpose.DOWNLOAD -> {
            selectionScopes[storyId] = SelectionScope.NOT_DOWNLOADED
            selectionFilters[storyId] = setOf(SelectionFilter.FROM_BOOKMARK)
        }
        SelectionPurpose.AUDIO -> {
            selectionScopes[storyId] = SelectionScope.ALL
            selectionFilters[storyId] = setOf(SelectionFilter.HAS_AUDIO)
        }
    }
    host.showChapterSelection(storyId)
}

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
        lateinit var rangeButton: android.widget.TextView
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
            rangeButton.text = "Range"
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

        // Quick picks add to the selection; "Before" drops the From bookmark filter so they show.
        val candidates =
            scoped.mapNotNull { (index, chapter) ->
                index.takeIf {
                    filters.filter { it != SelectionFilter.FROM_BOOKMARK }.all { it.matches(index, chapter, bookmarkIndex, audio) }
                }
            }
        val quick =
            selectionQuickPicks(
                this@showChapterSelection,
                storyId,
                story.chapters.map {
                    it.id
                },
                bookmarkIndex,
                candidates,
                filters,
                orderedIds,
                selectedIds,
            ) {
                updateSelection(it)
            }
        val (quickRow, rangeChip) =
            quickPickRow(context, quick) {
                if (rangeMode) {
                    finishRangeMode()
                } else {
                    rangeMode = true
                    rangeAnchor = null
                    rangeButton.text = "Tap first & last…"
                }
            }
        rangeButton = rangeChip
        addView(
            quickRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(Space.SM)
            },
        )

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
        actions =
            SelectionActions(this@showChapterSelection, this, story, items, audio, chapterAudioInfo(narration, story), canDownload) {
                finishRangeMode()
                updateSelection(emptySet())
            }
        actions.update(selectedIds)
    }
}

/**
 * Select's quick picks: before / from the bookmark, the next 10 from it, all shown. "Before"
 * drops the From bookmark filter (re-rendering with them selected) so the added chapters show.
 */
private fun selectionQuickPicks(
    host: ScreenHost,
    storyId: String,
    chapterIds: List<String>,
    bookmarkIndex: Int,
    candidates: List<Int>,
    filters: Set<SelectionFilter>,
    orderedIds: List<String>,
    selectedIds: Set<String>,
    update: (Set<String>) -> Unit,
): List<QuickPick> {
    val shown = orderedIds.toSet()
    return buildList {
        if (bookmarkIndex > 0) {
            add(
                QuickPick("Before Ch. ${bookmarkIndex + 1}", R.drawable.wna_bookmark) {
                    val before = candidates.filter { it < bookmarkIndex }.map(chapterIds::get)
                    if (SelectionFilter.FROM_BOOKMARK in filters) {
                        selectionFilters[storyId] = filters - SelectionFilter.FROM_BOOKMARK
                        host.showChapterSelection(storyId, selectedIds + before)
                    } else {
                        update(selectedIds + before)
                    }
                },
            )
        }
        if (bookmarkIndex >= 0) {
            add(
                QuickPick("Ch. ${bookmarkIndex + 1} onward", R.drawable.wna_bookmark) {
                    update(selectedIds + candidates.filter { it >= bookmarkIndex }.map(chapterIds::get).filter { it in shown })
                },
            )
        }
        add(
            QuickPick("Next $QUICK_NEXT", R.drawable.wna_skip_next) {
                val next = candidates.filter { it >= bookmarkIndex.coerceAtLeast(0) }.take(QUICK_NEXT).map(chapterIds::get)
                update(selectedIds + next.filter { it in shown })
            },
        )
        add(QuickPick("All shown", R.drawable.wna_select_all) { update(selectedIds + orderedIds) })
    }
}

/** A quick pick on Select's Add row: [onPick] adds its chapters to the selection. */
private class QuickPick(
    val label: String,
    val iconRes: Int,
    val onPick: () -> Unit,
)

/** "ADD" then one outlined chip per quick pick and a Range chip; returns the row and the Range chip's label. */
private fun quickPickRow(
    app: android.content.Context,
    picks: List<QuickPick>,
    onRange: () -> Unit,
): Pair<HorizontalScrollView, android.widget.TextView> {
    fun dp(value: Int) = app.dp(value)
    val colors = ThemeManager.colors
    val row =
        LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                makeText(app, "ADD", Type.LABEL_SMALL, colors.faint).apply { letterSpacing = ADD_LETTER_SPACING },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(Space.SM)
                },
            )
        }

    fun chip(
        label: String,
        iconRes: Int,
        tint: Int,
        onClick: () -> Unit,
    ): android.widget.TextView {
        val radius = dp(ThemeManager.shapes.chipRadius).toFloat()
        val text =
            makeText(app, label, Type.LABEL_LARGE, tint).apply {
                setCompoundDrawablesRelativeWithIntrinsicBounds(
                    app.tintedIcon(iconRes, tint)?.apply { setBounds(0, 0, dp(16), dp(16)) },
                    null,
                    null,
                    null,
                )
                compoundDrawablePadding = dp(Space.XS + 2)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(Space.MD), dp(Space.SM), dp(Space.MD + 2), dp(Space.SM))
                background = ripple(strokeBg(android.graphics.Color.TRANSPARENT, radius, colors.outline, dp(1)), radius, colors.onSurface)
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
        row.addView(
            text,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(Space.SM)
            },
        )
        return text
    }
    picks.forEach { pick ->
        chip(pick.label, pick.iconRes, if (pick.iconRes == R.drawable.wna_bookmark) colors.bookmark else colors.onSurface, pick.onPick)
    }
    val range = chip("Range", R.drawable.wna_filter, colors.onSurface, onRange)
    val scroller =
        HorizontalScrollView(app).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    return scroller to range
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

/**
 * The docked footer: how many are selected and where, what they hold (audio and its size, queued,
 * not downloaded), Clear, and Download / Generate / Delete, each counting the selected chapters it
 * applies to.
 */
private class SelectionActions(
    private val host: ScreenHost,
    container: LinearLayout,
    private val story: com.vinicius741.webnovelarchiver.domain.model.Story,
    private val items: List<SelectableChapter>,
    private val audio: Map<String, String>,
    private val audioInfo: Map<String, ChapterAudioInfo>,
    private val canDownload: Boolean,
    private val onClear: () -> Unit,
) {
    private var download: List<SelectableChapter> = emptyList()
    private var generate: List<SelectableChapter> = emptyList()
    private var delete: List<SelectableChapter> = emptyList()
    private val colors = ThemeManager.colors
    private val context = container.context
    private val summary = makeText(context, "", Type.BODY_SMALL, colors.onSurfaceVariant).apply { maxLines = 1 }
    private val details = makeText(context, "", Type.BODY_SMALL, colors.onSurfaceVariant)
    private val clear = makeButton(context, "Clear", Btn.TEXT) { onClear() }
    private val downloadButton: Button
    private val generateButton: Button
    private val deleteButton: Button

    init {
        val head =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(summary, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(clear.apply { minHeight = context.dp(ACTION_HEIGHT_DP) })
            }
        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        downloadButton =
            actions.action("Download") {
                if (download.isNotEmpty()) host.queueDownload(story, download.map { it.originalIndex })
            }
        generateButton =
            actions.action("Generate") {
                if (generate.isEmpty()) return@action
                host.queueNarration(story.id, generate.map { it.chapter.id })
                host.showStoryChapters(story.id)
            }
        deleteButton =
            actions.action("Delete") {
                val ids = delete.map { it.chapter.id }
                if (ids.isEmpty()) return@action
                ChapterSheets(host).confirmDelete(story, ids, audioInfo, null)
            }
        val footer =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(colors.elevation1)
                setPadding(0, context.dp(Space.SM), 0, context.dp(Space.SM))
                addView(head)
                addView(details)
                addView(
                    actions,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = context.dp(Space.SM)
                    },
                )
            }
        container.addView(
            View(context).apply { setBackgroundColor(colors.line) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(1).coerceAtLeast(1)),
        )
        container.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun LinearLayout.action(
        label: String,
        onClick: () -> Unit,
    ): Button {
        val button =
            makeButton(context, label, Btn.SURFACE) { onClick() }.apply {
                minHeight = context.dp(ACTION_HEIGHT_DP)
                minimumHeight = context.dp(ACTION_HEIGHT_DP)
                setPadding(context.dp(Space.SM), 0, context.dp(Space.SM), 0)
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
        addView(
            button,
            LinearLayout.LayoutParams(0, context.dp(ACTION_HEIGHT_DP), 1f).apply {
                if (childCount > 0) marginStart = context.dp(Space.SM)
            },
        )
        return button
    }

    fun update(selected: Set<String>) {
        val chosen = items.filter { it.chapter.id in selected }
        download = if (canDownload) chosen.filter { !it.chapter.downloaded } else emptyList()
        generate = chosen.filter { TtsContinueListening.isPlayable(it.chapter) && it.chapter.id !in audio }
        delete = chosen.filter { it.chapter.id in audio }
        summary.text =
            if (chosen.isEmpty()) {
                "Tap chapters, or add some above"
            } else {
                "${"%,d".format(chosen.size)} selected · ${ChapterActionPlanning.rangeLabel(chosen.map { it.originalIndex })}"
            }
        summary.setTextColor(if (chosen.isEmpty()) colors.onSurfaceVariant else colors.onSurface)
        clear.visibility = if (chosen.isEmpty()) View.INVISIBLE else View.VISIBLE
        val deleteIds = delete.map { it.chapter.id }
        val pending = ChapterActionPlanning.pendingOf(deleteIds, audioInfo)
        val ready = deleteIds.size - pending
        val notDownloaded = chosen.count { !it.chapter.downloaded }
        details.text =
            listOfNotNull(
                if (ready >
                    0
                ) {
                    "$ready with audio · ${NarrationPlanning.formatBytes(ChapterActionPlanning.bytesOf(deleteIds, audioInfo))}"
                } else {
                    null
                },
                if (pending > 0) "$pending queued" else null,
                if (notDownloaded > 0) "$notDownloaded not downloaded" else null,
                if (chosen.any { it.chapter.id == story.lastReadChapterId }) "includes your bookmark" else null,
            ).joinToString(" · ")
        details.visibility = if (details.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        downloadButton.show("Download", download.size)
        generateButton.show("Generate", generate.size)
        deleteButton.show("Delete", delete.size)
    }

    private fun Button.show(
        label: String,
        count: Int,
    ) {
        text = if (count > 0) "$label ${"%,d".format(count)}" else label
        contentDescription =
            if (count > 0) "$label ${NarrationScreenPlanning.chapters(count)}" else "$label (none of the selected chapters)"
        isEnabled = count > 0
        alpha = if (count > 0) 1f else DISABLED_ALPHA
    }
}

private const val ACTION_HEIGHT_DP = 40
private const val QUICK_NEXT = 10
private const val ADD_LETTER_SPACING = 0.08f
