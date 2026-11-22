package com.vinicius741.webnovelarchiver.feature.trending

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import androidx.core.graphics.ColorUtils
import com.vinicius741.webnovelarchiver.domain.metrics.MetricPoint
import com.vinicius741.webnovelarchiver.ui.dp

/**
 * A small line chart of one signal: a stroked path over a fading fill, ending in a dot at the
 * latest value. X is time and Y is fitted to the points, so a small rise still reads as a rise.
 */
internal class TrendSparkline(
    context: Context,
    private val color: Int,
    private val strokeDp: Float = 2f,
) : View(context) {
    private var points: List<MetricPoint> = emptyList()
    private val stroke =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = context.dp(1) * strokeDp
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = this@TrendSparkline.color
        }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@TrendSparkline.color }
    private val line = Path()
    private val area = Path()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setPoints(next: List<MetricPoint>) {
        if (next == points) return
        points = next
        invalidate()
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        fill.shader =
            LinearGradient(
                0f,
                0f,
                0f,
                h.toFloat(),
                ColorUtils.setAlphaComponent(color, FILL_TOP_ALPHA),
                ColorUtils.setAlphaComponent(color, 0),
                Shader.TileMode.CLAMP,
            )
    }

    override fun onDraw(canvas: Canvas) {
        if (points.size < 2 || width == 0 || height == 0) return
        val inset = stroke.strokeWidth * 2
        val left = inset
        val right = width - inset
        val top = inset
        val bottom = height - inset
        val minX = points.first().first
        val spanX = (points.last().first - minX).coerceAtLeast(1L).toFloat()
        val minY = points.minOf { it.second }
        val spanY = (points.maxOf { it.second } - minY).takeIf { it > 0.0 }

        fun x(point: MetricPoint) = left + (point.first - minX) / spanX * (right - left)

        val middle = (top + bottom) / 2

        // A flat series draws along the middle rather than the floor.
        fun y(point: MetricPoint) = spanY?.let { bottom - ((point.second - minY) / it).toFloat() * (bottom - top) } ?: middle
        line.reset()
        area.reset()
        points.forEachIndexed { index, point ->
            if (index == 0) line.moveTo(x(point), y(point)) else line.lineTo(x(point), y(point))
        }
        area.addPath(line)
        area.lineTo(x(points.last()), height.toFloat())
        area.lineTo(x(points.first()), height.toFloat())
        area.close()
        canvas.drawPath(area, fill)
        canvas.drawPath(line, stroke)
        canvas.drawCircle(x(points.last()), y(points.last()), stroke.strokeWidth * DOT_SCALE, dot)
    }

    private companion object {
        const val FILL_TOP_ALPHA = 0x55
        const val DOT_SCALE = 1.6f
    }
}
