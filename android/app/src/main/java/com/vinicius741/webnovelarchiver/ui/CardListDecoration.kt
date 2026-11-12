package com.vinicius741.webnovelarchiver.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.recyclerview.widget.RecyclerView

/**
 * Draws a v3 card (surface fill, hairline border, 18dp corners) behind a RecyclerView's rows, with
 * hairlines between them, so a long list reads as one card while every row still recycles. Rows
 * stay transparent; [isRow] says which adapter positions belong to the card (a header item does
 * not). The card's ends are only rounded when its first or last row is on screen.
 */
internal class CardListDecoration(
    context: Context,
    private val isRow: (Int) -> Boolean,
) : RecyclerView.ItemDecoration() {
    private val radius = context.dp(ThemeManager.shapes.cardRadius).toFloat()
    private val stroke = context.dp(1).coerceAtLeast(1).toFloat()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val line = Paint()
    private val rect = RectF()

    override fun onDraw(
        canvas: Canvas,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        val colors = ThemeManager.colors
        fill.color = colors.elevation1
        border.color = colors.line
        border.strokeWidth = stroke
        line.color = colors.line
        val adapter = parent.adapter ?: return
        var top = Float.MAX_VALUE
        var bottom = Float.MIN_VALUE
        var firstRowSeen = false
        var lastRowSeen = false
        val firstRow = (0 until adapter.itemCount).firstOrNull(isRow) ?: return
        val lastRow = (adapter.itemCount - 1 downTo 0).firstOrNull(isRow) ?: return
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val position = parent.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION || !isRow(position)) continue
            top = minOf(top, child.top + child.translationY)
            bottom = maxOf(bottom, child.bottom + child.translationY)
            if (position == firstRow) firstRowSeen = true
            if (position == lastRow) lastRowSeen = true
        }
        if (top == Float.MAX_VALUE) return
        // An end scrolled off screen is pushed past the edge so its rounded corners stay hidden.
        val drawTop = if (firstRowSeen) top else top - radius * 2
        val drawBottom = if (lastRowSeen) bottom else bottom + radius * 2
        val left = parent.paddingLeft.toFloat()
        val right = (parent.width - parent.paddingRight).toFloat()
        rect.set(left + stroke / 2, drawTop + stroke / 2, right - stroke / 2, drawBottom - stroke / 2)
        canvas.drawRoundRect(rect, radius, radius, fill)
        canvas.drawRoundRect(rect, radius, radius, border)
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val position = parent.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION || !isRow(position) || position == firstRow) continue
            val y = child.top + child.translationY
            canvas.drawRect(left, y, right, y + stroke, line)
        }
    }
}
