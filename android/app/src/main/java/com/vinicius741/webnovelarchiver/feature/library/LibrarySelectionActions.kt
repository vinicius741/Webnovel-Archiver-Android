package com.vinicius741.webnovelarchiver.feature.library

import android.view.View
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.feature.downloads.showQueue
import com.vinicius741.webnovelarchiver.feature.settings.showSettings
import com.vinicius741.webnovelarchiver.feature.updates.showUpdates
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.AppBarAction
import com.vinicius741.webnovelarchiver.ui.BOTTOM_BAR_VIEW_TAG
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.button
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.setAppBarTitle
import com.vinicius741.webnovelarchiver.ui.showStyledOptionsDialog
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
        setAppBarTitle(LibrarySelectionPlanning.title(next.size))
        libraryScreenState.refreshStoryCards(storyId)
    }
    return storyId in next
}

/** App-bar actions: normal navigation shortcuts, or just Select all while selecting. */
internal fun ScreenHost.libraryAppBarActions(
    selecting: Boolean,
    onSelectAll: () -> Unit,
): List<AppBarAction> =
    if (selecting) {
        listOf(AppBarAction(R.drawable.wna_check, "Select all") { onSelectAll() })
    } else {
        listOf(
            AppBarAction(R.drawable.wna_refresh, "Updates") { showUpdates() },
            AppBarAction(R.drawable.wna_download, "Downloads") { showQueue() },
            AppBarAction(R.drawable.wna_settings, "Settings") { showSettings() },
        )
    }

/** Select all / deselect all for [visibleIds]. */
internal fun ScreenHost.toggleSelectAllLibrary(visibleIds: List<String>) {
    val next = LibrarySelectionPlanning.toggleAll(libraryScreenState.selectedStoryIds, visibleIds)
    libraryScreenState.selectedStoryIds = next
    if (next.isEmpty()) {
        showLibrary()
    } else {
        setAppBarTitle(LibrarySelectionPlanning.title(next.size))
        libraryScreenState.refreshStoryCards(null)
    }
}

/** Bottom action bar of the Library's selection mode; the count lives in the app bar title. */
internal fun ScreenHost.makeLibrarySelectionBar(): View =
    LinearLayout(app).apply {
        orientation = LinearLayout.HORIZONTAL
        tag = BOTTOM_BAR_VIEW_TAG
        setPadding(0, dp(Space.LG), 0, 0)
        val selectedIds = { libraryScreenState.selectedStoryIds.toList() }
        button("Move", Btn.TONAL, R.drawable.wna_folder) { showMoveStoriesDialog(selectedIds()) }
        button("Delete", Btn.ERROR, R.drawable.wna_delete) { confirmDeleteStories(selectedIds()) }
        for (index in 0 until childCount) {
            (getChildAt(index).layoutParams as LinearLayout.LayoutParams).apply {
                width = 0
                weight = 1f
            }
        }
        setBackgroundColor(ThemeManager.colors.background)
    }

private fun ScreenHost.confirmDeleteStories(storyIds: List<String>) {
    if (storyIds.isEmpty()) return
    confirm(LibrarySelectionPlanning.deletePrompt(storyIds.size), confirmLabel = "Delete") {
        scope.launch {
            storyIds.forEach { repository.deleteStory(it) }
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
    val novelLabel = if (storyIds.size == 1) "Novel" else "Novels"
    showStyledOptionsDialog("Move ${storyIds.size} $novelLabel", options)
}
