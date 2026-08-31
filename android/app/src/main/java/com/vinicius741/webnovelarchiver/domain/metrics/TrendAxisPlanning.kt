package com.vinicius741.webnovelarchiver.domain.metrics

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure planning for trend-chart axis ranges. A fixed axis (score 0–5, counts from 0) renders these
 * small week-to-week movements as flat lines hugging a chart edge; [yAxisRange] instead fits the
 * data with a padding margin, clamped to the metric's valid domain. Android-free for
 * unit-testability.
 */
object TrendAxisPlanning {
    /** Fraction of the data span added as headroom on each side of the fitted range. */
    const val RANGE_PADDING_FRACTION = 0.15

    /** Flat series half-window as a fraction of the value (a flat 410 reads as 410 ± 20.5). */
    const val FLAT_WINDOW_FRACTION = 0.05

    /** Absolute floor for the flat-series half-window, so a flat series at 0 still gets a range. */
    const val FLAT_WINDOW_MIN = 1.0

    /**
     * `[min, max]` for the Y axis fitted to [points], clamped to `[hardMin, hardMax]` — the
     * metric's valid domain (score 0–5; counts/cents never below 0). A flat series gets a
     * symmetric window; the result always has `max > min`. Null when empty — the caller keeps the
     * chart's auto-range.
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
        // Clamp can only invert when data lies outside the stated domain (e.g. a stored score
        // above 5) — show it as recorded rather than hiding it.
        return if (clampedMin < clampedMax) clampedMin to clampedMax else (dataMin - pad) to (dataMax + pad)
    }
}
