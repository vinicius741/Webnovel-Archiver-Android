package com.vinicius741.webnovelarchiver.feature.narration

import android.content.Context
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg

/** The Listen hub's sections, in segmented-control order. */
internal enum class ListenSegment(
    val label: String,
) {
    QUEUE("Queue"),
    GENERATED("Generated"),
    SETUP("Setup"),
}

/**
 * The v3 segmented control: a rounded track with equal-width options, the selected one raised.
 * [counts] adds a faint number after a label (0 or missing shows none).
 */
internal class ListenSegmentedControl(
    context: Context,
    selected: ListenSegment,
    counts: Map<ListenSegment, Int>,
    private val onSelect: (ListenSegment) -> Unit,
) {
    private val colors = ThemeManager.colors
    private val buttons = mutableMapOf<ListenSegment, TextView>()

    val view: LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = context.dp(Spacing.XS)
            setPadding(pad, pad, pad, pad)
            background = roundedBg(colors.elevation2, context.dp(TRACK_RADIUS_DP).toFloat())
        }

    init {
        ListenSegment.entries.forEach { segment ->
            val label = SpannableStringBuilder(segment.label)
            counts[segment]?.takeIf { it > 0 }?.let { count ->
                val start = label.length + 1
                label.append(" ").append(count.toString())
                label.setSpan(ForegroundColorSpan(colors.faint), start, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val button =
                makeText(context, "", Type.LABEL_LARGE, colors.onSurfaceVariant).apply {
                    text = label
                    gravity = Gravity.CENTER
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        select(segment)
                        onSelect(segment)
                    }
                }
            buttons[segment] = button
            view.addView(button, LinearLayout.LayoutParams(0, context.dp(BUTTON_HEIGHT_DP), 1f))
        }
        select(selected)
    }

    fun select(segment: ListenSegment) {
        val context = view.context
        val radius = context.dp(BUTTON_HEIGHT_DP).toFloat() / 2
        buttons.forEach { (key, button) ->
            val on = key == segment
            button.setTextColor(if (on) colors.onSurface else colors.onSurfaceVariant)
            button.background = ripple(roundedBg(if (on) colors.elevation5 else Color.TRANSPARENT, radius), radius, colors.onSurface)
            button.isSelected = on
            button.contentDescription = key.label + if (on) ", selected" else ""
        }
    }

    private companion object {
        const val TRACK_RADIUS_DP = 22
        const val BUTTON_HEIGHT_DP = 38
    }
}
