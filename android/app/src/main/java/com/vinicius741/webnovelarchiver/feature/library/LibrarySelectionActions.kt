package com.vinicius741.webnovelarchiver.feature.library

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.StoryActionGuards
import com.vinicius741.webnovelarchiver.domain.story.UpdatesPlanning
import com.vinicius741.webnovelarchiver.feature.narration.narration
import com.vinicius741.webnovelarchiver.feature.story.queueDownload
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.BOTTOM_BAR_VIEW_TAG
import com.vinicius741.webnovelarchiver.ui.RAIL_MIN_WIDTH_DP
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.showStyledOptionsDialog
import com.vinicius741.webnovelarchiver.ui.strokeBg
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

/** Enters selection mode on the Library with [storyId] preselected. */
internal fun ScreenHost.startLibrarySelection(storyId: String) {
    libraryScreenState.selectedStoryIds = setOf(storyId)
    showLibrary()
}

/** Drops selected ids that left the library and returns how many remain. */
internal fun ScreenHost.pruneLibrarySelection(stories: List<Story>): Int {
    libraryScreenState.selectedStoryIds =
        LibrarySelectionPlanning.prune(libraryScreenState.selectedStoryIds, stories.map(Story::id))
    return libraryScreenState.selectedStoryIds.size
}

internal fun ScreenHost.exitLibrarySelection() {
    libraryScreenState.selectedStoryIds = emptySet()
    showLibrary()
}

/**
 * Toggles one card in place. Returns whether [storyId] is now selected; unselecting the last
 * novel leaves selection mode, which rebuilds the Library.
 */
internal fun ScreenHost.toggleLibrarySelection(storyId: String): Boolean {
    val next = LibrarySelectionPlanning.toggle(libraryScreenState.selectedStoryIds, storyId)
    libraryScreenState.selectedStoryIds = next
    if (next.isEmpty()) {
        showLibrary()
    } else {
        setLibrarySelectionTitle(next.size)
        libraryScreenState.refreshStoryCards(storyId)
    }
    return storyId in next
}

/** Select all / deselect all for [visibleIds]. */
internal fun ScreenHost.toggleSelectAllLibrary(visibleIds: List<String>) {
    val next = LibrarySelectionPlanning.toggleAll(libraryScreenState.selectedStoryIds, visibleIds)
    libraryScreenState.selectedStoryIds = next
    if (next.isEmpty()) {
        showLibrary()
    } else {
        setLibrarySelectionTitle(next.size)
        libraryScreenState.refreshStoryCards(null)
    }
}

/**
 * Bottom action bar of the Library's selection mode (Move / Download / Delete). It stands in for
 * the navigation bar, which the screen hides while selecting; the count lives in the top bar.
 */
internal fun ScreenHost.makeLibrarySelectionBar(): View {
    val colors = ThemeManager.colors
    val selectedIds = { libraryScreenState.selectedStoryIds.toList() }
    val wide = app.resources.configuration.screenWidthDp >= RAIL_MIN_WIDTH_DP
    val bar =
        LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            val radius = if (wide) dp(WIDE_BAR_RADIUS_DP).toFloat() else 0f
            background =
                strokeBg(
                    SELECTION_BAR_FILL,
                    radius,
                    ColorUtils.setAlphaComponent(colors.primary, SELECTION_BAR_BORDER_ALPHA),
                    dp(1).coerceAtLeast(1),
                )
            addView(selectionBarButton(R.drawable.wna_folder, "Move", colors.onPrimaryContainer) { showMoveStoriesDialog(selectedIds()) })
            addView(
                selectionBarButton(
                    R.drawable.wna_download,
                    "Download",
                    colors.onPrimaryContainer,
                ) { downloadSelectedStories(selectedIds()) },
            )
            addView(selectionBarButton(R.drawable.wna_delete, "Delete", colors.error) { confirmDeleteStories(selectedIds()) })
        }
    return FrameLayout(app).apply {
        tag = BOTTOM_BAR_VIEW_TAG
        if (wide) setPadding(dp(Spacing.LG), dp(Spacing.SM), dp(Spacing.LG), dp(Spacing.LG))
        addView(
            bar,
            FrameLayout.LayoutParams(
                if (wide) dp(WIDE_BAR_MAX_WIDTH_DP) else ViewGroup.LayoutParams.MATCH_PARENT,
                dp(SELECTION_BAR_HEIGHT_DP),
                Gravity.CENTER_HORIZONTAL,
            ),
        )
    }
}

private fun ScreenHost.selectionBarButton(
    iconRes: Int,
    label: String,
    color: Int,
    onClick: () -> Unit,
): View =
    LinearLayout(app).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        background = selectableRipple(color)
        contentDescription = label
        addView(
            ImageView(app).apply { setImageDrawable(app.tintedIcon(iconRes, color)) },
            LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)),
        )
        addView(
            makeText(app, label, Type.LABEL_MEDIUM, color).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(Spacing.XS), 0, 0)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
    }

/** Queues every chapter not downloaded yet for each selected novel, then leaves selection mode. */
private fun ScreenHost.downloadSelectedStories(storyIds: List<String>) {
    val stories = storyIds.mapNotNull { repository.story(it) }.filter(StoryActionGuards::canQueueDownloads)
    val withMissing = stories.filter { UpdatesPlanning.missingChapters(it) > 0 }
    if (withMissing.isEmpty()) {
        toast(if (stories.size == 1) "Every chapter is already downloaded" else "Every chapter of these novels is already downloaded")
        return
    }
    withMissing.forEach { queueDownload(it, UpdatesPlanning.missingChapterIndexes(it)) }
    toast(LibrarySelectionPlanning.queuedMessage(withMissing.size))
    exitLibrarySelection()
}

private fun ScreenHost.confirmDeleteStories(storyIds: List<String>) {
    if (storyIds.isEmpty()) return
    val removedFromSource = storyIds.sumOf { id -> repository.story(id)?.chapters?.count { it.isRemovedFromSource } ?: 0 }
    confirm(LibrarySelectionPlanning.deletePrompt(storyIds.size, removedFromSource), confirmLabel = "Delete") {
        scope.launch {
            storyIds.forEach {
                repository.deleteStory(it)
                narration.deleteStory(it)
            }
            exitLibrarySelection()
        }
    }
}

private fun ScreenHost.showMoveStoriesDialog(storyIds: List<String>) {
    if (storyIds.isEmpty()) return
    val tabs = repository.tabs.get().sortedBy { it.order }
    val tabOptions = listOf(null to "Unassigned") + tabs.map { it.id to it.name }
    val options =
        tabOptions.map { (tabId, label) ->
            label to {
                scope.launch {
                    storyIds.forEach { id ->
                        repository.story(id)?.let { story ->
                            story.tabId = tabId
                            repository.addOrUpdateStory(story)
                        }
                    }
                    exitLibrarySelection()
                }
                Unit
            }
        }
    val novelLabel = if (storyIds.size == 1) "novel" else "novels"
    showStyledOptionsDialog("Move ${storyIds.size} $novelLabel", options)
}

private const val SELECTION_BAR_HEIGHT_DP = 72
private const val SELECTION_BAR_FILL = 0xFF2A2417.toInt()
private const val SELECTION_BAR_BORDER_ALPHA = 0x40
private const val WIDE_BAR_RADIUS_DP = 20
private const val WIDE_BAR_MAX_WIDTH_DP = 560
private const val ICON_DP = 24
