package com.vinicius741.webnovelarchiver.feature.details

import android.content.Context
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.AxisBase
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IAxisValueFormatter
import com.vinicius741.webnovelarchiver.domain.metrics.MetricPoint
import com.vinicius741.webnovelarchiver.domain.metrics.TrendAxisPlanning
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds a fully themed [LineChart] for a trend series; pure view construction, no Android
 * lifecycle. [points] should already be windowed and downsampled (see `TrendFilterPlanning`);
 * [xRange] pins the X axis to the selected time window so every card spans the same dates.
 */
internal fun buildTrendChart(
    context: Context,
    points: List<MetricPoint>,
    kind: TrendMetricKind,
    xRange: Pair<Long, Long>? = null,
): LineChart {
    val colors = ThemeManager.colors
    val chart = LineChart(context)
    val entries = points.map { (x, y) -> Entry(x.toFloat(), y.toFloat()) }
    // Point markers only while they read as discrete samples; dense series draw a plain line.
    val drawCircles = entries.size <= MAX_POINTS_WITH_CIRCLES
    val dataSet =
        if (entries.isEmpty()) {
            null
        } else {
            LineDataSet(entries, "").apply {
                // No per-point value labels; the summary line above the chart carries the current value.
                color = colors.primary
                lineWidth = 2.5f
                isDrawCirclesEnabled = drawCircles
                isDrawCircleHoleEnabled = false
                circleColors = listOf(colors.primary)
                circleRadius = 2.5f
                isDrawValuesEnabled = false
                isDrawFilledEnabled = true
                fillColor = colors.primary
                fillAlpha = 40
                mode = LineDataSet.Mode.LINEAR
                isHighlightEnabled = false
            }
        }
    chart.data = dataSet?.let { LineData(it) }
    chart.description.isEnabled = false
    chart.legend.isEnabled = false
    // Transparent so the chart sits on the card background.
    chart.isDrawGridBackgroundEnabled = false
    chart.setBackgroundColor(0)
    chart.noDataText = "Not enough data to chart yet."
    chart.noDataTextColor = colors.onSurfaceVariant
    // Static plot: with zoom off a pan has nothing to move, and touch handling would only compete
    // with the screen's vertical scroll.
    chart.isTouchEnabled = false
    chart.isDragEnabled = false
    chart.isScaleEnabled = false
    chart.isPinchZoomEnabled = false
    chart.isDoubleTapToZoomEnabled = false
    // No manual view-port offsets: only self-computed offsets reserve room for wide labels ("1,250").

    val xAxis = chart.xAxis
    xAxis.position = XAxis.XAxisPosition.BOTTOM
    xAxis.valueFormatter = DateAxisFormatter()
    xAxis.textColor = colors.onSurfaceVariant
    xAxis.gridColor = colors.outlineVariant
    xAxis.gridLineWidth = 1f
    xAxis.isDrawAxisLineEnabled = false
    xAxis.labelCount = 4
    xAxis.isForceLabelsEnabled = true
    xAxis.textSize = 10f
    // One point per calendar day; day granularity keeps a short history's labels from repeating.
    xAxis.granularity = MILLIS_PER_DAY_FLOAT
    xAxis.isAvoidFirstLastClippingEnabled = true
    // A carry-in point before the window start is clipped at the left edge (clip-to-content is on).
    xRange?.let { (start, end) ->
        xAxis.axisMinimum = start.toFloat()
        xAxis.axisMaximum = end.toFloat()
    }

    val yAxis = chart.axisLeft
    yAxis.valueFormatter = YAxisFormatter(kind)
    yAxis.textColor = colors.onSurfaceVariant
    yAxis.gridColor = colors.outlineVariant
    yAxis.gridLineWidth = 1f
    yAxis.isDrawAxisLineEnabled = false
    yAxis.textSize = 10f
    // A fitted narrow range duplicates labels at the default count; let the chart pick clean intervals.
    yAxis.labelCount = Y_AXIS_LABEL_COUNT
    yAxis.isForceLabelsEnabled = false
    if (kind == TrendMetricKind.PATREON_MEMBERS || kind == TrendMetricKind.COUNT) {
        // Whole counts: fractional grid lines would format to duplicate integers.
        yAxis.granularity = 1f
    } else if (kind == TrendMetricKind.SCORE) {
        // Labels show two decimals; a finer step (a 4.86–4.88 range) repeated "4.88" and "4.86".
        yAxis.granularity = SCORE_GRANULARITY
    }
    // Fit the Y range to the data so small movements stay visible; a fixed 0–5 axis flattened them.
    val range =
        TrendAxisPlanning.yAxisRange(
            points = points,
            hardMin = 0.0,
            hardMax = if (kind == TrendMetricKind.SCORE) SCORE_MAX else Double.POSITIVE_INFINITY,
            // A flat score (common in a 7-day window) centres in a narrow band instead of hugging the 5.0 edge.
            flatHalfWindow = if (kind == TrendMetricKind.SCORE) SCORE_FLAT_HALF_WINDOW else null,
        )
    if (range != null) {
        yAxis.axisMinimum = range.first.toFloat()
        yAxis.axisMaximum = range.second.toFloat()
    } else {
        yAxis.axisMinimum = 0f
    }
    chart.axisRight.isEnabled = false

    // No draw-in animation: MPAndroidChart rasterizes lines into a software bitmap, so animating
    // every card at once redrew each chart per frame and dropped frames on open.
    // Height is set by the Trends screen; WRAP_CONTENT would collapse a LineChart inside a ScrollView.
    return chart
}

private const val MILLIS_PER_DAY_FLOAT = 24f * 60f * 60f * 1000f

/** Half-height of the Y band around a flat score series. */
private const val SCORE_FLAT_HALF_WINDOW = 0.05

/** Smallest score step the two-decimal Y labels can tell apart. */
private const val SCORE_GRANULARITY = 0.01f

/** Above this many drawn points the per-point circles are dropped. */
private const val MAX_POINTS_WITH_CIRCLES = 45

/** Score metrics live on a 0–5 scale; used as the hard domain for the fitted Y range. */
private const val SCORE_MAX = 5.0

/** Target number of Y grid lines; the chart adjusts to clean intervals around this count. */
private const val Y_AXIS_LABEL_COUNT = 5

/** Formats epoch-millis X values as `MMM d, yy` in the system timezone. */
private class DateAxisFormatter(
    private val zone: ZoneId = ZoneId.systemDefault(),
) : IAxisValueFormatter {
    private val formatter = DateTimeFormatter.ofPattern("MMM d, yy", Locale.US)

    override fun getFormattedValue(
        value: Float,
        axis: AxisBase,
    ): String {
        val instant = Instant.ofEpochMilli(value.toLong())
        return formatter.format(instant.atZone(zone))
    }
}

/** Formats Y values according to the metric kind: score (2dp), members (int), USD (compact $). */
private class YAxisFormatter(
    private val kind: TrendMetricKind,
) : IAxisValueFormatter {
    private val intFormat = NumberFormat.getIntegerInstance(Locale.US)

    override fun getFormattedValue(
        value: Float,
        axis: AxisBase,
    ): String =
        when (kind) {
            // Two decimals; one decimal collapsed adjacent grid lines to the same label.
            TrendMetricKind.SCORE -> String.format(Locale.US, "%.2f", value)
            TrendMetricKind.PATREON_MEMBERS -> intFormat.format(value.toInt())
            // Stored as cents; show compact dollars ($1.20k, $12.5k).
            TrendMetricKind.PATREON_USD -> compactUsd(value.toLong())
            // Engagement counts (views reach the millions) share the Details chip formatting.
            TrendMetricKind.COUNT -> formatCompactCount(value.toLong())
        }

    private fun compactUsd(cents: Long): String {
        val dollars = cents / 100.0
        return when {
            dollars >= 1_000_000 -> String.format(Locale.US, "$%.1fM", dollars / 1_000_000)
            dollars >= 10_000 -> String.format(Locale.US, "$%.1fk", dollars / 1_000)
            // Below $10k use two k-decimals so grid lines ~$10 apart stay distinct ($3.40k/$3.45k).
            dollars >= 1_000 -> String.format(Locale.US, "$%.2fk", dollars / 1_000)
            else -> String.format(Locale.US, "$%.0f", dollars)
        }
    }
}

/** Compact whole-count format ("85.2K") shared by the Trends COUNT axis and Details chips. */
internal fun formatCompactCount(value: Long): String {
    val suffix =
        when {
            value >= 1_000_000_000L -> "B"
            value >= 1_000_000L -> "M"
            value >= 1_000L -> "K"
            else -> return NumberFormat.getIntegerInstance(Locale.US).format(value)
        }
    val scaled =
        when (suffix) {
            "B" -> value / 1_000_000_000.0
            "M" -> value / 1_000_000.0
            else -> value / 1_000.0
        }
    val decimals =
        when {
            scaled >= 100 -> 0
            scaled >= 10 -> 1
            else -> 2
        }
    val rounded = String.format(Locale.US, "%.${decimals}f", scaled)
    // Trim only fractional zeros: "100" (100K) must not lose its integer zeros.
    val formatted = if (decimals > 0) rounded.trimEnd('0').trimEnd('.') else rounded
    return "$formatted$suffix"
}
