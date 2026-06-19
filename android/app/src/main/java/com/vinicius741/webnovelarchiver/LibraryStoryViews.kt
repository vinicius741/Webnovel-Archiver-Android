package com.vinicius741.webnovelarchiver

import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.core.LibraryQuery
import com.vinicius741.webnovelarchiver.core.ScreenLayoutResult
import com.vinicius741.webnovelarchiver.core.SourceRegistry
import com.vinicius741.webnovelarchiver.core.Story
import com.vinicius741.webnovelarchiver.core.StoryActionGuards
import com.vinicius741.webnovelarchiver.ui.GridLayout
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.card
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.coverImage
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeEmptyState
import com.vinicius741.webnovelarchiver.ui.makeProgressSummary
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.scoreRow
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.showStyledOptionsDialog
import com.vinicius741.webnovelarchiver.ui.tintedIcon

internal fun ScreenHost.renderTabGrid(
    stories: List<Story>,
    list: GridLayout,
    layout: ScreenLayoutResult,
    filter: String,
    selectedTabId: String?,
    selectedTags: Set<String>,
    sortOption: String,
    sortAscending: Boolean,
) {
    list.removeAllViews()
    // Re-apply the column count in case the window refolded since the grid was created.
    list.columnCount = layout.numColumns.coerceAtLeast(1)
    val visible = LibraryQuery.filterAndSort(stories, filter, selectedTabId, selectedTags, sortOption, sortAscending)
    if (visible.isEmpty()) {
        list.addView(makeEmptyState(app, "No novels match this view.", R.drawable.wna_search))
        return
    }
    // The library always uses the horizontal story card (80×120 cover on the left, text on the right)
    // regardless of column count — the same layout as phone mode, just reflowed into N columns on
    // wider windows. The previous vertical "compact" card cropped portrait covers badly and looked
    // inconsistent with the single-column view.
    visible.forEach { story ->
        list.addView(
            list.card {
                val content = buildStoryCard(story)
                content.background = selectableRipple(ThemeManager.colors.onSurface)
                content.isClickable = true
                content.setOnClickListener { showDetails(story.id) }
                content.setOnLongClickListener {
                    showStoryActionsDialog(story)
                    true
                }
                addView(content)
                if (story.totalChapters > 0) {
                    // L5: show the download-count text alongside the thin progress bar so you don't have
                    // to open the story to see "12 / 140 chapters".
                    addView(
                        makeProgressSummary(context, story.downloadedChapters, story.totalChapters).apply {
                            layoutParams =
                                LinearLayout
                                    .LayoutParams(
                                        LinearLayout.LayoutParams.MATCH_PARENT,
                                        LinearLayout.LayoutParams.WRAP_CONTENT,
                                    ).apply {
                                        topMargin =
                                            dp(Space.MD)
                                    }
                        },
                    )
                }
            },
        )
    }
}

/** Full-width horizontal card content for the single-column library: 80×120 cover + stacked text. */
private fun ScreenHost.buildStoryCard(story: Story): LinearLayout {
    val row =
        LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
    row.addView(coverImage(story, widthDp = 80, heightDp = 120, tapToOpen = false))
    row.addView(
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                makeText(app, story.title, Type.TITLE_MEDIUM, ThemeManager.colors.onSurface).apply {
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
            )
            addView(
                makeText(app, "by ${story.author}", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
                    setPadding(0, dp(Space.XS), 0, 0)
                },
            )
            SourceRegistry.getProvider(story.sourceUrl)?.let {
                addView(
                    makeText(app, it.name, Type.LABEL_SMALL, ThemeManager.colors.primary).apply {
                        setPadding(0, dp(Space.XS), 0, 0)
                    },
                )
            }
            story.score?.takeIf { it.isNotBlank() }?.let { score ->
                addView(scoreRow(score))
            }
            story.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
                addView(
                    makeText(app, tags.take(5).joinToString("  •  "), Type.LABEL_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
                        setPadding(0, dp(Space.XS), 0, 0)
                    },
                )
            }
        },
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
    )
    if (story.isArchived == true) {
        row.addView(
            ImageView(app).apply {
                setImageDrawable(app.tintedIcon(R.drawable.wna_archive, ThemeManager.colors.primary))
                layoutParams =
                    LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                        marginStart = dp(Space.SM)
                    }
            },
        )
    }
    return row
}

private fun ScreenHost.showStoryActionsDialog(story: Story) {
    val options = mutableListOf<Pair<String, () -> Unit>>("Open" to { showDetails(story.id) })
    if (StoryActionGuards.canSync(story)) options += "Sync" to { syncStory(story) }
    options += "Move" to { showMoveStoryDialog(story) }
    options += "Delete" to {
        confirm("Delete ${story.title}?") {
            storage.deleteStory(story.id)
            showLibrary()
        }
    }
    showStyledOptionsDialog(story.title, options)
}
