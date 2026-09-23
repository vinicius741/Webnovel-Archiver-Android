package com.vinicius741.webnovelarchiver.feature.library

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeCard
import com.vinicius741.webnovelarchiver.ui.makeEmptyState

/** Builds only the cards visible in a page; the same adapter serves single and paged libraries. */
internal class LibraryStoryAdapter(
    private val host: ScreenHost,
    private val columns: Int,
    private val tabId: String?,
    stories: List<Story>,
) : RecyclerView.Adapter<LibraryStoryAdapter.Holder>() {
    private var stories = stories
    private var visible: List<Story> = emptyList()
    private var query = ""
    private var tags: Set<String> = emptySet()
    private var sortOption = "lastUpdated"
    private var sortAscending = false

    init {
        updateVisible()
    }

    fun attachTo(list: RecyclerView) {
        val manager = GridLayoutManager(list.context, columns.coerceAtLeast(1))
        manager.spanSizeLookup =
            object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int = if (visible.isEmpty()) manager.spanCount else 1
            }
        list.layoutManager = manager
        list.adapter = this
        list.setHasFixedSize(false)
        list.overScrollMode = View.OVER_SCROLL_NEVER
        list.itemAnimator = null
    }

    fun updateFilter(
        query: String,
        tags: Set<String>,
        sortOption: String,
        sortAscending: Boolean,
    ) {
        if (this.query == query && this.tags == tags && this.sortOption == sortOption && this.sortAscending == sortAscending) return
        this.query = query
        this.tags = tags.toSet()
        this.sortOption = sortOption
        this.sortAscending = sortAscending
        updateVisible()
    }

    fun replaceStories(latest: List<Story>) {
        stories = latest
        updateVisible()
    }

    private fun updateVisible() {
        val next = LibraryQuery.filterAndSort(stories, query, tabId, tags, sortOption, sortAscending)
        // Download progress changes update the backing stories without rebinding during a gesture.
        if (next.map(Story::id) == visible.map(Story::id)) {
            visible = next
            return
        }
        visible = next
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = visible.size.coerceAtLeast(1)

    override fun getItemViewType(position: Int): Int = if (visible.isEmpty()) EMPTY else STORY

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): Holder {
        val root = FrameLayout(parent.context)
        root.layoutParams =
            RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                val gap = parent.context.dp(Space.LG) / 2
                marginStart = gap
                marginEnd = gap
                bottomMargin = parent.context.dp(Space.MD + Space.XS)
            }
        val card = if (viewType == STORY) makeCard(parent.context) else null
        card?.let {
            root.addView(
                it,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
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
            host.bindLibraryStoryCard(card, visible[position])
            return
        }
        holder.root.removeAllViews()
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
                    message = "Novels you add or move here will show up in this tab.",
                    title = "Nothing here yet",
                    iconRes = R.drawable.wna_menu_book,
                    actionLabel = "Add a story",
                    onAction = { host.showAddStory() },
                )
            }
        holder.root.addView(state)
    }

    class Holder(
        val root: FrameLayout,
        val card: LinearLayout?,
    ) : RecyclerView.ViewHolder(root)

    private companion object {
        const val STORY = 0
        const val EMPTY = 1
    }
}
