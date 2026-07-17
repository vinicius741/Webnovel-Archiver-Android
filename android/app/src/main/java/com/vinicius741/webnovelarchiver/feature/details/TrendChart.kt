package com.vinicius741.webnovelarchiver.feature.details

import android.content.Context
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.vinicius741.webnovelarchiver.domain.metrics.MetricPoint
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.dp
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds a fully themed [LineChart] for a trend series. Pure view construction — no data fetching,
 * no Android lifecycle — so the Trends screen can call it once per series after loading history.
 *
 * The chart intentionally disables vertical zoom and pin zoom (which fight the screen's vertical
 * scroll) and enables only horizontal drag so a long history can be panned. The X axis is epoch-millis
 * formatted as a date; the Y axis format and range come from [kind].
 */
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
                // A single subtle line; no value labels (the summary line above the chart carries the
                // exact current value, so per-point labels would just clutter the graph).
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
    // Transparent so the chart sits on the card background without a contrasting panel.
    chart.setDrawGridBackground(false)
    chart.setBackgroundColor(0)
    chart.setNoDataText("Not enough data to chart yet.")
    chart.setNoDataTextColor(colors.onSurfaceVariant)
    // Touch: horizontal pan only. Disable pinch-zoom and the double-tap zoom so two-finger and
    // double-tap gestures don't get swallowed by the chart instead of scrolling the screen.
    chart.setTouchEnabled(true)
    chart.isDragEnabled = true
    chart.setScaleEnabled(false)
    chart.setPinchZoom(false)
    chart.isDoubleTapToZoomEnabled = false
    chart.setViewPortOffsets(56f, 16f, 24f, 48f)

    val xAxis = chart.xAxis
    xAxis.position = XAxis.XAxisPosition.BOTTOM
    xAxis.valueFormatter = DateAxisFormatter()
    xAxis.textColor = colors.onSurfaceVariant
    xAxis.gridColor = colors.outlineVariant
    xAxis.gridLineWidth = 1f
    xAxis.setDrawAxisLine(false)
    xAxis.setLabelCount(4, true)
    xAxis.textSize = 10f

    val yAxis = chart.axisLeft
    yAxis.valueFormatter = YAxisFormatter(kind)
    yAxis.textColor = colors.onSurfaceVariant
    yAxis.gridColor = colors.outlineVariant
    yAxis.gridLineWidth = 1f
    yAxis.setDrawAxisLine(false)
    yAxis.textSize = 10f
    if (kind == TrendMetricKind.SCORE) {
        // Ratings are 0–5; pin the range so the line's slope is visually comparable across novels
        // instead of auto-fitting to a narrow band that exaggerates noise.
        yAxis.axisMinimum = 0f
        yAxis.axisMaximum = 5f
    } else {
        yAxis.axisMinimum = 0f
    }
    chart.axisRight.isEnabled = false

    // Animate the line draw on first appearance so the trend "arrives"; short so repeated opens
    // don't feel slow.
    chart.animateX(400)

    // A readable default height; the screen wraps the chart in its own card layout.
    chart.layoutParams =
        android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            context.dp(180),
        )
    chart.invalidate()
    return chart
}

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
            TrendMetricKind.SCORE -> String.format(Locale.US, "%.1f", value)
            TrendMetricKind.PATREON_MEMBERS -> intFormat.format(value.toInt())
            // Stored as cents; show compact dollars ($1.2k, $12k).
            TrendMetricKind.PATREON_USD -> compactUsd(value.toLong())
        }

    private fun compactUsd(cents: Long): String {
        val dollars = cents / 100.0
        return when {
            dollars >= 1_000_000 -> String.format(Locale.US, "$%.1fM", dollars / 1_000_000)
            dollars >= 1_000 -> String.format(Locale.US, "$%.1fk", dollars / 1_000)
            else -> String.format(Locale.US, "$%.0f", dollars)
        }
    }
}
