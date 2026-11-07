package com.vinicius741.webnovelarchiver.domain.metrics

import kotlin.math.abs

// Pure planning for the Trends screen filters: which time ranges are worth offering, how a series
// is cut to a range, and how a long series is thinned before charting. Android-free so it is
// unit-testable.

/** Time window the Trends charts show. [days] is null for the whole recorded history. */
enum class TrendRange(
    val label: String,
    val days: Long?,
) {
    WEEK("7D", 7),
    MONTH("30D", 30),
    QUARTER("90D", 90),
    YEAR("1Y", 365),
    ALL("All", null),
}

/** Which family of charts the Trends screen shows. */
enum class TrendGroup(
    val label: String,
) {
    ALL("All"),
    SCORE("Score"),
    PATREON("Patreon"),
    ENGAGEMENT("Engagement"),
}

/**
 * A series cut to a [TrendRange]. [points] may start with one carry-in point from before
 * [startAt] (the value known when the window opened) so the line enters from the left edge and
 * [change] covers the whole window. [startAt] is null for [TrendRange.ALL].
 */
data class TrendWindow(
    val points: List<MetricPoint>,
    val startAt: Long?,
    val endAt: Long?,
    val inWindowCount: Int,
) {
    /** Last minus first value across the window, or null with fewer than two points. */
    val change: Double? get() = if (points.size < 2) null else points.last().second - points.first().second

    /** A line needs two points and at least one value actually measured inside the window. */
    val isChartable: Boolean get() = points.size >= 2 && inWindowCount >= 1
}

object TrendFilterPlanning {
    /** Points drawn per chart at most; a chart is a few hundred pixels wide, so more only costs frames. */
    const val MAX_CHART_POINTS = 120

    /**
     * Ranges shorter than the recorded span, then [TrendRange.ALL]. A range at least as long as the
     * history would show the same chart as All, so it is not offered. Returns only All when the
     * history is empty or a single day.
     */
    fun availableRanges(
        firstAt: Long?,
        lastAt: Long?,
    ): List<TrendRange> {
        if (firstAt == null || lastAt == null || lastAt <= firstAt) return listOf(TrendRange.ALL)
        val span = lastAt - firstAt
        return TrendRange.entries.filter { range -> range.days == null || range.days * MILLIS_PER_DAY < span }
    }

    /**
     * Cuts [points] (oldest-first) to [range], ending at [anchorAt] — the newest snapshot of the
     * whole history, not the wall clock, so a novel that has not synced lately still shows its last
     * recorded weeks.
     */
    fun window(
        points: List<MetricPoint>,
        range: TrendRange,
        anchorAt: Long,
    ): TrendWindow {
        val days = range.days ?: return TrendWindow(points, startAt = null, endAt = null, inWindowCount = points.size)
        val startAt = anchorAt - days * MILLIS_PER_DAY
        val firstInside = points.indexOfFirst { it.first >= startAt }
        if (firstInside < 0) {
            // Nothing measured in the window; keep the last known value so the card can still say so.
            return TrendWindow(points.takeLast(1), startAt, anchorAt, inWindowCount = 0)
        }
        // Carry in the previous value only when nothing was measured exactly at the window start.
        val from = if (firstInside > 0 && points[firstInside].first > startAt) firstInside - 1 else firstInside
        return TrendWindow(points.subList(from, points.size).toList(), startAt, anchorAt, inWindowCount = points.size - firstInside)
    }

    /**
     * Largest-Triangle-Three-Buckets downsampling: keeps the first and last points and, per bucket,
     * the point that best preserves the line's visual shape. Series at or under [maxPoints] are
     * returned unchanged.
     */
    fun downsample(
        points: List<MetricPoint>,
        maxPoints: Int = MAX_CHART_POINTS,
    ): List<MetricPoint> {
        if (maxPoints < 3 || points.size <= maxPoints) return points
        val result = ArrayList<MetricPoint>(maxPoints)
        result.add(points.first())
        val bucketSize = (points.size - 2).toDouble() / (maxPoints - 2)
        var previous = 0
        for (bucket in 0 until maxPoints - 2) {
            val start = (bucket * bucketSize).toInt() + 1
            val end = minOf(((bucket + 1) * bucketSize).toInt() + 1, points.size - 1)
            // Average of the next bucket is the third triangle vertex.
            val nextStart = end
            val nextEnd = minOf(((bucket + 2) * bucketSize).toInt() + 1, points.size)
            var avgX = 0.0
            var avgY = 0.0
            for (i in nextStart until nextEnd) {
                avgX += points[i].first.toDouble()
                avgY += points[i].second
            }
            val nextCount = (nextEnd - nextStart).coerceAtLeast(1)
            avgX /= nextCount
            avgY /= nextCount
            val (ax, ay) = points[previous]
            var best = start
            var bestArea = -1.0
            for (i in start until end) {
                val area =
                    abs(
                        (ax - avgX) * (points[i].second - ay) -
                            (ax - points[i].first.toDouble()) * (avgY - ay),
                    )
                if (area > bestArea) {
                    bestArea = area
                    best = i
                }
            }
            result.add(points[best])
            previous = best
        }
        result.add(points.last())
        return result
    }

    /** Groups that have at least one series, led by [TrendGroup.ALL]; only All when fewer than two groups have data. */
    fun availableGroups(present: Set<TrendGroup>): List<TrendGroup> {
        val groups = TrendGroup.entries.filter { it != TrendGroup.ALL && it in present }
        return if (groups.size < 2) listOf(TrendGroup.ALL) else listOf(TrendGroup.ALL) + groups
    }

    /** Persisted "RANGE|GROUP" token for route memory; unknown parts fall back to the defaults. */
    fun encodeSelection(
        range: TrendRange,
        group: TrendGroup,
    ): String = "${range.name}|${group.name}"

    fun decodeSelection(token: String?): Pair<TrendRange?, TrendGroup?> {
        val parts = token?.split('|').orEmpty()
        val range = TrendRange.entries.firstOrNull { it.name == parts.getOrNull(0) }
        val group = TrendGroup.entries.firstOrNull { it.name == parts.getOrNull(1) }
        return range to group
    }

    private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
}
