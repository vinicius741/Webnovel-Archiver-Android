package com.vinicius741.webnovelarchiver.feature.library

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListUpdateCallback
import androidx.recyclerview.widget.RecyclerView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.SCREEN_SIDE_PADDING_DP
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeEmptyState
import com.vinicius741.webnovelarchiver.ui.makeText

/**
 * Builds only the cards visible in a page; the same adapter serves single and paged libraries. Each holder
 * builds its card once and rebinds values into it. List changes notify only the cards that moved, arrived,
 * left or now show something else ([LibraryCardDiff]); download progress alone never rebinds a card.
 */
internal class LibraryStoryAdapter(
    private val host: ScreenHost,
    private val grid: LibraryGridSpec,
    private val tabId: String?,
    stories: List<Story>,
    private val events: LibraryCardEvents,
) : RecyclerView.Adapter<LibraryStoryAdapter.Holder>() {
    private var stories = stories
    private var visible: List<Story> = emptyList()
    private var query = ""
    private var tags: Set<String> = emptySet()
    private var sortOption = "lastUpdated"
    private var sortAscending = false
    private var list: RecyclerView? = null

    init {
        updateVisible()
    }

    fun attachTo(list: RecyclerView) {
        val manager = GridLayoutManager(list.context, grid.columns.coerceAtLeast(1))
        manager.spanSizeLookup =
            object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int = if (getItemViewType(position) == STORY) 1 else manager.spanCount
            }
        list.layoutManager = manager
        list.adapter = this
        list.setHasFixedSize(false)
        list.overScrollMode = View.OVER_SCROLL_NEVER
        list.itemAnimator = null
        // Each card keeps half the column gap on its sides; this tops the outer ones up to the screen's padding.
        val edge = list.context.dp(SCREEN_SIDE_PADDING_DP) - cardSideGap(list.context)
        list.setPadding(edge, 0, edge, 0)
        list.clipToPadding = false
    }

    fun updateFilter(
        query: String,
        tags: Set<String>,
        sortOption: String,
        sortAscending: Boolean,
    ) {
        if (this.query == query && this.tags == tags && this.sortOption == sortOption && this.sortAscending == sortAscending) return
        val queryChanged = this.query != query
        this.query = query
        this.tags = tags.toSet()
        this.sortOption = sortOption
        this.sortAscending = sortAscending
        updateVisible()
        // The count line names the query, so it changes even when the same novels match; the empty state's copy
        // depends on the search and tags.
        if (queryChanged || visible.isEmpty()) notifyItemChanged(0)
    }

    /** Rebinds one story's card after its selection changed, or every card for [storyId] == null (Select All). */
    fun refreshCards(storyId: String?) {
        if (storyId == null) {
            if (visible.isNotEmpty()) notifyItemRangeChanged(HEADER_ROWS, visible.size)
            return
        }
        val index = visible.indexOfFirst { it.id == storyId }
        if (index >= 0) notifyItemChanged(index + HEADER_ROWS)
    }

    fun replaceStories(latest: List<Story>) {
        stories = latest
        updateVisible()
    }

    private fun updateVisible() {
        val previous = visible
        val next = LibraryQuery.filterAndSort(stories, query, tabId, tags, sortOption, sortAscending)
        visible = next
        when {
            previous.isEmpty() && next.isEmpty() -> Unit
            // The empty state swaps with the count row and the cards.
            previous.isEmpty() || next.isEmpty() -> notifyDataSetChanged()
            else -> {
                // Download progress alone changes no card's content, so it rebinds nothing during a gesture.
                val diff = LibraryCardDiff.calculate(previous, next, System.currentTimeMillis())
                keepingFirstRowIndex { CardUpdates().also(diff::dispatchUpdatesTo).structural }
                if (previous.size != next.size) notifyItemChanged(0)
            }
        }
    }

    /**
     * Keeps the first visible row index and its offset across a move, arrival or departure, as the full
     * rebind this replaced did, so a story jumping to the top on a sync does not drag the list with it.
     */
    private fun keepingFirstRowIndex(dispatch: () -> Boolean) {
        val list = list
        val manager = list?.layoutManager as? LinearLayoutManager
        val first = manager?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
        val firstView = manager?.findViewByPosition(first)
        val structural = dispatch()
        if (!structural || firstView == null) return
        val margin = (firstView.layoutParams as RecyclerView.LayoutParams).topMargin
        manager.scrollToPositionWithOffset(first, manager.getDecoratedTop(firstView) - margin - list.paddingTop)
    }

    /** Forwards a card diff past the count row, noting whether any card moved, arrived or left. */
    private inner class CardUpdates : ListUpdateCallback {
        var structural = false

        override fun onInserted(
            position: Int,
            count: Int,
        ) {
            structural = true
            notifyItemRangeInserted(position + HEADER_ROWS, count)
        }

        override fun onRemoved(
            position: Int,
            count: Int,
        ) {
            structural = true
            notifyItemRangeRemoved(position + HEADER_ROWS, count)
        }

        override fun onMoved(
            fromPosition: Int,
            toPosition: Int,
        ) {
            structural = true
            notifyItemMoved(fromPosition + HEADER_ROWS, toPosition + HEADER_ROWS)
        }

        override fun onChanged(
            position: Int,
            count: Int,
            payload: Any?,
        ) {
            notifyItemRangeChanged(position + HEADER_ROWS, count, payload)
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        list = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        if (list === recyclerView) list = null
    }

    override fun getItemCount(): Int = if (visible.isEmpty()) 1 else visible.size + HEADER_ROWS

    override fun getItemViewType(position: Int): Int =
        when {
            visible.isEmpty() -> EMPTY
            position < HEADER_ROWS -> COUNT
            else -> STORY
        }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): Holder {
        val root = FrameLayout(parent.context)
        root.layoutParams =
            RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                val gap = cardSideGap(parent.context)
                marginStart = gap
                marginEnd = gap
                bottomMargin = parent.context.dp(if (grid.viewMode == LibraryViewMode.COVERS) Space.LG + Space.XS else Space.MD)
            }
        val card =
            when {
                viewType != STORY -> null
                grid.viewMode == LibraryViewMode.COVERS -> LibraryCoverTile(host)
                else -> LibraryStoryCard(host)
            }
        card?.let {
            root.addView(
                it.root,
                // GridLayoutManager remeasures each item to the tallest height in its row.
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
        }
        return Holder(root, card)
    }

    override fun onBindViewHolder(
        holder: Holder,
        position: Int,
    ) {
        val card = holder.card
        if (card != null) {
            card.bind(visible[position - HEADER_ROWS], events)
            return
        }
        holder.root.removeAllViews()
        if (holder.itemViewType == COUNT) {
            holder.root.addView(
                makeText(
                    holder.root.context,
                    LibraryFiltersPlanning.countLine(visible.size, query),
                    Type.BODY_SMALL,
                    ThemeManager.colors.faint,
                ).apply {
                    setPadding(context.dp(Space.XS), context.dp(Space.XS), 0, context.dp(Space.SM))
                },
            )
            return
        }
        val state =
            if (query.isNotBlank() || tags.isNotEmpty()) {
                makeEmptyState(
                    holder.root.context,
                    message = "Try clearing your search or filters.",
                    title = "No matches",
                    iconRes = R.drawable.wna_search,
                )
            } else {
                makeEmptyState(
                    holder.root.context,
                    message = "Long-press novels in another tab to move them here.",
                    title = "Nothing here yet",
                    iconRes = R.drawable.wna_menu_book,
                    actionLabel = "Add a story",
                    onAction = events.onAddStory,
                )
            }
        holder.root.addView(state)
    }

    class Holder(
        val root: FrameLayout,
        val card: LibraryCardView?,
    ) : RecyclerView.ViewHolder(root)

    private companion object {
        const val STORY = 0
        const val EMPTY = 1
        const val COUNT = 2

        /** The "N novels" line above the cards. */
        const val HEADER_ROWS = 1
    }

    private fun cardSideGap(context: Context): Int = context.dp(Space.LG) / 2
}
