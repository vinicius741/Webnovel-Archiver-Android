package com.vinicius741.webnovelarchiver.feature.library

import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.GridLayout
import com.vinicius741.webnovelarchiver.ui.MaxWidthFrameLayout
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.grid
import com.vinicius741.webnovelarchiver.ui.layout.ScreenLayoutResult
import com.vinicius741.webnovelarchiver.ui.layout.libraryMaxContentWidth
import com.vinicius741.webnovelarchiver.ui.scroll
import com.vinicius741.webnovelarchiver.ui.size
import com.vinicius741.webnovelarchiver.ui.text

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

    private var filterSnapshot: FilterSnapshot = FilterSnapshot("", emptySet(), "updated", false)

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
        val grid =
            GridLayout(context).apply {
                columnCount = layout.numColumns.coerceAtLeast(1)
                horizontalSpacingDp = Space.LG
                // See the single-grid path: cards already have a bottom margin, so keep the grid's
                // vertical gap small to avoid stretched vertical spacing.
                verticalSpacingDp = Space.XS
            }
        // Same shell as the single-grid path: cap width at the size-class content max and center it,
        // inside a scroller so a long list scrolls vertically within its page.
        val shell =
            MaxWidthFrameLayout(context).apply {
                maxContentWidthDp = libraryMaxContentWidth(layout.numColumns)
                addView(
                    grid,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER_HORIZONTAL,
                    ),
                )
            }
        val scroll =
            ScrollView(context).apply {
                isFillViewport = true
                addView(shell)
            }
        // ViewPager2 requires every page's root view to fill the whole pager (match_parent on both
        // axes); otherwise it throws "Pages must fill the whole ViewPager2 (use match_parent)".
        scroll.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        return PageViewHolder(scroll, grid)
    }

    override fun onBindViewHolder(
        holder: PageViewHolder,
        position: Int,
    ) {
        val tabId = pageTabs[position]
        val snap = filterSnapshot
        host.renderTabGrid(stories, holder.grid, layout, snap.text, tabId, snap.tags, snap.sortOption, snap.sortAscending)
    }

    /** Apply a new search/tag/sort snapshot to every page. Re-renders bound pages in place without
     *  disturbing the current page position. */
    fun updateFilter(
        text: String,
        tags: Set<String>,
        sortOption: String,
        sortAscending: Boolean,
    ) {
        filterSnapshot = FilterSnapshot(text, tags, sortOption, sortAscending)
        notifyItemRangeChanged(0, itemCount)
    }

    /** Updates the backing snapshot for pages bound later; already-bound progress views are patched
     *  directly by the screen so RecyclerView never rebinds during a vertical gesture. */
    fun replaceStories(latest: List<Story>) {
        stories = latest
    }

    class PageViewHolder(
        view: android.view.View,
        val grid: GridLayout,
    ) : RecyclerView.ViewHolder(view)
}
