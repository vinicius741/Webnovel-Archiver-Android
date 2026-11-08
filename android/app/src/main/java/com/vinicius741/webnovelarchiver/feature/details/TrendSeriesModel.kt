package com.vinicius741.webnovelarchiver.feature.details

import com.vinicius741.webnovelarchiver.domain.metrics.MetricPoint
import com.vinicius741.webnovelarchiver.domain.metrics.MetricSnapshotPlanning
import com.vinicius741.webnovelarchiver.domain.metrics.TrendGroup
import com.vinicius741.webnovelarchiver.domain.metrics.TrendRange
import com.vinicius741.webnovelarchiver.domain.metrics.TrendWindow
import com.vinicius741.webnovelarchiver.domain.model.SourceMetricKind
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.StoryMetricHistory
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

// Data behind the Trends screen, extracted once per open (off the main thread) so filter changes
// only re-cut ready-made series instead of re-parsing the snapshot history.

internal const val FOCUS_SCORE = "score"

internal const val FOCUS_PATREON_MEMBERS = "patreon_members"

internal const val FOCUS_PATREON_USD = "patreon_usd"

/** Focus token that opens the Trends screen scrolled to one metric's chart card. */
internal fun metricFocusTag(kind: SourceMetricKind): String = "metric_${kind.name}"

/** One chartable metric, full history, oldest-first. [suffix] follows the current value ("/mo"). */
internal data class TrendSeries(
    val title: String,
    val focusTag: String,
    val group: TrendGroup,
    val kind: TrendMetricKind,
    val points: List<MetricPoint>,
    val suffix: String = "",
)

internal data class TrendsModel(
    val snapshotCount: Int,
    val firstSnapshotAt: Long?,
    val lastSnapshotAt: Long?,
    val series: List<TrendSeries>,
) {
    /** Newest recorded point across every series; range windows end here. */
    val anchorAt: Long? = series.mapNotNull { it.points.lastOrNull()?.first }.maxOrNull()

    /** Oldest recorded point across every series; with [anchorAt] it bounds the offered ranges. */
    val earliestAt: Long? = series.mapNotNull { it.points.firstOrNull()?.first }.minOrNull()
}

/**
 * Extracts every series the Trends screen can chart: score, Patreon (from the creator-merged
 * [patreonHistory]), then the source's featured engagement metrics in its featured order. Series
 * with no recorded value are left out.
 */
internal fun buildTrendsModel(
    story: Story,
    history: StoryMetricHistory,
    patreonHistory: StoryMetricHistory,
): TrendsModel {
    val featured =
        SourceRegistry
            .getProvider(story.sourceId, story.sourceUrl)
            ?.descriptor
            ?.featuredMetrics
            .orEmpty()
    val series =
        buildList {
            add(TrendSeries("Score", FOCUS_SCORE, TrendGroup.SCORE, TrendMetricKind.SCORE, MetricSnapshotPlanning.scoreSeries(history)))
            add(
                TrendSeries(
                    "Patreon members",
                    FOCUS_PATREON_MEMBERS,
                    TrendGroup.PATREON,
                    TrendMetricKind.PATREON_MEMBERS,
                    MetricSnapshotPlanning.patreonSeries(patreonHistory, MetricSnapshotPlanning.PatreonField.MEMBERS),
                    suffix = " members",
                ),
            )
            add(
                TrendSeries(
                    "Patreon monthly earnings",
                    FOCUS_PATREON_USD,
                    TrendGroup.PATREON,
                    TrendMetricKind.PATREON_USD,
                    MetricSnapshotPlanning.patreonSeries(patreonHistory, MetricSnapshotPlanning.PatreonField.MONTHLY_USD),
                    suffix = "/mo",
                ),
            )
            featured.forEach { kind ->
                add(
                    TrendSeries(
                        kind.label,
                        metricFocusTag(kind),
                        TrendGroup.ENGAGEMENT,
                        TrendMetricKind.COUNT,
                        MetricSnapshotPlanning.metricSeries(history, kind),
                    ),
                )
            }
        }.filter { it.points.isNotEmpty() }
    val allSnapshots = history.snapshots + patreonHistory.snapshots
    return TrendsModel(
        snapshotCount = history.snapshots.size,
        firstSnapshotAt = allSnapshots.minOfOrNull { it.capturedAt },
        lastSnapshotAt = allSnapshots.maxOfOrNull { it.capturedAt },
        series = series,
    )
}

/** "Current 4.47K (+155 since last sync)" — the last-sync delta always uses the full series. */
internal fun currentSummary(series: TrendSeries): String {
    val last = series.points.lastOrNull()?.second ?: return "Current —"
    val delta = MetricSnapshotPlanning.delta(series.points)?.let { " (${formatTrendDelta(series.kind, it)} since last sync)" } ?: ""
    return "Current ${formatTrendValue(series.kind, last)}${series.suffix}$delta"
}

/** "+230 in the last 30 days · range 4.24K–4.47K"; null when the window has nothing to compare. */
internal fun windowSummary(
    series: TrendSeries,
    window: TrendWindow,
    range: TrendRange,
): String? {
    val change = window.change ?: return null
    val since =
        when (range) {
            TrendRange.ALL -> "since ${formatTrendDate(window.points.first().first)}"
            TrendRange.YEAR -> "in the last year"
            else -> "in the last ${range.days} days"
        }
    // Spread covers values measured inside the window, not the carry-in from before it.
    val values = window.points.takeLast(window.inWindowCount).map { it.second }
    if (values.isEmpty()) return "${formatTrendDelta(series.kind, change)} $since"
    val low = values.min()
    val high = values.max()
    val spread =
        if (high - low >
            SPREAD_EPSILON
        ) {
            " · range ${formatTrendValue(series.kind, low)}–${formatTrendValue(series.kind, high)}"
        } else {
            ""
        }
    return "${formatTrendDelta(series.kind, change)} $since$spread"
}

internal fun formatTrendValue(
    kind: TrendMetricKind,
    value: Double,
): String =
    when (kind) {
        TrendMetricKind.SCORE -> String.format(Locale.US, "%.2f", value)
        TrendMetricKind.PATREON_MEMBERS -> NumberFormat.getIntegerInstance(Locale.US).format(value.toLong())
        // Stored in cents, shown in whole dollars rounded like the Details and Library cards.
        TrendMetricKind.PATREON_USD -> "$" + NumberFormat.getIntegerInstance(Locale.US).format((value / 100.0).roundToLong())
        TrendMetricKind.COUNT -> formatCompactCount(value.toLong())
    }

/** Signed delta in the value's display unit (USD cents shown as dollars, counts compacted). */
internal fun formatTrendDelta(
    kind: TrendMetricKind,
    delta: Double,
): String {
    if (kind == TrendMetricKind.SCORE) return String.format(Locale.US, "%+.2f", delta)
    val sign = if (delta < 0) "-" else "+"
    val magnitude = abs(delta)
    return sign + formatTrendValue(kind, magnitude)
}

internal fun formatTrendDate(epochMillis: Long): String =
    DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US).format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

private const val SPREAD_EPSILON = 1e-9
