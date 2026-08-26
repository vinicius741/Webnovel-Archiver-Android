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
        // Empty library: show the same empty state the Library screen shows instead of a bare
        // filter bar with nothing to filter.
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

        // Filter state is held in local closures rather than on ScreenHost because the screen never
        // rebuilds its own view tree: Select All / Deselect All re-filter in place (see [applyFilters]),
        // and the activity's configChanges declaration keeps the view tree alive across rotation/fold.
        // Opening the screen fresh always starts from the All tab + no tag filters + last-updated sort.
        var filterState = LibraryFilterState()

        // Declared up front as reassignable lambdas (matching the Library screen) so the search watcher,
        // chip callbacks, tab bar, and Select All / Deselect All can all close over them before their
        // real bodies are assigned further down.
        var applyFilters: () -> Unit = {}
        var currentFilteredIds: () -> List<String> = { emptyList() }

        val search = makeSearchField(context, "Search novels")

        // Rebuild the chip set whenever the active tab changes so the tag/source filters follow the
        // tab (All = union, a specific tab = only that tab's labels).
        // Declared before the tab bar so the bar's selection lambda can close over it.
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
                    // Re-render the chip row so the tapped chip shows its selected state.
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

        // Wire the search watcher after `filters`/`refreshFilters` exist (mirrors the Library screen)
        // so typing updates the collapsible header's active-filter indicators and re-narrows the chip
        // row under the typed query, not just the visible rows.
        search.doAfterTextChanged {
            filterState = filterState.copy(query = it?.toString().orEmpty())
            filters.syncActiveFilters(filterState.selectedTags)
            refreshFilters(filterState.selectedTabId, filterState.selectedTags)
            applyFilters()
        }

        // Select All / Deselect All act on the *currently filtered* list (matching the Follow Updates
        // selection screen), so selecting every novel in a search result or a single tab is one tap.
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

        // Reusable row container rebuilt by [applyFilters]. Wraps it in a scroller so a long filtered
        // list scrolls independently of the pinned tab bar / search / chips above it.
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(scroll(rows), verticalFill())

        // Bulk actions docked at the bottom as full-width primary CTAs.
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

        // Snapshot the current filtered ids for Select All / Deselect All. Computed on demand rather
        // than cached so it always reflects the latest search/tab/tag state at click time.
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

        // Rebuild the row list from the current filter snapshot, then refresh the bulk-action labels.
        // Rebuilding rather than re-rendering keeps the tab bar, search field, and chips untouched, so
        // the user's filter context is never disturbed by a Select All or a row toggle.
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
                    // Archives share the live title; label them so bulk move/delete is not ambiguous.
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
