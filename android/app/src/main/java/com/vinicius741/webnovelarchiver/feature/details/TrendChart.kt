package com.vinicius741.webnovelarchiver.feature.details

import android.content.Context
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.vinicius741.webnovelarchiver.domain.metrics.MetricPoint
import com.vinicius741.webnovelarchiver.domain.metrics.TrendAxisPlanning
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Builds a fully themed [LineChart] for a trend series; pure view construction, no Android lifecycle. */
internal fun buildTrendChart(
    context: Context,
    points: List<MetricPoint>,
    kind: TrendMetricKind,
): LineChart {
    val colors = ThemeManager.colors
    val chart = LineChart(context)
    val entries = points.map { (x, y) -> Entry(x.toFloat(), y.toFloat()) }
    val dataSet =
        if (entries.isEmpty()) {
            null
        } else {
            LineDataSet(entries, "").apply {
                // No per-point value labels; the summary line above the chart carries the current value.
                color = colors.primary
                lineWidth = 2.5f
                setDrawCircles(true)
                setCircleColor(colors.primary)
                circleRadius = 3f
                setDrawValues(false)
                setDrawFilled(true)
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
    chart.setDrawGridBackground(false)
    chart.setBackgroundColor(0)
    chart.setNoDataText("Not enough data to chart yet.")
    chart.setNoDataTextColor(colors.onSurfaceVariant)
    // Horizontal pan only; zoom gestures must not swallow the screen's vertical scroll.
    chart.setTouchEnabled(true)
    chart.isDragEnabled = true
    chart.setScaleEnabled(false)
    chart.setPinchZoom(false)
    chart.isDoubleTapToZoomEnabled = false
    // No manual view-port offsets: only self-computed offsets reserve room for wide labels ("1,250").

    val xAxis = chart.xAxis
    xAxis.position = XAxis.XAxisPosition.BOTTOM
    xAxis.valueFormatter = DateAxisFormatter()
    xAxis.textColor = colors.onSurfaceVariant
    xAxis.gridColor = colors.outlineVariant
    xAxis.gridLineWidth = 1f
    xAxis.setDrawAxisLine(false)
    xAxis.setLabelCount(4, true)
    xAxis.textSize = 10f
    // One point per calendar day; day granularity keeps a short history's labels from repeating.
    xAxis.granularity = MILLIS_PER_DAY_FLOAT
    xAxis.setAvoidFirstLastClipping(true)

    val yAxis = chart.axisLeft
    yAxis.valueFormatter = YAxisFormatter(kind)
    yAxis.textColor = colors.onSurfaceVariant
    yAxis.gridColor = colors.outlineVariant
    yAxis.gridLineWidth = 1f
    yAxis.setDrawAxisLine(false)
    yAxis.textSize = 10f
    // A fitted narrow range duplicates labels at the default count; let the chart pick clean intervals.
    yAxis.setLabelCount(Y_AXIS_LABEL_COUNT, false)
    if (kind == TrendMetricKind.PATREON_MEMBERS || kind == TrendMetricKind.COUNT) {
        // Whole counts: fractional grid lines would format to duplicate integers.
        yAxis.granularity = 1f
    }
    // Fit the Y range to the data so small movements stay visible; a fixed 0–5 axis flattened them.
    val range =
        TrendAxisPlanning.yAxisRange(
            points = points,
            hardMin = 0.0,
            hardMax = if (kind == TrendMetricKind.SCORE) SCORE_MAX else Double.POSITIVE_INFINITY,
        )
    if (range != null) {
        yAxis.axisMinimum = range.first.toFloat()
        yAxis.axisMaximum = range.second.toFloat()
    } else {
        yAxis.axisMinimum = 0f
    }
    chart.axisRight.isEnabled = false

    // Animate the draw-in; short so repeated opens don't feel slow.
    chart.animateX(400)

    // Height is set by the Trends screen; WRAP_CONTENT would collapse a LineChart inside a ScrollView.
    chart.invalidate()
    return chart
}

private const val MILLIS_PER_DAY_FLOAT = 24f * 60f * 60f * 1000f

/** Score metrics live on a 0–5 scale; used as the hard domain for the fitted Y range. */
private const val SCORE_MAX = 5.0

/** Target number of Y grid lines; the chart adjusts to clean intervals around this count. */
private const val Y_AXIS_LABEL_COUNT = 5

/** Formats epoch-millis X values as `MMM d, yy` in the system timezone. */
private class DateAxisFormatter(
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ValueFormatter() {
    private val formatter = DateTimeFormatter.ofPattern("MMM d, yy", Locale.US)

    override fun getFormattedValue(value: Float): String {
        val instant = Instant.ofEpochMilli(value.toLong())
        return formatter.format(instant.atZone(zone))
    }
}

/** Formats Y values according to the metric kind: score (2dp), members (int), USD (compact $). */
private class YAxisFormatter(
    private val kind: TrendMetricKind,
) : ValueFormatter() {
    private val intFormat = NumberFormat.getIntegerInstance(Locale.US)

    override fun getFormattedValue(value: Float): String =
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
    val formatted = String.format(Locale.US, "%.${decimals}f", scaled).trimEnd('0').trimEnd('.')
    return "$formatted$suffix"
}
