package com.vinicius741.webnovelarchiver.feature.library

import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.widget.doAfterTextChanged
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.button
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.flow
import com.vinicius741.webnovelarchiver.ui.fullButton
import com.vinicius741.webnovelarchiver.ui.makeEmptyState
import com.vinicius741.webnovelarchiver.ui.makeSearchField
import com.vinicius741.webnovelarchiver.ui.makeSelectableCardRow
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.scroll
import com.vinicius741.webnovelarchiver.ui.showStyledOptionsDialog
import com.vinicius741.webnovelarchiver.ui.size
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.toast
import com.vinicius741.webnovelarchiver.ui.verticalFill
import kotlinx.coroutines.launch

internal fun ScreenHost.showLibrarySelection(initialSelectedIds: Set<String> = emptySet()) {
    val stories = repository.library()
    val tabs = repository.getTabs().sortedBy { it.order }
    val selectedIds = initialSelectedIds.toMutableSet()
    screen(route = AppRoute.LibrarySelection(initialSelectedIds), title = "Organize Novels", onBack = { navigateBack() }) {
        // Empty library: same empty state as the Library screen, not a bare filter bar.
        if (stories.isEmpty()) {
            addView(
                makeEmptyState(
                    context,
                    title = "Your library is empty",
                    message = "Import a story before organizing novels into tabs.",
                    iconRes = R.drawable.wna_menu_book,
                ),
            )
            return@screen
        }

        // Filter state is local, not on ScreenHost: this screen never rebuilds its view tree —
        // Select All / Deselect All re-filter in place ([applyFilters]), and configChanges keeps
        // the tree alive across rotation/fold.
        var filterState = LibraryFilterState()

        // Declared up front as reassignable lambdas so the search watcher, chip callbacks, tab
        // bar, and bulk actions can close over them before their real bodies are assigned.
        var applyFilters: () -> Unit = {}
        var currentFilteredIds: () -> List<String> = { emptyList() }

        val search = makeSearchField(context, "Search novels")

        // Chips rebuild on tab change so tag/source filters follow the tab (All = union, a
        // specific tab = that tab's labels). Declared before the tab bar so it can close over it.
        var refreshFilters: (String?, Set<String>) -> Unit = { _, _ -> }

        val filters =
            makeLibraryFilters(
                context,
                search,
                tabs.isNotEmpty(),
                stories,
                filterState.selectedTabId,
                filterState.selectedTags,
                filterState.sortOption,
                filterState.sortAscending,
                { newSort ->
                    filterState = filterState.copy(sortOption = newSort.first, sortAscending = newSort.second)
                    applyFilters()
                },
                { tag ->
                    val nextTags = filterState.selectedTags.toMutableSet()
                    if (!nextTags.add(tag)) nextTags.remove(tag)
                    filterState = filterState.copy(selectedTags = nextTags)
                    refreshFilters(filterState.selectedTabId, filterState.selectedTags)
                    applyFilters()
                },
            )
        refreshFilters = filters.rebuildChips
        val tabBar =
            makeLibraryTabBar(context, tabs, stories, filterState.selectedTabId) { newTabId ->
                filterState = filterState.copy(selectedTabId = newTabId)
                refreshFilters(filterState.selectedTabId, filterState.selectedTags)
                applyFilters()
            }
        addView(tabBar.view)
        addView(filters.view)

        // Wire the search watcher after filters/refreshFilters exist so typing updates the header
        // indicators and narrows the chip row, not just the visible rows.
        search.doAfterTextChanged {
            filterState = filterState.copy(query = it?.toString().orEmpty())
            filters.syncActiveFilters(filterState.selectedTags)
            refreshFilters(filterState.selectedTabId, filterState.selectedTags)
            applyFilters()
        }

        // Select All / Deselect All act on the currently filtered list — one tap selects a whole
        // search result or tab.
        var refreshBulkActions: () -> Unit = {}
        flow {
            button("Select All", Btn.TEXT, R.drawable.wna_check) {
                selectedIds.addAll(currentFilteredIds())
                refreshBulkActions()
                applyFilters()
            }
            button("Deselect All", Btn.TEXT, R.drawable.wna_close) {
                selectedIds.removeAll(currentFilteredIds().toSet())
                refreshBulkActions()
                applyFilters()
            }
        }

        // Row container rebuilt by [applyFilters]; scrolls independently of the pinned bar/search/
        // chips above.
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(scroll(rows), verticalFill())

        lateinit var moveButton: Button
        lateinit var deleteButton: Button
        moveButton =
            fullButton("Move ${selectedIds.size} Selected", Btn.TONAL, R.drawable.wna_folder, bottomMarginDp = 8) {
                if (selectedIds.isEmpty()) toast("No novels selected") else showMoveStoriesDialog(selectedIds.toList())
            }
        deleteButton =
            fullButton("Delete Selected", Btn.ERROR, R.drawable.wna_delete, bottomMarginDp = 0) {
                if (selectedIds.isEmpty()) {
                    toast("No novels selected")
                } else {
                    confirm("Delete ${selectedIds.size} selected novels?", confirmLabel = "Delete") {
                        scope.launch {
                            selectedIds.forEach { repository.deleteStory(it) }
                            showLibrary()
                        }
                    }
                }
            }
        refreshBulkActions = {
            moveButton.text = "Move ${selectedIds.size} Selected"
            deleteButton.text = if (selectedIds.isEmpty()) "Delete Selected" else "Delete ${selectedIds.size} Selected"
        }

        // Current filtered ids for Select All / Deselect All; computed on demand so it reflects
        // the latest filters at click time.
        currentFilteredIds = {
            LibraryQuery
                .filterAndSort(
                    stories,
                    filterState.query,
                    filterState.selectedTabId,
                    filterState.selectedTags,
                    filterState.sortOption,
                    filterState.sortAscending,
                ).map { it.id }
        }

        // Rebuild rows only (never the filter UI) so the user's filter context survives a Select
        // All or row toggle, then refresh the bulk-action labels.
        applyFilters = {
            val visible =
                LibraryQuery.filterAndSort(
                    stories,
                    filterState.query,
                    filterState.selectedTabId,
                    filterState.selectedTags,
                    filterState.sortOption,
                    filterState.sortAscending,
                )
            rows.removeAllViews()
            if (visible.isEmpty()) {
                rows.addView(
                    makeText(
                        context,
                        "No novels match these filters.",
                        Type.BODY_MEDIUM,
                        ThemeManager.colors.onSurfaceVariant,
                    ).apply { setPadding(0, dp(Space.LG), 0, dp(Space.LG)) },
                )
            } else {
                visible.forEach { story ->
                    // Archives share the live title; labeled so bulk move/delete is unambiguous.
                    val subtitle =
                        if (story.isArchived == true) {
                            listOfNotNull(story.author.takeIf { it.isNotBlank() }, "Archived").joinToString(" · ")
                        } else {
                            story.author
                        }
                    rows.addView(
                        makeSelectableCardRow(
                            context,
                            title = story.title,
                            subtitle = subtitle,
                            selected = selectedIds.contains(story.id),
                        ) { checked ->
                            if (checked) selectedIds.add(story.id) else selectedIds.remove(story.id)
                            refreshBulkActions()
                        },
                    )
                }
            }
            refreshBulkActions()
        }

        applyFilters()
    }
}

internal fun ScreenHost.showMoveStoriesDialog(storyIds: List<String>) {
    val tabs = repository.getTabs().sortedBy { it.order }
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
                    showLibrary()
                }
                Unit
            }
        }
    val novelLabel = if (storyIds.size == 1) "Novel" else "Novels"
    showStyledOptionsDialog("Move ${storyIds.size} $novelLabel", options)
}

internal fun ScreenHost.showMoveStoryDialog(story: Story) {
    val tabs = repository.getTabs().sortedBy { it.order }
    val tabOptions = listOf(null to "Unassigned") + tabs.map { it.id to it.name }
    val options =
        tabOptions.map { (tabId, label) ->
            label to {
                story.tabId = tabId
                scope.launch {
                    repository.addOrUpdateStory(story)
                    showLibrary()
                }
                Unit
            }
        }
    showStyledOptionsDialog("Move Novel", options)
}
