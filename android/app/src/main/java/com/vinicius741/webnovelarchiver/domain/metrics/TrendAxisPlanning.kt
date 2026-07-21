package com.vinicius741.webnovelarchiver.domain.metrics

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure planning for trend-chart axis ranges.
 *
 * The Trends charts originally pinned the score Y axis to 0–5 and the Patreon axes to start at 0.
 * With the small week-to-week movement these metrics actually have (a score oscillating between
 * 4.6 and 4.8, a member count creeping from 1200 to 1250) that rendered as a flat line hugging
 * the top of the chart — the graph carried no visible information. [yAxisRange] instead fits the
 * axis to the recorded data with a padding margin, clamped to the metric's valid domain, so small
 * variations stay visible. Kept Android-free so the math is unit-testable.
 */
object TrendAxisPlanning {
    /** Fraction of the data span added as headroom on each side of the fitted range. */
    const val RANGE_PADDING_FRACTION = 0.15

    /** Flat series half-window as a fraction of the value (a flat 410 reads as 410 ± 20.5). */
    const val FLAT_WINDOW_FRACTION = 0.05

    /** Absolute floor for the flat-series half-window, so a flat series at 0 still gets a range. */
    const val FLAT_WINDOW_MIN = 1.0

    /**
     * Computes `[min, max]` for the Y axis from the recorded [points], fitted to the data so small
     * movements stay visible, then clamped to `[hardMin, hardMax]` — the metric's valid domain
     * (a score can never leave 0–5; a count or a cent amount can never go below 0).
     *
     * A flat series (all points equal) gets a symmetric window around the value so the flat line
     * renders mid-chart instead of collapsing onto an axis edge. The result always has `max > min`
     * so the chart never receives a zero-height range. Returns `null` when there are no points;
     * the caller then leaves the chart's own auto-range in place.
     */
    fun yAxisRange(
        points: List<MetricPoint>,
        hardMin: Double,
        hardMax: Double,
    ): Pair<Double, Double>? {
        if (points.isEmpty()) return null
        val values = points.map { it.second }
        val dataMin = values.min()
        val dataMax = values.max()
        val span = dataMax - dataMin
        val pad =
            if (span > 0.0) {
                span * RANGE_PADDING_FRACTION
            } else {
                max(abs(dataMax) * FLAT_WINDOW_FRACTION, FLAT_WINDOW_MIN)
            }
        val clampedMin = max(hardMin, dataMin - pad)
        val clampedMax = min(hardMax, dataMax + pad)
        // Clamping can only collapse/invert the range when the data itself lies outside the stated
        // domain (e.g. a provider stored a score above 5). Show that data as recorded rather than
        // hiding it behind the clamp.
        return if (clampedMin < clampedMax) clampedMin to clampedMax else (dataMin - pad) to (dataMax + pad)
    }
}
