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

/**
 * Builds a fully themed [LineChart] for a trend series. Pure view construction — no data fetching,
 * no Android lifecycle — so the Trends screen can call it once per series after loading history.
 *
 * The chart intentionally disables vertical zoom and pin zoom (which fight the screen's vertical
 * scroll) and enables only horizontal drag so a long history can be panned. The X axis is epoch-millis
 * formatted as a date; the Y axis format comes from [kind] and its range is fitted to the data by
 * [TrendAxisPlanning] (clamped to the metric's valid domain) so small movements stay visible.
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
    // No manual view-port offsets: the fitted Y range can produce wide labels ("1,250" members),
    // and the chart only reserves enough room for them when it computes offsets itself.

    val xAxis = chart.xAxis
    xAxis.position = XAxis.XAxisPosition.BOTTOM
    xAxis.valueFormatter = DateAxisFormatter()
    xAxis.textColor = colors.onSurfaceVariant
    xAxis.gridColor = colors.outlineVariant
    xAxis.gridLineWidth = 1f
    xAxis.setDrawAxisLine(false)
    xAxis.setLabelCount(4, true)
    xAxis.textSize = 10f
    // Snapshots are coalesced to one point per calendar day, so labels closer than a day can only
    // repeat the same date — force day granularity to keep a short history's axis readable.
    xAxis.granularity = MILLIS_PER_DAY_FLOAT
    // Keep the first/last date from being clipped at the chart edges.
    xAxis.setAvoidFirstLastClipping(true)

    val yAxis = chart.axisLeft
    yAxis.valueFormatter = YAxisFormatter(kind)
    yAxis.textColor = colors.onSurfaceVariant
    yAxis.gridColor = colors.outlineVariant
    yAxis.gridLineWidth = 1f
    yAxis.setDrawAxisLine(false)
    yAxis.textSize = 10f
    // A fitted (narrow) range makes the default label count draw duplicate, overlapping labels;
    // cap it and let the chart pick clean intervals instead of forcing an exact count.
    yAxis.setLabelCount(Y_AXIS_LABEL_COUNT, false)
    if (kind == TrendMetricKind.PATREON_MEMBERS) {
        // Members are whole counts: fractional grid lines would format to duplicate integers.
        yAxis.granularity = 1f
    }
    // Fit the axis to the recorded data (clamped to the metric's valid domain) so small movements
    // stay visible; a fixed 0–5 / 0-based axis rendered real week-to-week changes as a flat line.
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

    // Animate the line draw on first appearance so the trend "arrives"; short so repeated opens
    // don't feel slow.
    chart.animateX(400)

    // The chart's height is set by the Trends screen when it adds the view to its card (a
    // LineChart has no intrinsic height and WRAP_CONTENT would collapse it inside a ScrollView).
    chart.invalidate()
    return chart
}

/** X-axis granularity: one calendar day in epoch millis (the resolution snapshots are coalesced to). */
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
            // Two decimals: the fitted range is narrow, and one decimal rounded adjacent grid
            // lines to the same label (4.55 and 4.65 both read "4.6").
            TrendMetricKind.SCORE -> String.format(Locale.US, "%.2f", value)
            TrendMetricKind.PATREON_MEMBERS -> intFormat.format(value.toInt())
            // Stored as cents; show compact dollars ($1.20k, $12.5k).
            TrendMetricKind.PATREON_USD -> compactUsd(value.toLong())
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
