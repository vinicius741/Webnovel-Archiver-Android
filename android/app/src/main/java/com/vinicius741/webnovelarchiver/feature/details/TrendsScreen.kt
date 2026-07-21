package com.vinicius741.webnovelarchiver.feature.details

import android.graphics.Typeface
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.metrics.MetricSnapshotPlanning
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.StoryMetricHistory
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.findScrollView
import com.vinicius741.webnovelarchiver.ui.makeCard
import com.vinicius741.webnovelarchiver.ui.makeDivider
import com.vinicius741.webnovelarchiver.ui.makeEmptyState
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.screen
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Per-novel metric Trends sub-screen. Reached from the Details screen (tappable score row, tappable
 * Patreon card, or the overflow "Trends" entry). Renders one MPAndroidChart line chart per available
 * series (score, Patreon members, Patreon monthly USD) plus a current-value / delta / range summary
 * line above each chart. Each sync records one point; see [MetricSnapshotPlanning] for the retention
 * (same-day coalescing + 60-day-then-downsample + 1000-point cap).
 *
 * `showTrends` mirrors the `showLegacyEpubs` pattern: render a loading empty state, then a coroutine
 * reads the history off the IO dispatcher and re-renders into `renderTrends`.
 */

/** Focus token that opens the screen scrolled to the score chart. */
internal const val FOCUS_SCORE = "score"

/** Focus token that opens the screen scrolled to the Patreon members chart. */
internal const val FOCUS_PATREON_MEMBERS = "patreon_members"

/** Focus token that opens the screen scrolled to the Patreon monthly-USD chart. */
internal const val FOCUS_PATREON_USD = "patreon_usd"

/**
 * Per-card configuration for one trend chart, bundled so [ScreenHost.addChartCard] stays under the
 * parameter-count budget. [chartProvider] is invoked lazily and only when [showChart] is true, so a
 * series with too few points never constructs its chart.
 */
private data class TrendChartCard(
    val title: String,
    val focusTag: String,
    val emphasize: Boolean,
    val summary: String,
    val showChart: Boolean,
    val chartProvider: () -> View,
    val emptyMessage: String,
)

internal fun ScreenHost.showTrends(
    storyId: String,
    focus: String?,
) {
    val story = repository.getStory(storyId) ?: return showDetails(storyId)
    screen(route = AppRoute.Trends(story.id, focus), title = "Trends", subtitle = story.title, onBack = {
        showDetails(story.id)
    }, scrollable = true) {
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
        // The loading tree may have been torn down if the user navigated away during the read; only
        // render when it is still on screen (same guard showLegacyEpubs uses).
        if (loadingRoot.parent === frame) renderTrends(story, history, focus)
    }
}

private fun ScreenHost.renderTrends(
    story: Story,
    history: StoryMetricHistory,
    focus: String?,
) {
    screen(route = AppRoute.Trends(story.id, focus), title = "Trends", subtitle = story.title, onBack = {
        showDetails(story.id)
    }, scrollable = true) {
        addTrendsHeader(this, story, history)
        if (history.snapshots.isEmpty()) {
            addView(
                makeEmptyState(
                    app,
                    title = "No trend data yet",
                    message = "Sync this novel to start recording its score and Patreon figures over time.",
                    iconRes = R.drawable.wna_chart,
                ),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(Space.XL)
                },
            )
            return@screen
        }

        val scorePoints = MetricSnapshotPlanning.scoreSeries(history)
        val memberPoints = MetricSnapshotPlanning.patreonSeries(history, MetricSnapshotPlanning.PatreonField.MEMBERS)
        val usdPoints = MetricSnapshotPlanning.patreonSeries(history, MetricSnapshotPlanning.PatreonField.MONTHLY_USD)

        // Each chart card is tagged so the focus token can scroll the matching one into view after
        // the body lays out. A series needs at least two points to draw a meaningful line; with zero
        // or one point we show an explanatory card instead of a flat/degenerate chart.
        if (scorePoints.isNotEmpty()) {
            addChartCard(
                this,
                TrendChartCard(
                    title = "Score",
                    focusTag = FOCUS_SCORE,
                    emphasize = focus == FOCUS_SCORE,
                    summary = scoreSummary(scorePoints),
                    showChart = scorePoints.size >= 2,
                    chartProvider = { buildTrendChart(app, scorePoints, TrendMetricKind.SCORE) },
                    emptyMessage = "Score will be charted after the next sync adds a second point.",
                ),
            )
        }
        if (memberPoints.isNotEmpty()) {
            addChartCard(
                this,
                TrendChartCard(
                    title = "Patreon members",
                    focusTag = FOCUS_PATREON_MEMBERS,
                    emphasize = focus == FOCUS_PATREON_MEMBERS,
                    summary =
                        patreonSummary(
                            points = memberPoints,
                            suffix = " members",
                            formatValue = { value -> NumberFormat.getIntegerInstance(Locale.US).format(value.toInt()) },
                            // Members are stored and displayed as counts, so the raw numeric delta is correct.
                            formatDelta = { MetricSnapshotPlanning.formatDelta(memberPoints, asScore = false) },
                        ),
                    showChart = memberPoints.size >= 2,
                    chartProvider = { buildTrendChart(app, memberPoints, TrendMetricKind.PATREON_MEMBERS) },
                    emptyMessage = "Members will be charted after the next sync adds a second point.",
                ),
            )
        }
        if (usdPoints.isNotEmpty()) {
            addChartCard(
                this,
                TrendChartCard(
                    title = "Patreon monthly earnings",
                    focusTag = FOCUS_PATREON_USD,
                    emphasize = focus == FOCUS_PATREON_USD,
                    summary =
                        patreonSummary(
                            points = usdPoints,
                            suffix = "/mo",
                            formatValue = { value -> formatUsd(value.toLong(), signed = false) },
                            // USD points are in cents; format the delta in dollars so the units match the value.
                            formatDelta = {
                                MetricSnapshotPlanning.delta(usdPoints)?.toLong()?.let { cents -> formatUsd(cents, signed = true) }
                            },
                        ),
                    showChart = usdPoints.size >= 2,
                    chartProvider = { buildTrendChart(app, usdPoints, TrendMetricKind.PATREON_USD) },
                    emptyMessage = "Earnings will be charted after the next sync adds a second point.",
                ),
            )
        }
        if (scorePoints.isEmpty() && memberPoints.isEmpty() && usdPoints.isEmpty()) {
            addView(
                makeText(
                    app,
                    "This novel has synced, but no score or Patreon values were recorded. " +
                        "Score and Patreon are only captured for sources that expose them.",
                    Type.BODY_MEDIUM,
                    ThemeManager.colors.onSurfaceVariant,
                ),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(Space.MD)
                },
            )
        }
    }
    focus?.let { scrollToFocus(it) }
}

/** Small header: title row plus a "Recording N · since <date> · updated <date>" line. */
private fun ScreenHost.addTrendsHeader(
    content: LinearLayout,
    story: Story,
    history: StoryMetricHistory,
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
    val count = history.snapshots.size
    val firstAt = history.snapshots.firstOrNull()?.capturedAt
    val lastAt = history.snapshots.lastOrNull()?.capturedAt
    val range =
        if (firstAt != null && lastAt != null) {
            "Recording $count · since ${formatDate(firstAt)} · updated ${formatDate(lastAt)}"
        } else {
            "Recording $count snapshots"
        }
    content.addView(
        makeText(app, range, Type.BODY_SMALL, colors.onSurfaceVariant),
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(Space.XS)
            bottomMargin = dp(Space.MD)
        },
    )
}

/** A titled card holding a summary line and (when there are enough points) a chart. */
private fun ScreenHost.addChartCard(
    content: LinearLayout,
    card: TrendChartCard,
) {
    val colors = ThemeManager.colors
    val view =
        makeCard(app).apply {
            tag = card.focusTag
            layoutParams =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(Space.MD)
                    topMargin = if (card.emphasize) dp(Space.SM) else 0
                }
            addView(
                makeText(app, card.title, Type.TITLE_SMALL, if (card.emphasize) colors.primary else colors.onSurface).apply {
                    setTypeface(typeface, Typeface.BOLD)
                    letterSpacing = 0.04f
                },
            )
            addView(
                makeText(app, card.summary, Type.BODY_MEDIUM, colors.onSurfaceVariant),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(Space.XS)
                },
            )
            if (card.showChart) {
                addView(makeDivider(app))
                // Fixed height: WRAP_CONTENT measures a LineChart inside this ScrollView to a few
                // dozen px (the view has no intrinsic height), which squished the plot and stacked
                // the axis labels on top of each other.
                addView(
                    card.chartProvider(),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(TREND_CHART_HEIGHT_DP)).apply {
                        topMargin = dp(Space.SM)
                    },
                )
            } else {
                addView(
                    makeText(app, card.emptyMessage, Type.BODY_SMALL, colors.onSurfaceVariant),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(Space.SM)
                    },
                )
            }
        }
    content.addView(view)
}

/**
 * Scrolls the body so the focus-tagged card is visible. `post` runs after the ScrollView measures its
 * content, so the target card's position is known. Falls back silently if the tag isn't present.
 */
private fun ScreenHost.scrollToFocus(focus: String) {
    val scrollView = findScrollView(frame) ?: return

    // Local recursive search for the card whose tag matches the focus token, so the body can scroll
    // the matching chart into view once it has been measured.
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
        scrollView.smoothScrollTo(0, target.top)
    }
}

private fun scoreSummary(points: List<Pair<Long, Double>>): String {
    val base =
        seriesSummary(
            points = points,
            formatValue = { value -> String.format(Locale.US, "%.2f", value) },
            formatDelta = { MetricSnapshotPlanning.formatDelta(points, asScore = true) },
        )
    // Score has an extra min–max range line that the count/currency series don't.
    val summary = MetricSnapshotPlanning.summary(points)
    val range =
        if (summary.min != null && summary.max != null && summary.min != summary.max) {
            " · range ${String.format(Locale.US, "%.2f", summary.min)}–${String.format(Locale.US, "%.2f", summary.max)}"
        } else {
            ""
        }
    return base + range
}

/** Patreon summary (members or USD): "Current <value><suffix> (<signed delta> since last sync)".
 *  [formatDelta] is passed in so the delta renders in the same unit as the value (USD points are
 *  stored in cents but shown in dollars; a raw numeric delta would be a 100× mismatch). */
private fun patreonSummary(
    points: List<Pair<Long, Double>>,
    suffix: String,
    formatValue: (Double) -> String,
    formatDelta: () -> String?,
): String = seriesSummary(points = points, suffix = suffix, formatValue = formatValue, formatDelta = formatDelta)

/** Shared "Current <value> (<signed delta> since last sync)" line for any series. */
private fun seriesSummary(
    points: List<Pair<Long, Double>>,
    suffix: String = "",
    formatValue: (Double) -> String,
    formatDelta: () -> String?,
): String {
    val summary = MetricSnapshotPlanning.summary(points)
    val current = summary.last?.let(formatValue) ?: "—"
    val delta = formatDelta()?.let { " ($it since last sync)" } ?: ""
    return "Current $current$suffix$delta"
}

private fun formatUsd(
    cents: Long,
    signed: Boolean,
): String {
    val dollars = (if (signed) kotlin.math.abs(cents) else cents) / 100.0
    val prefix =
        if (signed && cents >= 0) {
            "+"
        } else if (signed) {
            "-"
        } else {
            ""
        }
    return "$prefix$${NumberFormat.getIntegerInstance(Locale.US).format(dollars.toLong())}"
}

private fun formatDate(epochMillis: Long): String =
    DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US).format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

/** Fixed chart height (dp) inside a trend card; see [ScreenHost.addChartCard]. */
private const val TREND_CHART_HEIGHT_DP = 180
