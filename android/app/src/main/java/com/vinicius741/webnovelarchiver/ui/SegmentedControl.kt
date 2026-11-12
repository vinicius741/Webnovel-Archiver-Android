package com.vinicius741.webnovelarchiver.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

internal object SegmentedControl {
    /** One segment of [makeSegmentedControl]; [dot] marks something waiting behind an unselected segment. */
    data class Segment(
        val label: String,
        val iconRes: Int = 0,
        val dot: Boolean = false,
    )
}

/**
 * A v3 segmented control: a pill track with equal-width segments, the selected one raised. Used for
 * a few mutually exclusive views of one thing (Trends range, AI Cover / Description). Callers rebuild
 * it after [onSelect], like the chip rows.
 */
internal fun makeSegmentedControl(
    context: Context,
    segments: List<SegmentedControl.Segment>,
    selected: Int,
    onSelect: (Int) -> Unit,
): LinearLayout {
    val colors = ThemeManager.colors
    val trackRadius = context.dp(SEGMENT_HEIGHT_DP / 2 + 3).toFloat()
    val radius = context.dp(SEGMENT_HEIGHT_DP / 2).toFloat()
    return LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        val pad = context.dp(3)
        setPadding(pad, pad, pad, pad)
        background = strokeBg(colors.elevation1, trackRadius, colors.line, context.dp(1).coerceAtLeast(1))
        segments.forEachIndexed { index, segment ->
            val on = index == selected
            val fg = if (on) colors.onSurface else colors.onSurfaceVariant
            val item =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    minimumHeight = context.dp(SEGMENT_HEIGHT_DP)
                    setPadding(context.dp(Spacing.SM), 0, context.dp(Spacing.SM), 0)
                    isClickable = true
                    isFocusable = true
                    contentDescription = segment.label
                    isSelected = on
                    background = ripple(roundedBg(if (on) colors.elevation4 else Color.TRANSPARENT, radius), radius, colors.onSurface)
                    if (segment.iconRes != 0) {
                        addView(
                            ImageView(context).apply { setImageDrawable(context.tintedIcon(segment.iconRes, fg)) },
                            LinearLayout.LayoutParams(context.dp(18), context.dp(18)).apply { marginEnd = context.dp(6) },
                        )
                    }
                    addView(
                        TextView(context).apply {
                            text = segment.label
                            textSize = Type.LABEL_LARGE.size()
                            setTextColor(fg)
                            typeface = Typeface.create(Typeface.DEFAULT, if (on) Typeface.BOLD else Typeface.NORMAL)
                            maxLines = 1
                            includeFontPadding = false
                        },
                    )
                    if (segment.dot) {
                        addView(
                            View(context).apply { background = roundedBg(colors.primary, context.dp(4).toFloat()) },
                            LinearLayout.LayoutParams(context.dp(8), context.dp(8)).apply { marginStart = context.dp(6) },
                        )
                    }
                    setOnClickListener { if (!on) onSelect(index) }
                }
            addView(item, LinearLayout.LayoutParams(0, context.dp(SEGMENT_HEIGHT_DP), 1f))
        }
    }
}

private const val SEGMENT_HEIGHT_DP = 40
