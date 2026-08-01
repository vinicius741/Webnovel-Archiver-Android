package com.vinicius741.webnovelarchiver.feature.library

import android.view.Gravity
import android.widget.FrameLayout
import androidx.core.widget.doAfterTextChanged
import androidx.viewpager2.widget.ViewPager2
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.feature.downloads.showQueue
import com.vinicius741.webnovelarchiver.feature.settings.showSettings
import com.vinicius741.webnovelarchiver.feature.updates.showUpdates
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.AppBarAction
import com.vinicius741.webnovelarchiver.ui.GridLayout
import com.vinicius741.webnovelarchiver.ui.MaxWidthFrameLayout
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.currentScreenLayout
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.layout.libraryMaxContentWidth
import com.vinicius741.webnovelarchiver.ui.makeEmptyState
import com.vinicius741.webnovelarchiver.ui.makeSearchField
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.scroll
import com.vinicius741.webnovelarchiver.ui.verticalFill
import kotlinx.coroutines.launch

internal fun ScreenHost.showLibrary() {
    activeStory = null
    // Capture the host outside the `screen(...) { }` block: inside that block `this` is the body
    // LinearLayout, so the pager adapter (which needs a ScreenHost to render grids) takes this ref.
    val host = this
    // Re-render this screen when the window changes (fold/unfold/rotation) or the Large Screen Layout
    // setting toggles, so the column count reflows 1 → 2 → 3 live.
    rerender = { showLibrary() }
    val layoutResult = currentScreenLayout()
    var stories: List<Story> = repository.library()
    var renderedProgress = stories.associate { it.id to (it.downloadedChapters to it.totalChapters) }
    var refreshLibraryContent: ((List<Story>) -> Unit)? = null
    val tabs = repository.getTabs().sortedBy { it.order }
    screen(
        route = AppRoute.Library,
        title = "Library",
        subtitle = if (stories.isEmpty()) null else "${stories.size} novel${if (stories.size == 1) "" else "s"}",
        actions =
            listOf(
                AppBarAction(R.drawable.wna_refresh, "Updates") { showUpdates() },
                AppBarAction(R.drawable.wna_download, "Downloads") { showQueue() },
                AppBarAction(R.drawable.wna_settings, "Settings") { showSettings() },
            ),
        fab = { showAddStory() },
    ) {
        // Resolve and persist tab selection even when the library is empty. Configured tabs are
        // still useful before the first import, and hiding them makes tab creation look ineffective.
        val hasUnassigned = stories.any { it.tabId == null }
        val initialSelectedTabId: String? =
            LibraryTabSelection.resolve(
                repository.getDisplayPreferences().libraryTabId,
                tabs,
                hasUnassigned,
            )

        fun persistTab(id: String?) {
            val encoded = LibraryTabSelection.encode(id)
            val display = repository.getDisplayPreferences()
            if (display.libraryTabId != encoded) {
                scope.launch { repository.saveDisplayPreferences(display.copy(libraryTabId = encoded)) }
            }
        }

        if (stories.isEmpty()) {
            addView(
                makeLibraryTabBar(context, tabs, stories, initialSelectedTabId) { newTabId ->
                    // The empty state has no filter bar, but tab selection still persists.
                    persistTab(newTabId)
                }.view,
            )
            addView(
                makeEmptyState(
                    context,
                    message = "Import a Royal Road, Scribble Hub, SpaceBattles, or FanFiction.net story to start building your library.",
                    title = "Your library is empty",
                    iconRes = R.drawable.wna_menu_book,
                    actionLabel = "Add a story",
                    onAction = { showAddStory() },
                ),
            )
            return@screen
        }

        val search = makeSearchField(context, "Search stories")

        // Restore the last-selected tab from persisted prefs (survives navigating away AND app restarts).
        // Resolve against the live tabs so a deleted tab's stale id falls back to All instead of an
        // empty, un-selectable view.
        // The single source of truth for "what is a swipeable tab", shared by the tab bar and the
        // pager so their ordering can never drift. The synthetic Unassigned tab (only when stories
        // are unassigned) comes first, then real tabs in their configured order, then All last.
        // `null` is the runtime sentinel for Unassigned.
        val pageTabs: List<String?> =
            buildList {
                if (hasUnassigned) add(null)
                addAll(tabs.map { it.id })
                add(LibraryTabSelection.ALL_TAB_ID)
            }

        // Restore the last-selected sort from persisted prefs (survives navigating away AND app
        // restarts). Mirrors the persisted tab handling above; the normalization layer maps the
        // legacy "updated" key onto the canonical "lastUpdated" and clamps unknown values.
        val persistedSort = repository.getDisplayPreferences()
        // Canonical key matches the Sort dialog option list ("lastUpdated", not the legacy "updated").
        var filterState =
            LibraryFilterState(
                selectedTabId = initialSelectedTabId,
                sortOption = persistedSort.librarySortOption.ifBlank { "lastUpdated" },
                sortAscending = persistedSort.librarySortAscending,
            )

        fun persistSort(
            option: String,
            ascending: Boolean,
        ) {
            val display = repository.getDisplayPreferences()
            if (display.librarySortOption != option || display.librarySortAscending != ascending) {
                scope.launch {
                    repository.saveDisplayPreferences(
                        display.copy(librarySortOption = option, librarySortAscending = ascending),
                    )
                }
            }
        }

        // Apply the current filter snapshot to whichever grid surface is showing: the single shared
        // [GridLayout] in single-tab mode, or every page's grid via the adapter in pager mode. Kept as
        // one closure so search/sort/tag callbacks never have to know which mode is active.
        var applyFilters: () -> Unit = {}
        // Rebuilds the tag/source chip row from the active selection. Declared before [makeLibraryFilters]
        // so the tag-toggle callback can re-highlight the tapped chip; assigned to `filters.rebuildChips`
        // right after the filter bar is built (mirrors how `applyFilters` is hoisted above).
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
                    persistSort(filterState.sortOption, filterState.sortAscending)
                    applyFilters()
                },
                { tag ->
                    val nextTags = filterState.selectedTags.toMutableSet()
                    if (!nextTags.add(tag)) nextTags.remove(tag)
                    filterState = filterState.copy(selectedTags = nextTags)
                    // Re-render the chip row so the tapped chip actually shows its selected state —
                    // previously the grid re-filtered but the chip visuals never updated.
                    refreshFilters(filterState.selectedTabId, filterState.selectedTags)
                    applyFilters()
                },
            )
        // Rebuild the chip set whenever the active tab changes so the tag/source filters follow the
        // tab (All = union, a specific tab = only that tab's labels) — matching the legacy RN app.
        // Declared before the tab bar so the bar's selection lambda can close over it.
        refreshFilters = filters.rebuildChips

        val tabBar =
            makeLibraryTabBar(context, tabs, stories, filterState.selectedTabId) { newTabId ->
                filterState = filterState.copy(selectedTabId = newTabId)
                persistTab(newTabId)
                refreshFilters(filterState.selectedTabId, filterState.selectedTags)
                applyFilters()
            }
        // Tab bar renders above the filter row, matching the original layout.
        addView(tabBar.view)
        addView(filters.view)

        // One watcher drives BOTH the single-grid and pager paths through the shared `applyFilters`
        // closure, so a keystroke re-filters whichever surface is showing. Previously this was wired
        // only inside the single-tab branch, which left the swipeable multi-tab pager unfiltered on
        // typing — `applyFilters` was reachable only via tag/sort callbacks and page swipes.
        search.doAfterTextChanged {
            filterState = filterState.copy(query = it?.toString().orEmpty())
            // The collapsible header's active-filter indicators track live search text too, and the
            // chip row recomputes so it keeps offering only tags reachable under the typed query.
            filters.syncActiveFilters(filterState.selectedTags)
            refreshFilters(filterState.selectedTabId, filterState.selectedTags)
            applyFilters()
        }

        if (pageTabs.size >= 2) {
            // Swipe-between-tabs (Gap #6 parity with the legacy RN PagerView). Each page owns its own
            // scrolling grid mirroring the single-grid shell below, so a swipe switches tabs exactly as
            // tapping the bar does. Tab bar ⇄ pager stay two-way synced: a swipe updates the bar's
            // active indicator, a bar tap animates the pager to that page.
            val adapter = LibraryPagesAdapter(host, stories, pageTabs, layoutResult)
            val pager =
                ViewPager2(context).apply {
                    this.adapter = adapter
                    // Disable the (default horizontal) page-over-scroll glow; the tab bar's indicator is
                    // the affordance that a swipe changed the active tab.
                    getChildAt(0).overScrollMode = android.view.View.OVER_SCROLL_NEVER
                }
            // Land on the persisted tab without animating on first show.
            val initialPage = pageTabs.indexOf(filterState.selectedTabId).coerceAtLeast(0)
            pager.setCurrentItem(initialPage, false)

            applyFilters = {
                adapter.updateFilter(filterState.query, filterState.selectedTags, filterState.sortOption, filterState.sortAscending)
            }
            refreshLibraryContent = { latest ->
                val changed = latest.filter { renderedProgress[it.id] != (it.downloadedChapters to it.totalChapters) }
                renderedProgress = latest.associate { it.id to (it.downloadedChapters to it.totalChapters) }
                stories = latest
                adapter.replaceStories(latest)
                changed.forEach { patchLibraryProgress(frame, it) }
            }
            // Swipe → tab. Mirrors RN's `tabId !== activeTabId` guard so the two-way wiring never
            // feeds back into itself.
            var suppressingPageCallback = false
            pager.registerOnPageChangeCallback(
                object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageSelected(position: Int) {
                        if (suppressingPageCallback) return
                        val newTabId = pageTabs.getOrNull(position) ?: return
                        if (newTabId != filterState.selectedTabId) {
                            filterState = filterState.copy(selectedTabId = newTabId)
                            persistTab(newTabId)
                            tabBar.selectVisual(newTabId)
                            // Refresh the chip set + re-filter so the newly visible page reflects the
                            // active tab's tags/sources plus the active search/tags/sort.
                            refreshFilters(filterState.selectedTabId, filterState.selectedTags)
                            applyFilters()
                        }
                    }
                },
            )
            // Bar tap → page (animate), in addition to the selection/persist/filter work the bar already
            // does above. Routed through a flag so setCurrentItem's resulting onPageSelected is a no-op.
            tabBar.onSelectFromBar = { id ->
                val idx = pageTabs.indexOf(id)
                if (idx in 0 until pager.adapter!!.itemCount && idx != pager.currentItem) {
                    suppressingPageCallback = true
                    pager.setCurrentItem(idx, true)
                    pager.post { suppressingPageCallback = false }
                }
            }
            addView(pager, verticalFill().apply { topMargin = dp(Space.LG) })
            applyFilters()
        } else {
            // Single-tab path (the common case: no custom tabs and nothing unassigned). This is the
            // original layout — one scrolling grid — untouched in behaviour.
            val list =
                GridLayout(context).apply {
                    columnCount = layoutResult.numColumns.coerceAtLeast(1)
                    horizontalSpacingDp = Space.LG
                    // Story cards carry their own bottom margin (Space.MD from the `card` helper), so the
                    // grid adds only a small gap on top — otherwise the vertical spacing (margin + grid)
                    // balloons past the horizontal gap and rows look stretched apart.
                    verticalSpacingDp = Space.XS
                }
            applyFilters = {
                renderTabGrid(
                    stories,
                    list,
                    layoutResult,
                    filterState.query,
                    filterState.selectedTabId,
                    filterState.selectedTags,
                    filterState.sortOption,
                    filterState.sortAscending,
                )
            }
            refreshLibraryContent = { latest ->
                val changed = latest.filter { renderedProgress[it.id] != (it.downloadedChapters to it.totalChapters) }
                renderedProgress = latest.associate { it.id to (it.downloadedChapters to it.totalChapters) }
                stories = latest
                changed.forEach { patchLibraryProgress(frame, it) }
            }
            val gridShell =
                MaxWidthFrameLayout(context).apply {
                    // Center the grid and cap its width at the size-class content max (760/1040/1320dp) so it
                    // never stretches edge-to-edge on tablets, matching the RN library layout.
                    maxContentWidthDp = libraryMaxContentWidth(layoutResult.numColumns)
                    addView(
                        list,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.CENTER_HORIZONTAL,
                        ),
                    )
                }
            addView(scroll(gridShell), verticalFill().apply { topMargin = dp(Space.LG) })
            applyFilters()
        }
    }
    refreshLibraryContent?.let { refresh ->
        val renderedRoot = frame.getChildAt(0)
        // Capture before launching: unlike drop(1), this still handles a publish that races between
        // the capture and collector registration because StateFlow then emits a different version.
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
}
