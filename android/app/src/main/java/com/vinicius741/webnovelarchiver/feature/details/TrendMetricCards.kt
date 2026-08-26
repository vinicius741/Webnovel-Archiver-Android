package com.vinicius741.webnovelarchiver.feature.details

import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.domain.metrics.MetricPoint
import com.vinicius741.webnovelarchiver.domain.metrics.MetricSnapshotPlanning
import com.vinicius741.webnovelarchiver.domain.model.SourceMetricKind
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.StoryMetricHistory
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.source.SourceRegistry

// Per-source engagement chart cards for the Trends screen. Sources without a rating (SpaceBattles,
// FanFiction.net) still report engagement counts (watchers, favorites, follows…) on every sync;
// those are snapshotted into the metric history and charted here, one card per metric the source's
// descriptor features. Kept apart from TrendsScreen.kt so that file stays inside its size budget.

/** Focus token that opens the Trends screen scrolled to one metric's chart card. */
internal fun metricFocusTag(kind: SourceMetricKind): String = "metric_${kind.name}"

/**
 * Adds one chart card per featured metric that has at least one recorded point, in the source's
 * featured order. Returns how many cards were added so the caller can decide whether the
 * "nothing recorded" note still applies. Mirrors the score/Patreon cards: a one-point series gets
 * the explanatory message instead of a degenerate chart.
 */
internal fun ScreenHost.addSourceMetricChartCards(
    content: LinearLayout,
    story: Story,
    history: StoryMetricHistory,
    focus: String?,
): Int {
    val featured =
        SourceRegistry
            .getProvider(story.sourceId, story.sourceUrl)
            ?.descriptor
            ?.featuredMetrics
            .orEmpty()
    var added = 0
    featured.forEach { kind ->
        val points = MetricSnapshotPlanning.metricSeries(history, kind)
        if (points.isEmpty()) return@forEach
        addChartCard(
            content,
            TrendChartCard(
                title = kind.label,
                focusTag = metricFocusTag(kind),
                emphasize = focus == metricFocusTag(kind),
                summary = countSummary(points),
                showChart = points.size >= 2,
                chartProvider = { buildTrendChart(app, points, TrendMetricKind.COUNT) },
                emptyMessage = "${kind.label} will be charted after the next sync adds a second point.",
            ),
        )
        added++
    }
    return added
}

// "Current 85.2K (+120 since last sync)" — compact value so views-scale counts stay readable, raw
// numeric delta (counts are stored and displayed in the same unit).
private fun countSummary(points: List<MetricPoint>): String =
    seriesSummary(
        points = points,
        formatValue = { value -> formatCompactCount(value.toLong()) },
        formatDelta = { MetricSnapshotPlanning.formatDelta(points, asScore = false) },
    )
