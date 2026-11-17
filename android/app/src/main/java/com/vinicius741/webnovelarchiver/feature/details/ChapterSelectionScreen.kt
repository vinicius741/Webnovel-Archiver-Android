package com.vinicius741.webnovelarchiver.feature.details

import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.story.StoryActionGuards
import com.vinicius741.webnovelarchiver.feature.details.ChapterSelectionPlanning
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
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.button
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.flow
import com.vinicius741.webnovelarchiver.ui.makeEmptyState
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.size
import com.vinicius741.webnovelarchiver.ui.strokeBg
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import com.vinicius741.webnovelarchiver.ui.toast

/** Which chapters Select lists, so a long novel shows only the ones an action can apply to. */
private enum class SelectionScope(
    val label: String,
) {
    NOT_DOWNLOADED("Not downloaded"),
    DOWNLOADED("Downloaded"),
    ALL("All"),
}

/** The scope Select last showed, per story, for this run of the app. */
private val selectionScopes = HashMap<String, SelectionScope>()

/**
 * Select chapters: one selection for every chapter action. Tap or long-press-and-drag chapters, then
 * Download them, Generate their audio, or Delete their audio; each action counts only the selected
 * chapters it applies to.
 */
@Suppress("LongMethod") // One linear screen build; its helpers are the hint card and the action row.
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
    val items =
        story.chapters.mapIndexedNotNull { index, chapter ->
            val shown =
                when (scope) {
                    SelectionScope.NOT_DOWNLOADED -> !chapter.downloaded
                    SelectionScope.DOWNLOADED -> chapter.downloaded
                    SelectionScope.ALL -> true
                }
            if (shown) SelectableChapter(index, chapter, audio[chapter.id]) else null
        }
    val orderedIds = items.map { it.chapter.id }
    val selectedIds = initialSelectedIds.filterTo(mutableSetOf()) { it in orderedIds }

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
        if (items.isEmpty()) {
            addView(
                makeEmptyState(
                    context,
                    message = "No chapters here. Pick another group above.",
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
        lateinit var hint: TextView

        fun updateSelection(nextSelection: Set<String>) {
            val previous = selectedIds.toSet()
            selectedIds.clear()
            selectedIds.addAll(nextSelection)
            adapter.refreshSelection(previous, selectedIds)
            actions.update(selectedIds)
        }

        fun finishRangeMode(message: String? = null) {
            val oldAnchor = rangeAnchor
            rangeMode = false
            rangeAnchor = null
            rangeButton.text = "Select range"
            adapter.setRangeAnchor(null, oldAnchor)
            hint.text = message ?: DEFAULT_SELECTION_HINT
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
                hint.text = "Chapter ${items[position].originalIndex + 1} is the start. Tap the last chapter."
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
                val first = minOf(items[anchor].originalIndex, items[position].originalIndex) + 1
                val last = maxOf(items[anchor].originalIndex, items[position].originalIndex) + 1
                finishRangeMode("Selected chapters $first–$last. Long-press and drag to adjust more.")
            }
        }

        hint = makeText(context, DEFAULT_SELECTION_HINT, Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant)
        addView(selectionHintCard(hint))

        flow(spacing = Space.SM) {
            rangeButton =
                button("Select range", Btn.TONAL, R.drawable.wna_filter) {
                    if (rangeMode) {
                        finishRangeMode()
                    } else {
                        rangeMode = true
                        rangeAnchor = null
                        rangeButton.text = "Cancel range"
                        hint.text = "Tap the first chapter in your range."
                    }
                }
            button("All", Btn.OUTLINED, R.drawable.wna_check) {
                finishRangeMode("All shown chapters selected.")
                updateSelection(orderedIds.toSet())
            }
            button("Clear", Btn.OUTLINED, R.drawable.wna_close) {
                finishRangeMode("Selection cleared.")
                updateSelection(emptySet())
            }
        }

        val list =
            RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context)
                itemAnimator = null
                clipToPadding = false
                setPadding(0, context.dp(Space.MD), 0, context.dp(Space.SM))
            }
        adapter = ChapterSelectionAdapter(items, selectedIds, ::handleTap)
        list.adapter = adapter
        list.addItemDecoration(CardListDecoration(context) { true })
        list.addOnItemTouchListener(
            ChapterDragSelectionTouchListener(
                list = list,
                currentSelection = { selectedIds.toSet() },
                isSelected = { position -> orderedIds[position] in selectedIds },
                onDragStarted = {
                    if (rangeMode) finishRangeMode()
                    hint.text = if (it) "Drag to select chapters." else "Drag to clear selected chapters."
                },
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
                onDragFinished = { hint.text = DEFAULT_SELECTION_HINT },
            ),
        )
        addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        actions = SelectionActions(this@showChapterSelection, this, story, items, audio, canDownload)
        actions.update(selectedIds)
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
    private lateinit var downloadButton: android.widget.Button
    private lateinit var generateButton: android.widget.Button
    private lateinit var deleteButton: android.widget.Button

    init {
        container.flow(spacing = Space.SM) {
            downloadButton =
                button("Download", Btn.FILLED, R.drawable.wna_download) {
                    if (download.isNotEmpty()) host.queueDownload(story, download.map { it.originalIndex })
                }
            generateButton =
                button("Generate audio", Btn.TONAL, R.drawable.wna_headphones) {
                    if (generate.isEmpty()) return@button
                    host.queueNarration(story.id, generate.map { it.chapter.id })
                    host.showStoryChapters(story.id)
                }
            deleteButton =
                button("Delete audio", Btn.ERROR, R.drawable.wna_delete) {
                    val ids = delete.map { it.chapter.id }
                    if (ids.isEmpty()) return@button
                    host.confirm("Delete the audio of ${NarrationScreenPlanning.chapters(ids.size)}?", confirmLabel = "Delete") {
                        host.narrationAction("Could not delete the audio") { deleteAudio(story.id, ids) }
                        host.showStoryChapters(story.id)
                    }
                }
        }
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

    private fun android.widget.Button.show(
        label: String,
        count: Int,
    ) {
        text = if (count > 0) "$label $count" else label
        isEnabled = count > 0
        alpha = if (count > 0) 1f else DISABLED_ALPHA
    }
}

/** The hint in a quiet card, as the prototype shows it above the actions. */
private fun ScreenHost.selectionHintCard(hint: TextView): LinearLayout {
    val colors = ThemeManager.colors
    val radius = dp(14).toFloat()
    return LinearLayout(app).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(dp(Space.MD), dp(Space.MD), dp(Space.MD), dp(Space.MD))
        background = strokeBg(colors.elevation1, radius, colors.line, dp(1))
        addView(
            android.widget.ImageView(app).apply { setImageDrawable(app.tintedIcon(R.drawable.wna_checklist, colors.primary)) },
            LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(Space.SM + 2) },
        )
        addView(hint, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        layoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(Space.XS)
            }
    }
}

private const val DEFAULT_SELECTION_HINT =
    "Tap chapters, or long-press one and drag to select or clear a range. Then download them or make their audio."
