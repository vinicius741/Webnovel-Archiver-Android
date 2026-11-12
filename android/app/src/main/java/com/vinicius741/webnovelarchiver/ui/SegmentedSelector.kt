package com.vinicius741.webnovelarchiver.ui

import android.content.Context
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The v3 segmented control for any list of options: a rounded track with equal-width options, the
 * selected one raised. A count after a label shows faint (null or 0 shows none). [onSelect] gets
 * the tapped index after the control has moved its highlight.
 */
internal class SegmentedSelector(
    context: Context,
    labels: List<String>,
    selected: Int,
    counts: List<Int?> = emptyList(),
    private val onSelect: (Int) -> Unit,
) {
    private val colors = ThemeManager.colors
    private val buttons = mutableListOf<TextView>()
    private val labels = labels.toList()

    val view: LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = context.dp(Spacing.XS)
            setPadding(pad, pad, pad, pad)
            background = roundedBg(colors.elevation2, context.dp(TRACK_RADIUS_DP).toFloat())
        }

    init {
        labels.forEachIndexed { index, label ->
            val text = SpannableStringBuilder(label)
            counts.getOrNull(index)?.takeIf { it > 0 }?.let { count ->
                val start = text.length + 1
                text.append(" ").append(count.toString())
                text.setSpan(ForegroundColorSpan(colors.faint), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val button =
                makeText(context, "", Type.LABEL_LARGE, colors.onSurfaceVariant).apply {
                    this.text = text
                    gravity = Gravity.CENTER
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        select(index)
                        onSelect(index)
                    }
                }
            buttons += button
            view.addView(button, LinearLayout.LayoutParams(0, context.dp(BUTTON_HEIGHT_DP), 1f))
        }
        select(selected)
    }

    fun select(index: Int) {
        val radius = view.context.dp(BUTTON_HEIGHT_DP).toFloat() / 2
        buttons.forEachIndexed { i, button ->
            val on = i == index
            button.setTextColor(if (on) colors.onSurface else colors.onSurfaceVariant)
            button.background = ripple(roundedBg(if (on) colors.elevation5 else Color.TRANSPARENT, radius), radius, colors.onSurface)
            button.isSelected = on
            button.contentDescription = labels[i] + if (on) ", selected" else ""
        }
    }

    private companion object {
        const val TRACK_RADIUS_DP = 22
        const val BUTTON_HEIGHT_DP = 38
    }
}
