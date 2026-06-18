package com.vinicius741.webnovelarchiver

import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.core.StoryActionGuards
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.button
import com.vinicius741.webnovelarchiver.ui.flow
import com.vinicius741.webnovelarchiver.ui.fullButton
import com.vinicius741.webnovelarchiver.ui.makeEmptyState
import com.vinicius741.webnovelarchiver.ui.makeSelectableCardRow
import com.vinicius741.webnovelarchiver.ui.sanitizeTitle
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.scroll
import com.vinicius741.webnovelarchiver.ui.toast
import com.vinicius741.webnovelarchiver.ui.verticalFill

internal fun ScreenHost.showChapterSelection(
    storyId: String,
    initialSelectedIds: Set<String> = emptySet(),
) {
    val story = storage.getStory(storyId) ?: return showLibrary()
    if (!StoryActionGuards.canQueueDownloads(story)) {
        toast(StoryActionGuards.archivedActionMessage("Downloading"))
        return showDetails(story.id)
    }
    val selectedIds = initialSelectedIds.toMutableSet()
    val downloadable = story.chapters.filter { !it.downloaded }
    screen(title = "Select Chapters", subtitle = story.title, onBack = { showDetails(story.id) }) {
        var refreshBulkActions: () -> Unit = {}
        // X3: select-all / deselect-all affordance for fast bulk selection.
        flow {
            button("Select All", Btn.TEXT, R.drawable.wna_check, enabled = downloadable.isNotEmpty()) {
                selectedIds.clear()
                selectedIds.addAll(downloadable.map { it.id })
                showChapterSelection(story.id, selectedIds)
            }
            button("Deselect All", Btn.TEXT, R.drawable.wna_close, enabled = downloadable.isNotEmpty()) {
                selectedIds.clear()
                showChapterSelection(story.id, selectedIds)
            }
        }
        addView(
            scroll(
                LinearLayout(app).apply {
                    orientation = LinearLayout.VERTICAL
                    // X1: reuse the card-style selectable row instead of bare CheckBoxes.
                    if (downloadable.isEmpty()) {
                        addView(makeEmptyState(context, "All chapters are already downloaded.", R.drawable.wna_check))
                    } else {
                        downloadable.forEach { chapter ->
                            val displayIndex = story.chapters.indexOfFirst { it.id == chapter.id } + 1
                            addView(
                                makeSelectableCardRow(
                                    context,
                                    title = "$displayIndex. ${sanitizeTitle(chapter.title)}",
                                    subtitle = if (chapter.downloaded) "Available Offline" else null,
                                    selected = selectedIds.contains(chapter.id),
                                ) { checked ->
                                    if (checked) selectedIds.add(chapter.id) else selectedIds.remove(chapter.id)
                                    refreshBulkActions()
                                },
                            )
                        }
                    }
                },
            ),
            verticalFill(),
        )
        // X2: the primary bulk action is a full-width CTA docked at the bottom, next to the items.
        val downloadButton =
            fullButton(
                "Download ${selectedIds.size} Selected",
                Btn.FILLED,
                R.drawable.wna_download,
                enabled = downloadable.isNotEmpty(),
                bottomMarginDp = 0,
            ) {
                val selectedIndexes =
                    story.chapters.mapIndexedNotNull { index, chapter ->
                        if (selectedIds.contains(chapter.id) && !chapter.downloaded) index else null
                    }
                if (selectedIndexes.isEmpty()) {
                    toast("No undownloaded chapters selected")
                } else {
                    queueDownload(story, selectedIndexes)
                }
            }
        refreshBulkActions = {
            downloadButton.text = "Download ${selectedIds.size} Selected"
        }
        refreshBulkActions()
    }
}
