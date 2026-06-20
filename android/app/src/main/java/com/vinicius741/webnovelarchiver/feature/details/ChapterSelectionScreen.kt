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
import com.vinicius741.webnovelarchiver.feature.story.queueDownload
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.button
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.flow
import com.vinicius741.webnovelarchiver.ui.fullButton
import com.vinicius741.webnovelarchiver.ui.makeEmptyState
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.size
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.toast

internal fun ScreenHost.showChapterSelection(
    storyId: String,
    initialSelectedIds: Set<String> = emptySet(),
) {
    val story = storage.getStory(storyId) ?: return showLibrary()
    if (!StoryActionGuards.canQueueDownloads(story)) {
        toast(StoryActionGuards.archivedActionMessage("Downloading"))
        return showDetails(story.id)
    }

    val items =
        story.chapters.mapIndexedNotNull { index, chapter ->
            if (chapter.downloaded) null else SelectableChapter(index, chapter)
        }
    val orderedIds = items.map { it.chapter.id }
    val selectedIds = initialSelectedIds.filterTo(mutableSetOf()) { it in orderedIds }

    screen(title = "Select Chapters", subtitle = story.title, onBack = { showDetails(story.id) }) {
        if (items.isEmpty()) {
            addView(
                makeEmptyState(
                    context,
                    message = "Every chapter is already saved — there's nothing left to queue.",
                    title = "All caught up",
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
        lateinit var downloadButton: android.widget.Button
        lateinit var hint: TextView

        fun updateSelection(nextSelection: Set<String>) {
            val previous = selectedIds.toSet()
            selectedIds.clear()
            selectedIds.addAll(nextSelection)
            adapter.refreshSelection(previous, selectedIds)
            downloadButton.text = "Download ${selectedIds.size} Selected"
        }

        fun finishRangeMode(message: String? = null) {
            val oldAnchor = rangeAnchor
            rangeMode = false
            rangeAnchor = null
            rangeButton.text = "Select Range"
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

        hint =
            makeText(context, DEFAULT_SELECTION_HINT, Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
                setPadding(0, 0, 0, context.dp(Space.SM))
            }
        addView(hint)

        flow(spacing = Space.SM) {
            rangeButton =
                button("Select Range", Btn.TONAL, R.drawable.wna_filter) {
                    if (rangeMode) {
                        finishRangeMode()
                    } else {
                        rangeMode = true
                        rangeAnchor = null
                        rangeButton.text = "Cancel Range"
                        hint.text = "Tap the first chapter in your range."
                    }
                }
            button("All", Btn.TEXT, R.drawable.wna_check) {
                finishRangeMode("All available chapters selected.")
                updateSelection(orderedIds.toSet())
            }
            button("Clear", Btn.TEXT, R.drawable.wna_close) {
                finishRangeMode("Selection cleared.")
                updateSelection(emptySet())
            }
        }

        val list =
            RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context)
                itemAnimator = null
                clipToPadding = false
                setPadding(0, context.dp(Space.SM), 0, context.dp(Space.SM))
            }
        adapter = ChapterSelectionAdapter(items, selectedIds, ::handleTap)
        list.adapter = adapter
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

        downloadButton =
            fullButton(
                "Download ${selectedIds.size} Selected",
                Btn.FILLED,
                R.drawable.wna_download,
                bottomMarginDp = 0,
            ) {
                val selectedIndexes = items.filter { it.chapter.id in selectedIds }.map { it.originalIndex }
                if (selectedIndexes.isEmpty()) {
                    toast("No undownloaded chapters selected")
                } else {
                    queueDownload(story, selectedIndexes)
                }
            }
    }
}

private const val DEFAULT_SELECTION_HINT =
    "Tap chapters individually, or long-press one and drag to select or clear a range."
