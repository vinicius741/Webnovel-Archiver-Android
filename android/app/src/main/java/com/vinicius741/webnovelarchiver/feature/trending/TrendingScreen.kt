package com.vinicius741.webnovelarchiver.feature.trending

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.metrics.TrendingPlanning.Entry
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.APP_BAR_SUBTITLE_TAG
import com.vinicius741.webnovelarchiver.ui.RAIL_MIN_WIDTH_DP
import com.vinicius741.webnovelarchiver.ui.SCREEN_SIDE_PADDING_DP
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.bindWhileAttached
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.dropUnusedBaselineAlignment
import com.vinicius741.webnovelarchiver.ui.makeCountedChip
import com.vinicius741.webnovelarchiver.ui.makeEmptyState
import com.vinicius741.webnovelarchiver.ui.makeSmallSpinner
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.rememberListPosition
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.verticalFill

/**
 * Trending: the library's novels ranked by how fast their statistics are rising now, minus the
 * ones the reader is caught up on. Reached from Updates' app bar; built from
 * [TrendingScreenModel]'s state.
 */
internal fun showTrending(host: ScreenHost) {
    host.activeStory = null
    host.rerender = { showTrending(host) }
    // screen() navigates to this route before it prunes the store, so the model is kept.
    val model = host.screenModels.get(AppRoute.Trending.stableKey) { TrendingScreenModel(HostTrendingData(host)) }
    TrendingRenderer(host, model, model.state.value).render()
}

private fun subtitleFor(state: TrendingUiState): String =
    when {
        state.loading -> "Reading your stats…"
        state.result.entries.isEmpty() -> "Nothing rising right now"
        state.result.entries.size == 1 -> "1 novel rising"
        else -> "${state.result.entries.size} novels rising"
    }

/**
 * One render of Trending, bound to [model] while its views are on screen. A new state patches the
 * subtitle and chips and submits the rows to the list (DiffUtil rebinds only changed rows); the
 * loading and empty states swap a slot over the list.
 */
private class TrendingRenderer(
    private val host: ScreenHost,
    private val model: TrendingScreenModel,
    private var applied: TrendingUiState,
) {
    private val app = host.app
    private val wide = wideTrendingLayout(host.app)
    private val adapter = TrendingAdapter(host)
    private lateinit var chipScroll: HorizontalScrollView
    private lateinit var emptySlot: FrameLayout
    private lateinit var list: RecyclerView

    fun render() {
        host.screen(
            route = AppRoute.Trending,
            title = "Trending",
            subtitle = subtitleFor(applied),
            fullWidthBody = true,
        ) {
            chipScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
            addView(chipScroll)
            emptySlot = FrameLayout(context)
            addView(emptySlot, verticalFill())
            val side = host.dp(SCREEN_SIDE_PADDING_DP)
            list =
                RecyclerView(context).apply {
                    layoutManager =
                        GridLayoutManager(context, if (wide) 2 else 1).apply {
                            spanSizeLookup =
                                object : GridLayoutManager.SpanSizeLookup() {
                                    override fun getSpanSize(position: Int): Int =
                                        if (this@TrendingRenderer.adapter.currentList.getOrNull(
                                                position,
                                            ) is TrendingItem.Row
                                        ) {
                                            1
                                        } else {
                                            spanCount
                                        }
                                }
                        }
                    this.adapter = this@TrendingRenderer.adapter
                    itemAnimator = null
                    clipToPadding = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                    setPadding(side - host.dp(ITEM_HALF_GAP_DP), host.dp(Spacing.XS), side - host.dp(ITEM_HALF_GAP_DP), host.dp(Spacing.XL))
                }
            addView(list, verticalFill())
            show(applied)
            host.rememberListPosition(list)
        }
        list.bindWhileAttached(host.scope, model.state) { apply(it) }
    }

    private fun apply(next: TrendingUiState) {
        if (next == applied) return
        val filterChanged = next.filter != applied.filter
        applied = next
        setSubtitle(subtitleFor(next))
        show(next)
        if (filterChanged) list.scrollToPosition(0)
    }

    /** Patches the app bar's subtitle in place. */
    private fun setSubtitle(text: String) {
        val subtitle = host.frame.findViewWithTag<TextView>(APP_BAR_SUBTITLE_TAG) ?: return
        if (subtitle.text != text) subtitle.text = text
    }

    private fun show(state: TrendingUiState) {
        renderChips(state)
        val visible = state.visible
        val message = emptyMessage(state)
        if (state.loading || message != null) {
            list.visibility = View.GONE
            emptySlot.visibility = View.VISIBLE
            emptySlot.removeAllViews()
            emptySlot.addView(
                if (state.loading) loadingView() else message!!,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
            )
            adapter.submitList(emptyList())
        } else {
            emptySlot.visibility = View.GONE
            list.visibility = View.VISIBLE
            adapter.submitList(items(state, visible))
        }
        chipScroll.dropUnusedBaselineAlignment()
    }

    private fun items(
        state: TrendingUiState,
        visible: List<Entry>,
    ): List<TrendingItem> =
        buildList {
            visible.forEachIndexed { index, entry ->
                // The hero leads the All list only; a filter ranks by one signal, so every novel is a row.
                if (index == 0 && state.filter == TrendingFilter.ALL) {
                    add(TrendingItem.Hero(entry))
                } else {
                    add(TrendingItem.Row(entry, index + 1))
                }
            }
            add(TrendingItem.Footer(state.result.caughtUpHidden, state.result.measured))
        }

    private fun emptyMessage(state: TrendingUiState): View? {
        if (state.loading) return null
        if (state.result.entries.isEmpty()) {
            return makeEmptyState(
                app,
                title = "Nothing is trending yet",
                message =
                    "Trending compares your syncs over time. Sync your novels over a few days and the ones whose " +
                        "stats are rising show up here.",
                iconRes = R.drawable.wna_trending,
            )
        }
        if (state.visible.isNotEmpty()) return null
        val name =
            state.filter.signal
                ?.let(TrendingCopy::signalName)
                .orEmpty()
        return makeEmptyState(
            app,
            title = "Nothing rising on $name",
            message = "No novel's ${name.lowercase()} numbers are climbing right now. Try another filter.",
            iconRes = R.drawable.wna_trending,
        )
    }

    private fun loadingView(): View =
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(makeSmallSpinner(app, LOADING_SPINNER_DP))
            addView(
                makeText(app, "Comparing your novels' stats…", Type.BODY_MEDIUM, ThemeManager.colors.onSurfaceVariant).apply {
                    setPadding(0, host.dp(Spacing.MD), 0, 0)
                },
            )
        }

    private fun renderChips(state: TrendingUiState) {
        val scrollX = chipScroll.scrollX
        chipScroll.removeAllViews()
        val row =
            LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(host.dp(SCREEN_SIDE_PADDING_DP), host.dp(Spacing.XS), host.dp(SCREEN_SIDE_PADDING_DP), host.dp(Spacing.SM))
            }
        TrendingFilter.entries.forEach { option ->
            val count = if (state.loading) null else state.count(option)
            row.addView(
                makeCountedChip(app, option.label, count, option == state.filter) { model.selectFilter(option) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = host.dp(Spacing.SM)
                },
            )
        }
        chipScroll.addView(row)
        chipScroll.post { chipScroll.scrollTo(scrollX, 0) }
    }
}

/** One list item of Trending. */
internal sealed interface TrendingItem {
    val key: String

    data class Hero(
        val entry: Entry,
    ) : TrendingItem {
        override val key: String get() = "hero:${entry.story.id}"
    }

    data class Row(
        val entry: Entry,
        val rank: Int,
    ) : TrendingItem {
        override val key: String get() = "row:${entry.story.id}"
    }

    data class Footer(
        val caughtUpHidden: Int,
        val measured: Int,
    ) : TrendingItem {
        override val key: String get() = "footer"
    }
}

/** Rows rebuild their small view tree on bind; the list holds at most a few dozen novels. */
private class TrendingAdapter(
    private val host: ScreenHost,
) : ListAdapter<TrendingItem, TrendingAdapter.Holder>(Diff) {
    private val views = TrendingViews(host)

    class Holder(
        val frame: FrameLayout,
    ) : RecyclerView.ViewHolder(frame)

    override fun getItemViewType(position: Int): Int =
        when (getItem(position)) {
            is TrendingItem.Hero -> TYPE_HERO
            is TrendingItem.Row -> TYPE_ROW
            is TrendingItem.Footer -> TYPE_FOOTER
        }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): Holder =
        Holder(
            FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                val half = host.dp(ITEM_HALF_GAP_DP)
                setPadding(half, half, half, half)
            },
        )

    override fun onBindViewHolder(
        holder: Holder,
        position: Int,
    ) {
        val view =
            when (val item = getItem(position)) {
                is TrendingItem.Hero -> views.hero(item.entry)
                is TrendingItem.Row -> views.row(item.entry, item.rank)
                is TrendingItem.Footer -> views.footer(item.caughtUpHidden, item.measured)
            }
        holder.frame.removeAllViews()
        holder.frame.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        holder.frame.dropUnusedBaselineAlignment()
    }

    private object Diff : DiffUtil.ItemCallback<TrendingItem>() {
        override fun areItemsTheSame(
            oldItem: TrendingItem,
            newItem: TrendingItem,
        ): Boolean = oldItem.key == newItem.key

        override fun areContentsTheSame(
            oldItem: TrendingItem,
            newItem: TrendingItem,
        ): Boolean = oldItem == newItem
    }

    private companion object {
        const val TYPE_HERO = 0
        const val TYPE_ROW = 1
        const val TYPE_FOOTER = 2
    }
}

private fun wideTrendingLayout(context: Context): Boolean {
    val config = context.resources.configuration
    val rail = if (config.screenWidthDp >= RAIL_MIN_WIDTH_DP) RAIL_WIDTH_DP else 0
    return config.screenWidthDp - rail >= TWO_COLUMN_MIN_DP
}

private const val ITEM_HALF_GAP_DP = 4
private const val LOADING_SPINNER_DP = 28
private const val RAIL_WIDTH_DP = 84
private const val TWO_COLUMN_MIN_DP = 600
