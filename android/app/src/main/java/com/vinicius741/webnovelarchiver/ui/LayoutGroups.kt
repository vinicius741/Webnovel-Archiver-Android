package com.vinicius741.webnovelarchiver.ui

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup

private const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT

/* ------------------------------------------------------------------ */
/* WrapLayout — a horizontal FlowLayout that wraps children to the    */
/* next line when they exceed the available width. Used for button    */
/* rows and chip groups so primary actions never overflow the screen. */
/* ------------------------------------------------------------------ */

class WrapLayout(context: Context) : ViewGroup(context) {
    var horizontalSpacingDp: Int = 8
    var verticalSpacingDp: Int = 8

    private fun hd(): Int = context.dp(horizontalSpacingDp)
    private fun vd(): Int = context.dp(verticalSpacingDp)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingStart - paddingEnd
        val padTop = paddingTop
        val padBottom = paddingBottom
        var widthUsed = 0
        var heightUsed = padTop
        var lineWidth = 0
        var lineHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, heightUsed)
            val lp = child.layoutParams as MarginLayoutParams
            val cw = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val ch = child.measuredHeight + lp.topMargin + lp.bottomMargin
            if (lineWidth + cw > maxWidth && lineWidth > 0) {
                widthUsed = maxOf(widthUsed, lineWidth - hd())
                heightUsed += lineHeight + vd()
                lineWidth = cw
                lineHeight = ch
            } else {
                if (lineWidth > 0) lineWidth += hd()
                lineWidth += cw
                lineHeight = maxOf(lineHeight, ch)
            }
        }
        widthUsed = maxOf(widthUsed, lineWidth)
        heightUsed += lineHeight + padBottom
        val resolvedWidth = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(widthMeasureSpec)
            else -> widthUsed + paddingStart + paddingEnd
        }
        setMeasuredDimension(resolvedWidth, resolveSize(heightUsed, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val maxWidth = (right - left) - paddingStart - paddingEnd
        var x = paddingStart
        var y = paddingTop
        var lineHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val lp = child.layoutParams as MarginLayoutParams
            val cw = child.measuredWidth
            val ch = child.measuredHeight
            if (x + lp.leftMargin + cw + lp.rightMargin > paddingStart + maxWidth && x > paddingStart) {
                x = paddingStart
                y += lineHeight + vd()
                lineHeight = 0
            }
            val cl = x + lp.leftMargin
            val ct = y + lp.topMargin
            child.layout(cl, ct, cl + cw, ct + ch)
            x += lp.leftMargin + cw + lp.rightMargin + hd()
            lineHeight = maxOf(lineHeight, ch + lp.topMargin + lp.bottomMargin)
        }
    }

    override fun checkLayoutParams(p: LayoutParams?): Boolean = p is MarginLayoutParams
    override fun generateLayoutParams(p: LayoutParams): LayoutParams = MarginLayoutParams(p)
    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams = MarginLayoutParams(context, attrs)
    override fun generateDefaultLayoutParams(): LayoutParams = MarginLayoutParams(WRAP_CONTENT, WRAP_CONTENT)
}

/* ------------------------------------------------------------------ */
/* GridLayout — arranges children in a fixed number of equal-width     */
/* columns. Use for button groups where a predictable grid is cleaner  */
/* than a wrapping flow.                                              */
/* ------------------------------------------------------------------ */

class GridLayout(context: Context) : ViewGroup(context) {
    var columnCount: Int = 2
    var horizontalSpacingDp: Int = 8
    var verticalSpacingDp: Int = 8

    private fun hd(): Int = context.dp(horizontalSpacingDp)
    private fun vd(): Int = context.dp(verticalSpacingDp)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val rawWidth = MeasureSpec.getSize(widthMeasureSpec)
        val availableWidth = (rawWidth - paddingStart - paddingEnd).coerceAtLeast(0)
        val cellWidth = if (columnCount > 0) {
            (availableWidth - (columnCount - 1) * hd()) / columnCount
        } else availableWidth

        var totalHeight = paddingTop + paddingBottom
        var rowHeight = 0
        var colIndex = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue

            val lp = child.layoutParams as MarginLayoutParams
            val childWidthMeasure = (cellWidth - lp.leftMargin - lp.rightMargin).coerceAtLeast(0)
            val cellWidthSpec = MeasureSpec.makeMeasureSpec(childWidthMeasure, MeasureSpec.EXACTLY)
            measureChildWithMargins(child, cellWidthSpec, 0, heightMeasureSpec, 0)

            rowHeight = maxOf(rowHeight, child.measuredHeight + lp.topMargin + lp.bottomMargin)
            colIndex++

            if (colIndex >= columnCount) {
                totalHeight += rowHeight
                if (i < childCount - 1) totalHeight += vd()
                colIndex = 0
                rowHeight = 0
            }
        }

        if (colIndex > 0) {
            totalHeight += rowHeight
        }

        val resolvedWidth = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.EXACTLY -> rawWidth
            else -> availableWidth + paddingStart + paddingEnd
        }
        setMeasuredDimension(resolvedWidth, resolveSize(totalHeight, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val availableWidth = ((right - left) - paddingStart - paddingEnd).coerceAtLeast(0)
        val cellWidth = if (columnCount > 0) {
            (availableWidth - (columnCount - 1) * hd()) / columnCount
        } else availableWidth

        var x = paddingStart
        var y = paddingTop
        var rowHeight = 0
        var colIndex = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue

            val lp = child.layoutParams as MarginLayoutParams
            val cw = child.measuredWidth
            val ch = child.measuredHeight

            val cl = x + lp.leftMargin
            val ct = y + lp.topMargin
            child.layout(cl, ct, cl + cw, ct + ch)

            rowHeight = maxOf(rowHeight, ch + lp.topMargin + lp.bottomMargin)
            colIndex++
            x += cellWidth + hd()

            if (colIndex >= columnCount) {
                y += rowHeight + vd()
                x = paddingStart
                colIndex = 0
                rowHeight = 0
            }
        }
    }

    override fun checkLayoutParams(p: LayoutParams?): Boolean = p is MarginLayoutParams
    override fun generateLayoutParams(p: LayoutParams): LayoutParams = MarginLayoutParams(p)
    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams = MarginLayoutParams(context, attrs)
    override fun generateDefaultLayoutParams(): LayoutParams = MarginLayoutParams(WRAP_CONTENT, WRAP_CONTENT)
}
