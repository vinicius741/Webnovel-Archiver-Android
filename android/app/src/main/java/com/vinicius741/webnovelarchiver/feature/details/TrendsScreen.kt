package com.vinicius741.webnovelarchiver.feature.details

import android.graphics.Typeface
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.metrics.PatreonCreatorPlanning
import com.vinicius741.webnovelarchiver.domain.metrics.TrendFilterPlanning
import com.vinicius741.webnovelarchiver.domain.metrics.TrendGroup
import com.vinicius741.webnovelarchiver.domain.metrics.TrendRange
import com.vinicius741.webnovelarchiver.domain.metrics.TrendWindow
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.findScrollView
import com.vinicius741.webnovelarchiver.ui.makeCard
import com.vinicius741.webnovelarchiver.ui.makeChip
import com.vinicius741.webnovelarchiver.ui.makeDivider
import com.vinicius741.webnovelarchiver.ui.makeEmptyState
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Per-novel metric Trends sub-screen: one line chart per recorded series (score, Patreon members /
// USD, source engagement metrics) with range and metric-family filters. Series are extracted once
// per open ([buildTrendsModel]); a filter change rebuilds only the card list. Retention policy
// (same-day coalescing, downsampling, point cap) lives in MetricSnapshotPlanning, filter math in
// [TrendFilterPlanning].

/** Last range picked on any novel this process, so a preferred window carries across novels. */
private var preferredTrendRange: TrendRange = TrendRange.ALL

internal fun ScreenHost.showTrends(
    storyId: String,
    focus: String?,
) {
    val story = repository.story(storyId) ?: return showDetails(storyId)
    rerender = { showTrends(storyId, focus) }
    // Not scrollable: a scrolling placeholder would record its own zero offset over the remembered
    // one when the populated screen replaces it, so fold/unfold and Back would land at the top.
    screen(route = AppRoute.Trends(story.id, focus), title = "Trends", subtitle = story.title) {
        addView(
            makeEmptyState(
                app,
                title = "Loading trends",
                message = "Reading recorded history…",
                iconRes = R.drawable.wna_chart,
            ),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
    }
    val loadingRoot = frame.getChildAt(0)
    scope.launch {
        val history = repository.getMetricHistory(storyId)
        // Patreon figures belong to the creator, so novels sharing one chart a single merged series.
        val siblings = PatreonCreatorPlanning.siblings(story, repository.library())
        val siblingHistories = siblings.map { repository.getMetricHistory(it.id) }
        val model =
            withContext(Dispatchers.Default) {
                val patreonHistory =
                    if (siblings.isEmpty()) {
                        history
                    } else {
                        PatreonCreatorPlanning.mergedPatreonHistory(story.id, listOf(history) + siblingHistories)
                    }
                buildTrendsModel(story, history, patreonHistory)
            }
        // Render only if the loading tree is still on screen (the user may have navigated away).
        if (loadingRoot.parent === frame) renderTrends(story, model, focus)
    }
}

private fun ScreenHost.renderTrends(
    story: Story,
    model: TrendsModel,
    focus: String?,
) {
    var scrollTarget: String? = null
    screen(route = AppRoute.Trends(story.id, focus), title = "Trends", subtitle = story.title, scrollable = true) {
        addTrendsHeader(this, story, model)
        if (model.snapshotCount == 0 && model.series.isEmpty()) {
            addView(
                makeEmptyState(
                    app,
                    title = "No trend data yet",
                    message = "Sync this novel to start recording its score, engagement metrics, and Patreon figures over time.",
                    iconRes = R.drawable.wna_chart,
                ),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(Space.XL)
                },
            )
            return@screen
        }
        if (model.series.isEmpty()) {
            addView(
                makeText(
                    app,
                    "This novel has synced, but no score, Patreon, or engagement values were recorded. " +
                        "Those are only captured for sources that expose them.",
                    Type.BODY_MEDIUM,
                    ThemeManager.colors.onSurfaceVariant,
                ),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(Space.MD)
                },
            )
            return@screen
        }
        val memoryKey = navigator.current.stableKey
        val saved = routeMemory.queries[memoryKey]
        // A first open honours the focus card; Back restores the user's filters and scroll instead.
        if (saved == null) scrollTarget = focus
        TrendsBody(this@renderTrends, this, model, focus, memoryKey, saved).render()
    }
    scrollTarget?.let { scrollToFocus(it) }
}

/** Filter rows plus the card list; owns the selection and rebuilds the cards when it changes. */
private class TrendsBody(
    private val host: ScreenHost,
    private val content: LinearLayout,
    private val model: TrendsModel,
    private val focus: String?,
    private val memoryKey: String,
    savedSelection: String?,
) {
    private val ranges = TrendFilterPlanning.availableRanges(model.earliestAt, model.anchorAt)
    private val groups = TrendFilterPlanning.availableGroups(model.series.mapTo(mutableSetOf()) { it.group })
    private var range: TrendRange
    private var group: TrendGroup
    private val filters = LinearLayout(host.app).apply { orientation = LinearLayout.VERTICAL }
    private val cards = LinearLayout(host.app).apply { orientation = LinearLayout.VERTICAL }

    // Bumped on every rebuild so a pending staggered chart attach from an old card list stops.
    private var generation = 0

    init {
        val (savedRange, savedGroup) = TrendFilterPlanning.decodeSelection(savedSelection)
        range = (savedRange ?: preferredTrendRange).takeIf { it in ranges } ?: TrendRange.ALL
        group = savedGroup?.takeIf { it in groups } ?: TrendGroup.ALL
        // Record the first open's selection so a redraw (fold/unfold) counts as a return, not a new
        // open, and keeps the user's scroll instead of jumping back to the focus card.
        host.routeMemory.queries[memoryKey] = TrendFilterPlanning.encodeSelection(range, group)
    }

    fun render() {
        content.addView(filters, matchWrap())
        content.addView(cards, matchWrap())
        renderFilters()
        renderCards()
    }

    private fun select(
        newRange: TrendRange = range,
        newGroup: TrendGroup = group,
    ) {
        if (newRange == range && newGroup == group) return
        if (newRange != range) preferredTrendRange = newRange
        range = newRange
        group = newGroup
        host.routeMemory.queries[memoryKey] = TrendFilterPlanning.encodeSelection(range, group)
        renderFilters()
        renderCards()
    }

    private fun renderFilters() {
        // Keep each row's horizontal scroll across the rebuild so the tapped chip stays put.
        val previousScroll = (0 until filters.childCount).map { (filters.getChildAt(it) as? HorizontalScrollView)?.scrollX ?: 0 }
        filters.removeAllViews()
        val rows = mutableListOf<HorizontalScrollView>()
        if (ranges.size > 1) {
            rows +=
                chipRow(
                    ranges.map {
                        (
                            if (it ==
                                TrendRange.ALL
                            ) {
                                "All time"
                            } else {
                                it.label
                            }
                        ) to (it == range)
                    },
                ) { select(newRange = ranges[it]) }
        }
        if (groups.size > 1) {
            rows +=
                chipRow(
                    groups.map {
                        (
                            if (it ==
                                TrendGroup.ALL
                            ) {
                                "All metrics"
                            } else {
                                it.label
                            }
                        ) to (it == group)
                    },
                ) { select(newGroup = groups[it]) }
        }
        rows.forEachIndexed { index, row ->
            filters.addView(
                row,
                matchWrap().apply {
                    bottomMargin = host.app.dp(if (index == rows.lastIndex) Space.MD else Space.SM)
                },
            )
            previousScroll.getOrNull(index)?.takeIf { it > 0 }?.let { x -> row.post { row.scrollTo(x, 0) } }
        }
    }

    private fun chipRow(
        chips: List<Pair<String, Boolean>>,
        onSelect: (Int) -> Unit,
    ): HorizontalScrollView {
        val row = LinearLayout(host.app).apply { orientation = LinearLayout.HORIZONTAL }
        chips.forEachIndexed { index, (label, selected) ->
            row.addView(
                makeChip(host.app, label, selected) { onSelect(index) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = host.app.dp(Space.SM)
                },
            )
        }
        return HorizontalScrollView(host.app).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun renderCards() {
        val current = ++generation
        cards.removeAllViews()
        val anchor = model.anchorAt ?: return
        val pendingCharts = ArrayDeque<Pair<FrameLayout, () -> View>>()
        model.series
            .filter { group == TrendGroup.ALL || it.group == group }
            .forEach { series ->
                val window = TrendFilterPlanning.window(series.points, range, anchor)
                val slot = addCard(series, window)
                if (slot != null) {
                    val drawn = TrendFilterPlanning.downsample(window.points)
                    // A ranged window pins the X axis so every card spans the same dates.
                    val bounds = window.startAt?.let { start -> start to (window.endAt ?: anchor) }
                    pendingCharts.addLast(slot to { buildTrendChart(host.app, drawn, series.kind, bounds) })
                }
            }
        attachChartsOnePerFrame(pendingCharts, current)
    }

    /**
     * Charts go in one per animation frame: building and first-drawing every LineChart in the
     * frame that also lays out the screen is what dropped frames on open. Slots keep a fixed height,
     * so nothing shifts as charts arrive.
     */
    private fun attachChartsOnePerFrame(
        queue: ArrayDeque<Pair<FrameLayout, () -> View>>,
        current: Int,
    ) {
        if (queue.isEmpty()) return
        cards.postOnAnimation {
            // A filter change (new generation) or a replaced screen (detached list) abandons the chain.
            if (current != generation || !cards.isAttachedToWindow) return@postOnAnimation
            val (slot, build) = queue.removeFirst()
            slot.addView(build(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            attachChartsOnePerFrame(queue, current)
        }
    }

    /** Adds the card and returns its empty chart slot, or null when it shows a message instead of a chart. */
    private fun addCard(
        series: TrendSeries,
        window: TrendWindow,
    ): FrameLayout? {
        val app = host.app
        val colors = ThemeManager.colors
        val emphasize = series.focusTag == focus
        val card =
            makeCard(app).apply {
                tag = series.focusTag
                addView(
                    makeText(app, series.title, Type.TITLE_SMALL, if (emphasize) colors.primary else colors.onSurface).apply {
                        setTypeface(typeface, Typeface.BOLD)
                        letterSpacing = 0.04f
                    },
                )
                addView(
                    makeText(app, currentSummary(series), Type.BODY_MEDIUM, colors.onSurfaceVariant),
                    matchWrap().apply { topMargin = app.dp(Space.XS) },
                )
                if (window.isChartable) {
                    windowSummary(series, window, range)?.let { line ->
                        addView(makeText(app, line, Type.BODY_SMALL, colors.onSurfaceVariant), matchWrap().apply { topMargin = app.dp(2) })
                    }
                }
            }
        cards.addView(
            card,
            matchWrap().apply {
                bottomMargin = app.dp(Space.MD)
                topMargin = if (emphasize) app.dp(Space.SM) else 0
            },
        )
        if (!window.isChartable) {
            card.addView(
                makeText(app, emptyMessage(series, window), Type.BODY_SMALL, colors.onSurfaceVariant),
                matchWrap().apply { topMargin = app.dp(Space.SM) },
            )
            return null
        }
        card.addView(makeDivider(app))
        // WRAP_CONTENT gives a LineChart in a ScrollView almost no height; fixed height avoids a squished plot.
        val slot = FrameLayout(app)
        card.addView(
            slot,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.dp(TREND_CHART_HEIGHT_DP)).apply {
                topMargin = app.dp(Space.SM)
            },
        )
        return slot
    }

    private fun emptyMessage(
        series: TrendSeries,
        window: TrendWindow,
    ): String =
        when {
            window.inWindowCount > 0 || series.points.size < 2 -> "${series.title} will be charted after the next sync adds a second point."
            range == TrendRange.YEAR -> "No values recorded in the last year."
            else -> "No values recorded in the last ${range.days} days."
        }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}

private fun ScreenHost.addTrendsHeader(
    content: LinearLayout,
    story: Story,
    model: TrendsModel,
) {
    val colors = ThemeManager.colors
    content.addView(
        makeText(app, story.title, Type.TITLE_LARGE, colors.onSurface).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
    content.addView(
        makeText(app, "by ${story.author}", Type.BODY_MEDIUM, colors.onSurfaceVariant),
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(2)
        },
    )
    val count = model.snapshotCount
    val noun = if (count == 1) "snapshot" else "snapshots"
    val firstAt = model.firstSnapshotAt
    val lastAt = model.lastSnapshotAt
    val range =
        if (firstAt != null && lastAt != null) {
            "$count $noun · since ${formatTrendDate(firstAt)} · updated ${formatTrendDate(lastAt)}"
        } else {
            "$count $noun"
        }
    content.addView(
        makeText(app, range, Type.BODY_SMALL, colors.onSurfaceVariant),
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(Space.XS)
            bottomMargin = dp(Space.MD)
        },
    )
}

/** Scrolls the focus-tagged card into view once the ScrollView has measured; no-op if absent. */
private fun ScreenHost.scrollToFocus(focus: String) {
    val scrollView = findScrollView(frame) ?: return

    fun findTagged(root: View): View? {
        if (root.tag == focus) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findTagged(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }
    scrollView.post {
        val target = findTagged(scrollView) ?: return@post
        // Cards sit inside the card list, so offset by its own top within the scrolled content.
        val parentTop = (target.parent as? View)?.top ?: 0
        scrollView.smoothScrollTo(0, parentTop + target.top)
    }
}

/** Fixed chart height (dp) inside a trend card. */
private const val TREND_CHART_HEIGHT_DP = 180
