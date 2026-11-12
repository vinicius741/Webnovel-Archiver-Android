package com.vinicius741.webnovelarchiver.feature.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.vinicius741.webnovelarchiver.ui.ThemeManager

/**
 * A thin, flat progress line (the v3 player's 3dp gold bar): a faint track with a filled part.
 * Draws itself, so updating [fraction] on every sentence is a cheap invalidate.
 */
internal class PlayerProgressLine(
    context: Context,
    fillColor: Int = ThemeManager.colors.primary,
    trackColor: Int = TRACK_COLOR,
    private val rounded: Boolean = false,
) : View(context) {
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = trackColor }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor }
    private val rect = RectF()

    var fraction: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (clamped == field) return
            field = clamped
            invalidate()
        }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val radius = if (rounded) height / 2f else 0f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, trackPaint)
        if (fraction <= 0f) return
        rect.set(0f, 0f, width * fraction, height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
    }

    private companion object {
        const val TRACK_COLOR = 0x0FFFFFFF
    }
}
