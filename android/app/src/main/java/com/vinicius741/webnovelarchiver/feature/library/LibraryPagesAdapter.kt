package com.vinicius741.webnovelarchiver.feature.library

import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.MaxWidthFrameLayout
import com.vinicius741.webnovelarchiver.ui.layout.ScreenLayoutResult
import com.vinicius741.webnovelarchiver.ui.layout.libraryMaxContentWidth

/**
 * Backs the Library's swipe-between-tabs [ViewPager2] (Gap #6 parity with the legacy RN `PagerView`).
 * One page per entry in [pageTabs]; each page owns its own scrolling grid identical to the single-grid
 * shell, so a swipe simply reveals a different tab's pre-filtered story list.
 *
 * The active search/tag/sort snapshot is held in [filterSnapshot] and re-applied via [updateFilter],
 * which re-renders every visible page. Page *count* is fixed by the tab set, so filter changes never
 * move the user off their current page — they only change what each page shows.
 */
internal class LibraryPagesAdapter(
    private val host: ScreenHost,
    private var stories: List<Story>,
    private val pageTabs: List<String?>,
    private val layout: ScreenLayoutResult,
) : RecyclerView.Adapter<LibraryPagesAdapter.PageViewHolder>() {
    private data class FilterSnapshot(
        val text: String,
        val tags: Set<String>,
        val sortOption: String,
        val sortAscending: Boolean,
    )

    private var filterSnapshot: FilterSnapshot = FilterSnapshot("", emptySet(), "lastUpdated", false)
    private val bound = mutableSetOf<PageViewHolder>()

    init {
        // ViewPager2 plays nicely with stable item ids when set before the adapter is attached.
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = pageTabs[position]?.hashCode()?.toLong() ?: -1L

    override fun getItemCount(): Int = pageTabs.size

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): PageViewHolder {
        val context = parent.context
        val list = RecyclerView(context)
        // Same shell as the single-grid path: cap width at the size-class content max and center it,
        // inside a scroller so a long list scrolls vertically within its page.
        val shell =
            MaxWidthFrameLayout(context).apply {
                maxContentWidthDp = libraryMaxContentWidth(layout.numColumns)
                addView(
                    list,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER_HORIZONTAL,
                    ),
                )
            }
        // ViewPager2 requires every page's root view to fill the whole pager (match_parent on both
        // axes); otherwise it throws "Pages must fill the whole ViewPager2 (use match_parent)".
        shell.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        return PageViewHolder(shell, list)
    }

    override fun onBindViewHolder(
        holder: PageViewHolder,
        position: Int,
    ) {
        val tabId = pageTabs[position]
        val snap = filterSnapshot
        holder.storyAdapter =
            LibraryStoryAdapter(host, layout.numColumns, tabId, stories).also { adapter ->
                adapter.updateFilter(snap.text, snap.tags, snap.sortOption, snap.sortAscending)
                adapter.attachTo(holder.list)
            }
        bound.add(holder)
        // Per-tab scroll memory: capture continuously from the bound page, then restore the saved
        // offset once it lays out. A recycled far-away page rebound later also regains its position.
        val scrollKey = LibraryTabSelection.memoryKey(tabId)
        holder.list.trackScrollInto(host.libraryScreenState.tabScrollPositions, scrollKey)
        holder.list.restoreScrollOnce(host.libraryScreenState.tabScrollPositions[scrollKey] ?: 0)
    }

    override fun onViewRecycled(holder: PageViewHolder) {
        bound.remove(holder)
        holder.list.clearOnScrollListeners()
        holder.list.adapter = null
        holder.storyAdapter = null
    }

    /** Apply a new search/tag/sort snapshot to every page. Re-renders bound pages in place without
     *  disturbing the current page position. Equivalent filter states are skipped (R22): a
     *  no-change update must not rebuild any page's grid. */
    fun updateFilter(
        text: String,
        tags: Set<String>,
        sortOption: String,
        sortAscending: Boolean,
    ) {
        val next = FilterSnapshot(text, tags, sortOption, sortAscending)
        if (next == filterSnapshot) return
        filterSnapshot = next
        bound.forEach { it.storyAdapter?.updateFilter(text, tags, sortOption, sortAscending) }
    }

    /** Updates the backing snapshot for pages bound later; already-bound progress views are patched
     *  directly by the screen so RecyclerView never rebinds during a vertical gesture. */
    fun replaceStories(latest: List<Story>) {
        stories = latest
        bound.forEach { it.storyAdapter?.replaceStories(latest) }
    }

    class PageViewHolder(
        root: MaxWidthFrameLayout,
        val list: RecyclerView,
    ) : RecyclerView.ViewHolder(root) {
        var storyAdapter: LibraryStoryAdapter? = null
    }
}
