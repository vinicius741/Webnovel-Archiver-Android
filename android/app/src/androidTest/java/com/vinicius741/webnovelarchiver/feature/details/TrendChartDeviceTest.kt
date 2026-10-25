package com.vinicius741.webnovelarchiver.feature.details

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.mikephil.charting.charts.LineChart
import com.vinicius741.webnovelarchiver.app.MainActivity
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrendChartDeviceTest {
    @Test
    fun layoutRedrawKeepsTheTrendsStoryAndFocus() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = context.appContainer.repository
        val id = "trend-redraw-device-fixture"
        runBlocking {
            repository.upsertStory(Story(id = id, title = "Trend redraw fixture", sourceUrl = "https://example.invalid/trends"))
        }
        try {
            val intent =
                Intent(context, MainActivity::class.java).apply {
                    putExtra("dev_start_screen", "details")
                    putExtra("dev_start_story", id)
                }
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                val deadline = System.currentTimeMillis() + 15_000
                var ready = false
                while (!ready && System.currentTimeMillis() < deadline) {
                    scenario.onActivity { ready = it.navigator.current == AppRoute.Details(id) }
                    if (!ready) Thread.sleep(50)
                }
                assertTrue("Fixture details did not load", ready)
                scenario.onActivity { activity ->
                    activity.showTrends(id, FOCUS_SCORE)
                    repeat(2) {
                        checkNotNull(activity.rerender).invoke()
                        assertEquals(AppRoute.Trends(id, FOCUS_SCORE), activity.navigator.current)
                    }
                }
            }
        } finally {
            runBlocking { repository.deleteStory(id) }
        }
    }

    @Test
    fun everyMetricRendersWithFittedAxesOnCoverAndInnerWidths() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val start = 1_790_294_400_000L
            val day = 86_400_000L
            val series =
                mapOf(
                    TrendMetricKind.SCORE to listOf(4.61, 4.65, 4.72),
                    TrendMetricKind.PATREON_MEMBERS to listOf(1200.0, 1220.0, 1250.0),
                    TrendMetricKind.PATREON_USD to listOf(340_000.0, 345_000.0, 349_000.0),
                    TrendMetricKind.COUNT to listOf(1_200_000.0, 1_210_000.0, 1_250_000.0),
                )
            for ((kind, values) in series) {
                val points = values.mapIndexed { index, value -> (start + index * day) to value }
                for (width in listOf(1080, 1968)) {
                    val chart = buildTrendChart(context, points, kind)
                    render(chart, width)
                    assertEquals(3, chart.data!!.entryCount)
                    assertTrue(chart.axisLeft.axisMinimum <= values.first())
                    assertTrue(chart.axisLeft.axisMaximum >= values.last())
                    if (kind == TrendMetricKind.SCORE) {
                        assertTrue(chart.axisLeft.axisMinimum > 4f)
                        assertTrue(chart.axisLeft.axisMaximum <= 5f)
                        assertEquals("4.65", chart.axisLeft.valueFormatter.getFormattedValue(4.65f, chart.axisLeft))
                    }
                    assertTrue(chart.axisLeft.entryCount > 1)
                    val labels = (0 until chart.axisLeft.entryCount).map { chart.axisLeft.getFormattedLabel(it) }
                    assertEquals("$kind at width $width has duplicate Y labels: $labels", labels.size, labels.distinct().size)
                    assertTrue(
                        chart.xAxis.valueFormatter
                            .getFormattedValue(start.toFloat(), chart.xAxis)
                            .isNotBlank(),
                    )
                }
            }
        }
    }

    @Test
    fun emptyAndSinglePointChartsRenderWithoutInvalidRanges() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (points in listOf(emptyList(), listOf(1_790_294_400_000L to 4.65))) {
                for (width in listOf(1080, 1968)) {
                    val chart = buildTrendChart(context, points, TrendMetricKind.SCORE)
                    render(chart, width)
                    assertEquals(points.size, chart.data?.entryCount ?: 0)
                    assertTrue(chart.axisLeft.axisMinimum.isFinite())
                    assertTrue(chart.axisLeft.axisMaximum.isFinite())
                }
            }
        }
    }

    private fun render(
        chart: LineChart,
        width: Int,
    ) {
        chart.stopAnimations()
        chart.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY),
        )
        chart.layout(0, 0, width, 700)
        val bitmap = Bitmap.createBitmap(width, 700, Bitmap.Config.ARGB_8888)
        try {
            chart.draw(Canvas(bitmap))
            val pixels = IntArray(width * 700)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, 700)
            assertTrue("Chart did not draw any content", pixels.any { it != 0 })
        } finally {
            bitmap.recycle()
        }
    }
}
