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

// Per-novel metric Trends sub-screen: one line chart per recorded series (score, Patreon members /
// USD, source engagement metrics) with a current/delta/range summary. Retention policy (same-day
// coalescing, downsampling, point cap) lives in [MetricSnapshotPlanning].

internal const val FOCUS_SCORE = "score"

internal const val FOCUS_PATREON_MEMBERS = "patreon_members"

internal const val FOCUS_PATREON_USD = "patreon_usd"

/** Card config for [addChartCard]; [chartProvider] is lazy and only invoked when [showChart] is true. */
internal data class TrendChartCard(
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
    val story = repository.story(storyId) ?: return showDetails(storyId)
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
        // Render only if the loading tree is still on screen (the user may have navigated away).
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
                    message = "Sync this novel to start recording its score, engagement metrics, and Patreon figures over time.",
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

        // Cards are tagged for focus scrolling; under two points an explanatory card replaces a degenerate chart.
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
        val metricCardsAdded = addSourceMetricChartCards(this, story, history, focus)
        if (scorePoints.isEmpty() && memberPoints.isEmpty() && usdPoints.isEmpty() && metricCardsAdded == 0) {
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
        }
    }
    focus?.let { scrollToFocus(it) }
}

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

internal fun ScreenHost.addChartCard(
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
                // WRAP_CONTENT gives a LineChart in a ScrollView almost no height; fixed height avoids a squished plot.
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

/** [formatDelta] is injected so the delta matches the value's unit (USD is stored in cents, shown in dollars). */
private fun patreonSummary(
    points: List<Pair<Long, Double>>,
    suffix: String,
    formatValue: (Double) -> String,
    formatDelta: () -> String?,
): String = seriesSummary(points = points, suffix = suffix, formatValue = formatValue, formatDelta = formatDelta)

internal fun seriesSummary(
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

/** Fixed chart height (dp) inside a trend card. */
private const val TREND_CHART_HEIGHT_DP = 180
