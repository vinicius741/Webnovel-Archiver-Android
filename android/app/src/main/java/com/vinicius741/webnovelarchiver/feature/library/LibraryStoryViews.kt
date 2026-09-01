package com.vinicius741.webnovelarchiver.feature.library

import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.metrics.PatreonEarningsPlanning
import com.vinicius741.webnovelarchiver.domain.model.SourceAvailability
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.StoryActionGuards
import com.vinicius741.webnovelarchiver.domain.story.StoryBookmarkPlanning
import com.vinicius741.webnovelarchiver.feature.details.showDetails
import com.vinicius741.webnovelarchiver.feature.library.LibraryQuery
import com.vinicius741.webnovelarchiver.feature.story.syncStory
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.ui.GridLayout
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.card
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.coverImage
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.layout.ScreenLayoutResult
import com.vinicius741.webnovelarchiver.ui.makeChapterCoverageSummary
import com.vinicius741.webnovelarchiver.ui.makeEmptyState
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.publicationStatusBadge
import com.vinicius741.webnovelarchiver.ui.row
import com.vinicius741.webnovelarchiver.ui.scoreRow
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.showStyledOptionsDialog
import com.vinicius741.webnovelarchiver.ui.sourceAvailabilityBadge
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import com.vinicius741.webnovelarchiver.ui.updateChapterCoverageSummary
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToLong

private data class LibraryProgressTag(
    val storyId: String,
)

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
        // An empty state is page-level content, not a story card. Let it span the grid so it stays
        // centered instead of being constrained to the first cell on multi-column layouts.
        list.columnCount = 1
        // Distinguish "this tab is just empty" (no search/tag filter active) from "your filters
        // excluded everything". The first invites an action; the second should not, since the fix
        // is to clear filters, not add a story.
        val hasActiveFilter = filter.isNotBlank() || selectedTags.isNotEmpty()
        val state =
            if (hasActiveFilter) {
                makeEmptyState(app, message = "Try clearing your search or filters.", title = "No matches", iconRes = R.drawable.wna_search)
            } else {
                makeEmptyState(
                    app,
                    message = "Novels you add or move here will show up in this tab.",
                    title = "Nothing here yet",
                    iconRes = R.drawable.wna_menu_book,
                    actionLabel = "Add a story",
                    onAction = { showAddStory() },
                )
            }
        list.addView(
            state,
            ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        return
    }
    // The library always uses the horizontal story card (80×120 cover on the left, text on the right)
    // regardless of column count — the same layout as phone mode, just reflowed into N columns on
    // wider windows.
    visible.forEach { story ->
        list.addView(
            list.card {
                val content = buildStoryCard(story)
                content.background = selectableRipple(ThemeManager.colors.onSurface)
                content.isClickable = true
                content.setOnClickListener { showDetails(story.id) }
                content.setOnLongClickListener {
                    // Progress is patched without rebuilding this card, so resolve the latest story
                    // before an action that may persist it; never act on the pre-download snapshot.
                    repository.story(story.id)?.let(::showStoryActionsDialog)
                    true
                }
                addView(content)
                if (story.totalChapters > 0) {
                    addView(
                        makeChapterCoverageSummary(
                            context,
                            StoryBookmarkPlanning.downloadedFlags(story),
                            StoryBookmarkPlanning.bookmarkFraction(story),
                            story.downloadedChapters,
                            story.totalChapters,
                        ).apply {
                            tag = LibraryProgressTag(story.id)
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

/** Patches only the count text and coverage bar for a story, preserving every scroll/gesture view. */
internal fun patchLibraryProgress(
    root: android.view.View,
    story: Story,
) {
    if (root.tag == LibraryProgressTag(story.id)) {
        updateChapterCoverageSummary(
            root,
            StoryBookmarkPlanning.downloadedFlags(story),
            StoryBookmarkPlanning.bookmarkFraction(story),
            story.downloadedChapters,
            story.totalChapters,
        )
    }
    if (root is ViewGroup) {
        for (index in 0 until root.childCount) patchLibraryProgress(root.getChildAt(index), story)
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
            val provider = SourceRegistry.getProvider(story.sourceId, story.sourceUrl)
            val publicationStatusBadge = publicationStatusBadge(story)
            val sourceAvailabilityBadge = sourceAvailabilityBadge(story)
            if (provider != null || publicationStatusBadge != null || sourceAvailabilityBadge != null) {
                addView(
                    LinearLayout(app).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(Space.XS), 0, 0)
                        provider?.let {
                            addView(makeText(app, it.name, Type.LABEL_SMALL, ThemeManager.colors.primary))
                        }
                        publicationStatusBadge?.let {
                            val badgeLayoutParams =
                                LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                )
                            if (provider != null) {
                                badgeLayoutParams.marginStart = dp(Space.SM)
                            }
                            addView(
                                it,
                                badgeLayoutParams,
                            )
                        }
                        sourceAvailabilityBadge?.let {
                            val badgeLayoutParams =
                                LinearLayout
                                    .LayoutParams(
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ).apply {
                                        if (provider != null || publicationStatusBadge != null) marginStart = dp(Space.SM)
                                    }
                            addView(it, badgeLayoutParams)
                        }
                    },
                )
            }
            story.score?.takeIf { it.isNotBlank() }?.let { score ->
                addView(scoreRow(score))
            }
            story.patreonStats?.let(PatreonEarningsPlanning::estimate)?.let { earnings ->
                addView(
                    makeText(app, formatLibraryPatreonStats(earnings), Type.LABEL_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(0, dp(Space.XS), 0, 0)
                    },
                )
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

private fun formatLibraryPatreonStats(earnings: PatreonEarningsPlanning.PatreonEarnings): String {
    val amount =
        earnings.monthlyUsdCents?.let { cents ->
            val prefix = if (earnings.amountIsEstimated) "~" else ""
            "${prefix}${'$'}${NumberFormat.getIntegerInstance(Locale.US).format((cents / 100.0).roundToLong())}/mo"
        }
    val membersPrefix = if (earnings.membersIsEstimated) "~" else ""
    val members = "${membersPrefix}${NumberFormat.getIntegerInstance().format(earnings.paidMembers)}"
    val membersLabel = if (earnings.membersIsEstimated) "est. paid" else "paid"
    return if (amount != null) "Patreon $amount · $members $membersLabel" else "Patreon · $members $membersLabel"
}

private fun ScreenHost.showStoryActionsDialog(story: Story) {
    val options = mutableListOf<Pair<String, () -> Unit>>("Open" to { showDetails(story.id) })
    if (StoryActionGuards.canSync(story)) {
        val syncLabel =
            if (story.sourceSyncState.availability == SourceAvailability.available) {
                "Sync"
            } else {
                "Check Source Again"
            }
        options += syncLabel to { syncStory(story) }
    }
    options += "Move" to { showMoveStoryDialog(story) }
    options += "Select Multiple" to { showLibrarySelection(setOf(story.id)) }
    options += "Delete" to {
        confirm("Delete ${story.title}?") {
            scope.launch {
                repository.deleteStory(story.id)
                showLibrary()
            }
        }
    }
    showStyledOptionsDialog(story.title, options)
}
