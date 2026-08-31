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
import com.vinicius741.webnovelarchiver.source.SourceRegistry
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
    // Capture the ScreenHost here; inside screen{} `this` is the body LinearLayout.
    val host = this
    // Re-render on window/setting changes so the column count reflows live.
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
        // Tabs stay visible when the library is empty; hiding them makes tab creation look ineffective.
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
            val sourceNames = SourceRegistry.all().joinToString(", ") { it.name }
            addView(
                makeLibraryTabBar(context, tabs, stories, initialSelectedTabId) { newTabId ->
                    persistTab(newTabId)
                }.view,
            )
            addView(
                makeEmptyState(
                    context,
                    message = "Import a story from $sourceNames to start building your library.",
                    title = "Your library is empty",
                    iconRes = R.drawable.wna_menu_book,
                    actionLabel = "Add a story",
                    onAction = { showAddStory() },
                ),
            )
            return@screen
        }

        val search = makeSearchField(context, "Search stories")

        // Shared bar+pager tab ordering: Unassigned (null sentinel) first when present, then tabs, then All.
        val pageTabs: List<String?> =
            buildList {
                if (hasUnassigned) add(null)
                addAll(tabs.map { it.id })
                add(LibraryTabSelection.ALL_TAB_ID)
            }

        val persistedSort = repository.getDisplayPreferences()
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

        // One closure applies filters to whichever grid surface (shared grid or pager adapter) is showing.
        var applyFilters: () -> Unit = {}
        // Hoisted so the tag-toggle callback can re-highlight chips; assigned filters.rebuildChips below.
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
                    refreshFilters(filterState.selectedTabId, filterState.selectedTags)
                    applyFilters()
                },
            )
        // Chips follow the active tab: All = union, a specific tab = only its labels.
        refreshFilters = filters.rebuildChips

        val tabBar =
            makeLibraryTabBar(context, tabs, stories, filterState.selectedTabId) { newTabId ->
                filterState = filterState.copy(selectedTabId = newTabId)
                persistTab(newTabId)
                refreshFilters(filterState.selectedTabId, filterState.selectedTags)
                applyFilters()
            }
        addView(tabBar.view)
        addView(filters.view)

        search.doAfterTextChanged {
            filterState = filterState.copy(query = it?.toString().orEmpty())
            // Indicators and chip options track the live query too.
            filters.syncActiveFilters(filterState.selectedTags)
            refreshFilters(filterState.selectedTabId, filterState.selectedTags)
            applyFilters()
        }

        if (pageTabs.size >= 2) {
            // Each page owns a scrolling grid; bar and pager stay two-way synced.
            val adapter = LibraryPagesAdapter(host, stories, pageTabs, layoutResult)
            val pager =
                ViewPager2(context).apply {
                    this.adapter = adapter
                    // No over-scroll glow; the tab bar's indicator signals the swipe.
                    getChildAt(0).overScrollMode = android.view.View.OVER_SCROLL_NEVER
                }
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
            // The changed-id check stops bar-initiated switches from feeding back.
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
                            refreshFilters(filterState.selectedTabId, filterState.selectedTags)
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
            addView(pager, verticalFill().apply { topMargin = dp(Space.LG) })
            applyFilters()
        } else {
            val list =
                GridLayout(context).apply {
                    columnCount = layoutResult.numColumns.coerceAtLeast(1)
                    horizontalSpacingDp = Space.LG
                    // Cards carry their own bottom margin; a larger grid gap would stretch rows apart.
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
}
