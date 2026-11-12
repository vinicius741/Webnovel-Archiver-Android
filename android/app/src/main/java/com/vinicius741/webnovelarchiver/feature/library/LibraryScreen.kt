package com.vinicius741.webnovelarchiver.feature.library

import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.Tab
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.BackIntercept
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.navigation.runUiOperation
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.ui.MaxWidthFrameLayout
import com.vinicius741.webnovelarchiver.ui.ScreenChrome
import com.vinicius741.webnovelarchiver.ui.layout.resolveScreenLayout
import com.vinicius741.webnovelarchiver.ui.makeEmptyState
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.screenMetrics
import com.vinicius741.webnovelarchiver.ui.verticalFill
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * The Library: a large app bar (search opens in the bar), the tab bar with counts, the active
 * sort/filter row, and one swipeable page of list cards or covers per tab. Long-press starts
 * selection, which swaps the navigation bar for a Move / Download / Delete bar.
 */
internal fun ScreenHost.showLibrary() {
    activeStory = null
    // Capture the ScreenHost here; inside screen{} `this` is the body LinearLayout.
    val host = this
    // Re-render on window/setting changes so the column count reflows live.
    rerender = { showLibrary() }
    // A re-render (fold, search, selection) replaces the screen the open sheet was built against.
    libraryExtras.sortFilterSheet?.get()?.dismiss()
    libraryExtras.sortFilterSheet = null
    val grid = currentLibraryGrid()
    var stories: List<Story> = repository.library()
    val selectedCount = pruneLibrarySelection(stories)
    val selecting = selectedCount > 0
    val searching = !selecting && (libraryExtras.searchOpen || libraryScreenState.query.isNotEmpty())
    var renderedProgress = stories.associate { it.id to (it.downloadedChapters to it.totalChapters) }
    var refreshLibraryContent: ((List<Story>) -> Unit)? = null
    val tabs = repository.tabs.get().sortedBy { it.order }
    val hasUnassigned = stories.any { it.tabId == null }
    val prefs = repository.getDisplayPreferences()
    var filterState =
        LibraryFilterState(
            selectedTabId = LibraryTabSelection.resolve(prefs.libraryTabId, tabs, hasUnassigned),
            query = libraryScreenState.query,
            selectedTags = libraryScreenState.selectedTags,
            sortOption = prefs.librarySortOption.ifBlank { "lastUpdated" },
            sortAscending = prefs.librarySortAscending,
        )
    // One closure applies filters to whichever grid surface (shared grid or pager adapter) is showing.
    var applyFilters: () -> Unit = {}
    var onFiltersChanged: () -> Unit = {}
    val openSheet = {
        val handle =
            showLibrarySortFilterSheet(stories, tabs, { filterState }) { next ->
                val sortChanged = next.sortOption != filterState.sortOption || next.sortAscending != filterState.sortAscending
                filterState = next
                libraryScreenState.selectedTags = next.selectedTags
                if (sortChanged) persistLibrarySort(next.sortOption, next.sortAscending)
                applyFilters()
                onFiltersChanged()
            }
        libraryExtras.sortFilterSheet = WeakReference(handle)
    }
    // R22: debounce the per-page grid rebuild to the pause between keystrokes.
    var searchGeneration = 0
    val onQuery: (String) -> Unit = { query ->
        filterState = filterState.copy(query = query)
        libraryScreenState.query = query
        refreshBackCallback()
        searchGeneration += 1
        debounceLibraryFilterApply(scope, searchGeneration, { searchGeneration }, applyFilters)
    }
    screen(
        route = AppRoute.Library,
        title = LibrarySelectionPlanning.screenTitle(selectedCount),
        chrome = ScreenChrome.IMMERSIVE,
        closeAction = if (selecting) ({ exitLibrarySelection() }) else null,
        backIntercept = libraryBackIntercept(),
    ) {
        val setFilterDot =
            addLibraryHeader(
                this,
                selectedCount,
                searching,
                stories.size,
                grid.viewMode,
                filterState,
                LibraryHeaderActions(
                    onSelectAll = { toggleSelectAllLibrary(filterState.applyTo(stories).map(Story::id)) },
                    onOpenSheet = { openSheet() },
                    onQuery = onQuery,
                ),
            )
        if (!selecting && !searching) addView(host.continueListeningCard())
        val initialSelectedTabId = filterState.selectedTabId
        if (stories.isEmpty()) {
            renderEmptyLibrary(this, tabs, stories, initialSelectedTabId)
            return@screen
        }

        // Shared bar+pager tab ordering: Unassigned (null sentinel) first when present, then tabs, then All.
        val pageTabs: List<String?> =
            buildList {
                if (hasUnassigned) add(null)
                addAll(tabs.map { it.id })
                add(LibraryTabSelection.ALL_TAB_ID)
            }
        val tabBar =
            makeLibraryTabBar(context, tabs, stories, filterState.selectedTabId) { newTabId ->
                filterState = filterState.copy(selectedTabId = newTabId)
                persistLibraryTab(newTabId)
                applyFilters()
            }
        addView(tabBar.view)
        val activeRow =
            makeLibraryActiveFilterRow(
                onOpenSheet = { openSheet() },
                onRemoveTag = { tag ->
                    filterState = filterState.copy(selectedTags = filterState.selectedTags - tag)
                    libraryScreenState.selectedTags = filterState.selectedTags
                    applyFilters()
                    onFiltersChanged()
                },
                onClear = {
                    filterState = filterState.copy(selectedTags = emptySet())
                    libraryScreenState.selectedTags = emptySet()
                    applyFilters()
                    onFiltersChanged()
                },
            )
        addView(activeRow.view)
        activeRow.render(filterState)
        onFiltersChanged = {
            activeRow.render(filterState)
            setFilterDot(filterState.selectedTags.isNotEmpty())
            refreshBackCallback()
        }

        val refreshFromRepository: (List<Story>, (List<Story>) -> Unit) -> Unit = { latest, replace ->
            val changed = latest.filter { renderedProgress[it.id] != (it.downloadedChapters to it.totalChapters) }
            renderedProgress = latest.associate { it.id to (it.downloadedChapters to it.totalChapters) }
            stories = latest
            replace(latest)
            changed.forEach { patchLibraryProgress(frame, it) }
        }
        if (pageTabs.size >= 2) {
            // Each page owns a scrolling grid; bar and pager stay two-way synced.
            val adapter = LibraryPagesAdapter(host, stories, pageTabs, grid)
            val pager =
                ViewPager2(context).apply {
                    this.adapter = adapter
                    // No over-scroll glow; the tab bar's indicator signals the swipe.
                    getChildAt(0).overScrollMode = android.view.View.OVER_SCROLL_NEVER
                }
            pager.setCurrentItem(pageTabs.indexOf(filterState.selectedTabId).coerceAtLeast(0), false)
            applyFilters =
                { adapter.updateFilter(filterState.query, filterState.selectedTags, filterState.sortOption, filterState.sortAscending) }
            libraryScreenState.refreshStoryCards = adapter::refreshCards
            refreshLibraryContent = { latest -> refreshFromRepository(latest, adapter::replaceStories) }
            // The changed-id check stops bar-initiated switches from feeding back.
            var suppressingPageCallback = false
            pager.registerOnPageChangeCallback(
                object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageSelected(position: Int) {
                        if (suppressingPageCallback) return
                        val newTabId = pageTabs.getOrNull(position) ?: return
                        if (newTabId != filterState.selectedTabId) {
                            filterState = filterState.copy(selectedTabId = newTabId)
                            persistLibraryTab(newTabId)
                            tabBar.selectVisual(newTabId)
                            applyFilters()
                        }
                    }
                },
            )
            // Animate to the page; the flag makes the resulting onPageSelected a no-op.
            tabBar.onSelectFromBar = { id ->
                val idx = pageTabs.indexOf(id)
                if (idx in 0 until pager.adapter!!.itemCount && idx != pager.currentItem) {
                    suppressingPageCallback = true
                    pager.setCurrentItem(idx, true)
                    pager.post { suppressingPageCallback = false }
                }
            }
            addView(pager, verticalFill())
            applyFilters()
        } else {
            val list = RecyclerView(context)
            val adapter = LibraryStoryAdapter(host, grid, filterState.selectedTabId, stories)
            adapter.attachTo(list)
            libraryScreenState.refreshStoryCards = adapter::refreshCards
            applyFilters =
                { adapter.updateFilter(filterState.query, filterState.selectedTags, filterState.sortOption, filterState.sortAscending) }
            refreshLibraryContent = { latest -> refreshFromRepository(latest, adapter::replaceStories) }
            val gridShell =
                MaxWidthFrameLayout(context).apply {
                    maxContentWidthDp = grid.maxContentWidthDp
                    addView(
                        list,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            Gravity.CENTER_HORIZONTAL,
                        ),
                    )
                }
            val scrollKey = LibraryTabSelection.memoryKey(filterState.selectedTabId)
            list.trackScrollInto(libraryScreenState.tabScrollPositions, scrollKey)
            addView(gridShell, verticalFill())
            applyFilters()
            list.restoreScrollOnce(libraryScreenState.tabScrollPositions[scrollKey] ?: 0)
        }
        if (selecting) addView(makeLibrarySelectionBar())
    }
    // The selection bar replaces the navigation until selection ends (the next render resets it).
    if (selecting) shell.setNavigationSuppressed(true)
    refreshLibraryContent?.let { refresh -> observeLibrary(refresh) }
}

/** The header's callbacks into the Library's filter state. */
private class LibraryHeaderActions(
    val onSelectAll: () -> Unit,
    val onOpenSheet: () -> Unit,
    val onQuery: (String) -> Unit,
)

/**
 * Adds the app bar for the current mode (selecting, searching, or browsing) and returns how to
 * show or hide its Sort & filter dot.
 */
private fun ScreenHost.addLibraryHeader(
    container: LinearLayout,
    selectedCount: Int,
    searching: Boolean,
    novelCount: Int,
    viewMode: LibraryViewMode,
    state: LibraryFilterState,
    actions: LibraryHeaderActions,
): (Boolean) -> Unit {
    val filtersActive = state.selectedTags.isNotEmpty()
    return when {
        selectedCount > 0 -> {
            container.addView(makeLibrarySelectionTopBar(selectedCount, onClose = { exitLibrarySelection() }) { actions.onSelectAll() })
            val noDot: (Boolean) -> Unit = {}
            noDot
        }
        searching -> {
            val bar = makeLibrarySearchBar(state.query, filtersActive, onClose = { closeLibrarySearch() }) { actions.onOpenSheet() }
            container.addView(bar.view)
            bar.field.doAfterTextChanged { actions.onQuery(it?.toString().orEmpty()) }
            if (libraryExtras.searchOpen && state.query.isEmpty()) focusSearch(bar.field)
            bar.setFilterDot
        }
        else -> {
            val appBar = makeLibraryAppBar(novelCount, viewMode, filtersActive) { actions.onOpenSheet() }
            container.addView(appBar.view)
            val setDot: (Boolean) -> Unit = { on -> appBar.setDot(SORT_FILTER_LABEL, on) }
            setDot
        }
    }
}

/** Patches the rendered Library when the repository publishes a new library version. */
private fun ScreenHost.observeLibrary(refresh: (List<Story>) -> Unit) {
    val renderedRoot = frame.getChildAt(0)
    // Captured before launch: still catches a racing publish because StateFlow re-emits a new version.
    var observedLibraryVersion = repository.downloadState.value.libraryVersion
    screenObserver =
        scope.launch {
            repository.downloadState.collect { snapshot ->
                if (renderedRoot.parent !== frame) return@collect
                if (snapshot.libraryVersion == observedLibraryVersion) return@collect
                observedLibraryVersion = snapshot.libraryVersion
                refresh(snapshot.library)
            }
        }
}

/** Closes the search bar, dropping its query. */
private fun ScreenHost.closeLibrarySearch() {
    libraryExtras.searchOpen = false
    libraryScreenState.query = ""
    showLibrary()
}

/**
 * Back closes the search and clears active filters before it leaves the app.
 */
private fun ScreenHost.libraryBackIntercept() =
    BackIntercept(
        isActive = {
            libraryExtras.searchOpen || libraryScreenState.query.isNotEmpty() || libraryScreenState.selectedTags.isNotEmpty()
        },
        clear = {
            libraryExtras.searchOpen = false
            libraryScreenState.query = ""
            libraryScreenState.selectedTags = emptySet()
            showLibrary()
        },
    )

/** R22: pause between keystrokes before rebuilding every library page's grid. */
private const val SEARCH_APPLY_DEBOUNCE_MS = 200L

/** Empty-library body. The tab bar stays visible; hiding it makes tab creation look ineffective. */
private fun ScreenHost.renderEmptyLibrary(
    container: LinearLayout,
    tabs: List<Tab>,
    stories: List<Story>,
    initialSelectedTabId: String?,
) {
    val sourceNames = SourceRegistry.all().joinToString(", ") { it.name }
    container.addView(
        makeLibraryTabBar(container.context, tabs, stories, initialSelectedTabId) { newTabId ->
            persistLibraryTab(newTabId)
        }.view,
    )
    container.addView(
        makeEmptyState(
            container.context,
            message = "Paste a link from $sourceNames.",
            title = "Your library is empty",
            iconRes = R.drawable.wna_menu_book,
            actionLabel = "Add a story",
            onAction = { showAddStory() },
        ),
    )
}

/** Applies the filter snapshot after the debounce pause unless a newer keystroke superseded it. */
private fun debounceLibraryFilterApply(
    scope: kotlinx.coroutines.CoroutineScope,
    generation: Int,
    currentGeneration: () -> Int,
    apply: () -> Unit,
) {
    scope.launch {
        delay(SEARCH_APPLY_DEBOUNCE_MS)
        if (generation == currentGeneration()) apply()
    }
}
