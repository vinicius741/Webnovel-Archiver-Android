package com.vinicius741.webnovelarchiver.feature.library

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.doOnDetach
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.feature.details.showDetails
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.BackIntercept
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.ui.BottomSheetHandle
import com.vinicius741.webnovelarchiver.ui.MaxWidthFrameLayout
import com.vinicius741.webnovelarchiver.ui.ScreenChrome
import com.vinicius741.webnovelarchiver.ui.bindWhileAttached
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.layout.resolveScreenLayout
import com.vinicius741.webnovelarchiver.ui.makeEmptyState
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.screenMetrics
import com.vinicius741.webnovelarchiver.ui.toast
import com.vinicius741.webnovelarchiver.ui.verticalFill
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Library's model. The Library is the root of every history, so the model lives as long as the
 * activity; after a process death it starts from the view memory saved with the activity.
 */
internal fun ScreenHost.libraryModel(): LibraryScreenModel =
    screenModels.get(AppRoute.Library.stableKey) {
        LibraryScreenModel(HostLibraryData(this), screenModels.takeRestored(AppRoute.Library.stableKey))
    }

/**
 * The Library: a large app bar (search opens in the bar), the tab bar with counts, the active
 * sort/filter row, and one swipeable page of list cards or covers per tab. Long-press starts
 * selection, which swaps the navigation bar for a Move / Download / Delete bar. Built from
 * [LibraryScreenModel]'s state; see [LibraryRenderer] for how it follows it.
 */
internal fun ScreenHost.showLibrary() {
    activeStory = null
    // Re-render on window/setting changes so the column count reflows live.
    rerender = { showLibrary() }
    // The title, close action and header depend on the state, so the model is looked up before
    // screen() builds them. The Library is always in the history, so the store keeps it.
    val model = libraryModel()
    LibraryRenderer(this, model, model.onRender()).render()
}

/**
 * One render of the Library, bound to [model] while its views are on screen. A new
 * [LibraryBuildKey] (selection or search starting or ending, the layout, the tab set) rebuilds the
 * screen. Everything else is patched in place: filters re-filter the bound pages, a selection
 * change rebinds only the cards it touched, a library change patches download progress without
 * rebinding during a gesture, and the Continue listening card binds its slot.
 */
private class LibraryRenderer(
    private val host: ScreenHost,
    private val model: LibraryScreenModel,
    /** The state this render shows. */
    private var applied: LibraryUiState,
) {
    private val app = host.app
    private val key = applied.buildKey
    private val grid =
        host.screenMetrics().let { metrics ->
            LibraryViewModePlanning.gridSpec(applied.viewMode, resolveScreenLayout(metrics).numColumns, metrics.widthDp)
        }
    private val cards =
        LibraryCardEvents(
            selectionOf = { applied.selectionOf(it) },
            onOpen = { host.showDetails(it) },
            onStartSelection = model::startSelection,
            onToggle = model::toggleSelection,
            onAddStory = { host.showAddStory() },
        )
    private val tabScroll =
        object : LibraryTabScroll {
            override fun remember(
                tabKey: String,
                offset: Int,
            ) = model.rememberScroll(tabKey, offset)

            override fun offset(tabKey: String): Int = model.scrollOffset(tabKey)
        }
    private val continueCard =
        LibraryContinueCard(host, onPlay = model::playContinueListening, onDismiss = model::dismissContinueListening)
    private var renderedProgress = progressOf(applied.stories)

    // Views of this render, set while it is built.
    private lateinit var root: View
    private var setFilterDot: (Boolean) -> Unit = {}
    private var activeRow: LibraryActiveFilterRow? = null
    private var tabBar: LibraryTabBar? = null
    private var pager: ViewPager2? = null
    private var pages: LibraryPagesAdapter? = null
    private var single: LibraryStoryAdapter? = null
    private var sheet: BottomSheetHandle? = null
    private var suppressingPageCallback = false
    private var queryApply: Job? = null

    fun render() {
        val state = applied
        host.screen(
            route = AppRoute.Library,
            title = LibrarySelectionPlanning.screenTitle(state.selectedStoryIds.size),
            chrome = ScreenChrome.IMMERSIVE,
            closeAction = if (state.selecting) model::exitSelection else null,
            backIntercept = BackIntercept(isActive = { model.state.value.backClears }, clear = model::clearSearchAndFilters),
        ) {
            addHeader(this, state)
            if (!state.selecting && !state.searching) {
                addView(continueCard.view)
                continueCard.bind(state.continueListening)
            }
            if (state.stories.isEmpty()) {
                renderEmpty(this, state)
                return@screen
            }
            val bar = host.makeLibraryTabBar(context, state.tabs, state.stories, state.filter.selectedTabId) { tabId -> selectTab(tabId) }
            tabBar = bar
            addView(bar.view)
            val row =
                host.makeLibraryActiveFilterRow(
                    onOpenSheet = { openSheet() },
                    onRemoveTag = model::removeTag,
                    onClear = model::clearTags,
                )
            activeRow = row
            addView(row.view)
            row.render(state.filter)
            if (state.pageTabs.size >= 2) addPager(this, state) else addSingleGrid(this, state)
            if (state.selecting) {
                addView(
                    host.makeLibrarySelectionBar(
                        onMove = { host.showMoveStoriesDialog(applied.selectedStoryIds.size, applied.tabs, model::moveSelected) },
                        onDownload = { host.toast(model.downloadSelected()) },
                        onDelete = {
                            model.deletePrompt()?.let { prompt ->
                                host.confirm(prompt, confirmLabel = "Delete") { model.deleteSelected() }
                            }
                        },
                    ),
                )
            }
        }
        // The selection bar replaces the navigation until selection ends (the next render resets it).
        if (state.selecting) host.shell.setNavigationSuppressed(true)
        root = host.frame.getChildAt(0)
        // The open sheet acts on this render's lists; a new render (fold, search, selection) closes it.
        root.doOnDetach { sheet?.dismiss() }
        root.bindWhileAttached(host.scope, model.state) { apply(it) }
        root.bindWhileAttached(host.scope, model.messages) { host.toast(it) }
    }

    /** The app bar for the current mode (selecting, searching, or browsing). */
    private fun addHeader(
        container: LinearLayout,
        state: LibraryUiState,
    ) {
        val filtersActive = state.filter.selectedTags.isNotEmpty()
        when {
            state.selecting ->
                container.addView(
                    host.makeLibrarySelectionTopBar(
                        state.selectedStoryIds.size,
                        onClose = model::exitSelection,
                    ) { model.toggleSelectAll() },
                )
            state.searching -> {
                val bar = host.makeLibrarySearchBar(state.filter.query, filtersActive, onClose = model::closeSearch) { openSheet() }
                container.addView(bar.view)
                bar.field.doAfterTextChanged { model.setQuery(it?.toString().orEmpty()) }
                if (state.searchOpen && state.filter.query.isEmpty()) host.focusSearch(bar.field)
                setFilterDot = bar.setFilterDot
            }
            else -> {
                val appBar =
                    host.makeLibraryAppBar(
                        state.stories.size,
                        state.viewMode,
                        filtersActive,
                        onSearch = model::openSearch,
                        onSwitchViewMode = model::switchViewMode,
                    ) { openSheet() }
                container.addView(appBar.view)
                setFilterDot = { on -> appBar.setDot(SORT_FILTER_LABEL, on) }
            }
        }
    }

    /** Each tab is a page with its own scrolling grid; the bar and the pager stay two-way synced. */
    private fun addPager(
        container: LinearLayout,
        state: LibraryUiState,
    ) {
        val adapter = LibraryPagesAdapter(host, state.stories, state.pageTabs, grid, cards, tabScroll)
        val pager =
            ViewPager2(container.context).apply {
                this.adapter = adapter
                // No over-scroll glow; the tab bar's indicator signals the swipe.
                getChildAt(0).overScrollMode = View.OVER_SCROLL_NEVER
            }
        pager.setCurrentItem(state.pageTabs.indexOf(state.filter.selectedTabId).coerceAtLeast(0), false)
        pager.registerOnPageChangeCallback(
            object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    if (suppressingPageCallback) return
                    val tabId = state.pageTabs.getOrNull(position) ?: return
                    if (tabId != applied.filter.selectedTabId) {
                        tabBar?.selectVisual(tabId)
                        model.selectTab(tabId)
                    }
                }
            },
        )
        pages = adapter
        this.pager = pager
        container.addView(pager, verticalFill())
        applyFilters()
    }

    /** One tab (All only): a single grid, without the pager. */
    private fun addSingleGrid(
        container: LinearLayout,
        state: LibraryUiState,
    ) {
        val list = RecyclerView(container.context)
        val adapter = LibraryStoryAdapter(host, grid, state.filter.selectedTabId, state.stories, cards)
        adapter.attachTo(list)
        single = adapter
        val gridShell =
            MaxWidthFrameLayout(container.context).apply {
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
        val scrollKey = LibraryTabSelection.memoryKey(state.filter.selectedTabId)
        list.trackScroll { offset -> model.rememberScroll(scrollKey, offset) }
        container.addView(gridShell, verticalFill())
        applyFilters()
        list.restoreScrollOnce(model.scrollOffset(scrollKey))
    }

    /** Empty-library body. The tab bar stays visible; hiding it makes tab creation look ineffective. */
    private fun renderEmpty(
        container: LinearLayout,
        state: LibraryUiState,
    ) {
        val sourceNames = SourceRegistry.all().joinToString(", ") { it.name }
        container.addView(
            host.makeLibraryTabBar(container.context, state.tabs, state.stories, state.filter.selectedTabId, model::selectTab).view,
        )
        container.addView(
            makeEmptyState(
                container.context,
                message = "Paste a link from $sourceNames.",
                title = "Your library is empty",
                iconRes = R.drawable.wna_menu_book,
                actionLabel = "Add a story",
                onAction = { host.showAddStory() },
            ),
        )
    }

    /** A tab tapped in the bar: select it and animate the pager to its page. */
    private fun selectTab(tabId: String?) {
        model.selectTab(tabId)
        showPage(tabId)
    }

    /** Moves the pager to [tabId]'s page; the flag makes the resulting onPageSelected a no-op. */
    private fun showPage(tabId: String?) {
        val pager = pager ?: return
        val index = applied.pageTabs.indexOf(tabId)
        if (index !in 0 until (pager.adapter?.itemCount ?: 0) || index == pager.currentItem) return
        suppressingPageCallback = true
        pager.setCurrentItem(index, true)
        pager.post { suppressingPageCallback = false }
    }

    private fun openSheet() {
        sheet = host.showLibrarySortFilterSheet(applied.stories, applied.tabs, { model.state.value.filter }, model::setFilter)
    }

    private fun apply(next: LibraryUiState) {
        val previous = applied
        if (next == previous) return
        applied = next
        if (next.buildKey != key) {
            if (host.navigator.current == AppRoute.Library) host.showLibrary()
            return
        }
        patchFilter(previous.filter, next.filter)
        if (next.backClears != previous.backClears) host.refreshBackCallback()
        if (next.selectedStoryIds != previous.selectedStoryIds) patchSelection(previous.selectedStoryIds, next.selectedStoryIds)
        if (next.library !== previous.library) patchLibrary(next.stories)
        continueCard.bind(next.continueListening)
    }

    private fun patchFilter(
        previous: LibraryFilterState,
        next: LibraryFilterState,
    ) {
        if (next.query != previous.query) applyQueryAfterPause()
        val tagsOrSort =
            next.selectedTags != previous.selectedTags ||
                next.sortOption != previous.sortOption ||
                next.sortAscending != previous.sortAscending
        if (tagsOrSort) {
            applyFilters()
            activeRow?.render(next)
            setFilterDot(next.selectedTags.isNotEmpty())
        }
        if (next.selectedTabId != previous.selectedTabId) {
            tabBar?.selectVisual(next.selectedTabId)
            showPage(next.selectedTabId)
        }
    }

    /** R22: the search re-filters every page's grid in the pause between keystrokes. */
    private fun applyQueryAfterPause() {
        queryApply?.cancel()
        queryApply =
            host.scope.launch {
                delay(SEARCH_APPLY_DEBOUNCE_MS)
                applyFilters()
            }
    }

    /** Applies the shown search, tags and sort to whichever grid surface (pager pages or the single grid) is showing. */
    private fun applyFilters() {
        val filter = applied.filter
        pages?.updateFilter(filter.query, filter.selectedTags, filter.sortOption, filter.sortAscending)
        single?.updateFilter(filter.query, filter.selectedTags, filter.sortOption, filter.sortAscending)
    }

    /** Retitles the bar and rebinds the toggled card (on every page it shows), or every card for a bulk change. */
    private fun patchSelection(
        previous: Set<String>,
        next: Set<String>,
    ) {
        retitleLibrarySelection(root, next.size)
        val changed = (previous - next) + (next - previous)
        val storyId = changed.singleOrNull()
        pages?.refreshCards(storyId)
        single?.refreshCards(storyId)
    }

    /** Hands the new library to the grids and patches only the progress of stories whose counts moved. */
    private fun patchLibrary(latest: List<Story>) {
        val changed = latest.filter { renderedProgress[it.id] != (it.downloadedChapters to it.totalChapters) }
        renderedProgress = progressOf(latest)
        pages?.replaceStories(latest)
        single?.replaceStories(latest)
        changed.forEach { patchLibraryProgress(root, it) }
    }

    private fun progressOf(stories: List<Story>) = stories.associate { it.id to (it.downloadedChapters to it.totalChapters) }
}

/** R22: pause between keystrokes before rebuilding every library page's grid. */
private const val SEARCH_APPLY_DEBOUNCE_MS = 200L
