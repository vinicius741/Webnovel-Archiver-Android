package com.vinicius741.webnovelarchiver.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.vinicius741.webnovelarchiver.R

private const val CHIP_HEIGHT_DP = 36
private const val CHIP_ICON_DP = 18
private const val SELECTED_COUNT_ALPHA = 0xBF

/**
 * The v3 filter chip with an optional muted count after its label ("Fantasy 25"): outlined when
 * idle, gold container with a leading check when [selected]. [iconRes] adds a leading icon when
 * not selected (e.g. the globe on source filters).
 */
internal fun makeCountedChip(
    context: Context,
    label: String,
    count: Int?,
    selected: Boolean,
    iconRes: Int = 0,
    onClick: () -> Unit,
): LinearLayout {
    val colors = ThemeManager.colors
    val radius = context.dp(CHIP_HEIGHT_DP / 2).toFloat()
    val fg = if (selected) colors.onPrimaryContainer else colors.onSurface
    val countColor =
        if (selected) {
            ColorUtils.setAlphaComponent(
                colors.onPrimaryContainer,
                SELECTED_COUNT_ALPHA,
            )
        } else {
            colors.onSurfaceVariant
        }
    return LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dp(CHIP_HEIGHT_DP)
        setPadding(context.dp(Spacing.MD + 2), 0, context.dp(Spacing.MD + 2), 0)
        isClickable = true
        isFocusable = true
        background =
            ripple(
                if (selected) {
                    roundedBg(
                        colors.primaryContainer,
                        radius,
                    )
                } else {
                    strokeBg(Color.TRANSPARENT, radius, colors.outline, context.dp(1))
                },
                radius,
                colors.onSurface,
            )
        val leading = if (selected) R.drawable.wna_check else iconRes
        if (leading != 0) {
            addView(
                ImageView(context).apply {
                    setImageDrawable(context.tintedIcon(leading, fg))
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                },
                LinearLayout
                    .LayoutParams(
                        context.dp(CHIP_ICON_DP),
                        context.dp(CHIP_ICON_DP),
                    ).apply { marginEnd = context.dp(Spacing.XS + 2) },
            )
        }
        addView(
            TextView(context).apply {
                text = countedLabel(label, count, countColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, CHIP_TEXT_SP)
                typeface = Typeface.create(typeface, Typeface.BOLD)
                setTextColor(fg)
                includeFontPadding = false
                maxLines = 1
            },
        )
        contentDescription = if (count != null) "$label, $count" else label
        isSelected = selected
        setOnClickListener { onClick() }
    }
}

/** "[label] [count]" with the count in [countColor], for chips and tabs. */
internal fun countedLabel(
    label: String,
    count: Int?,
    countColor: Int,
): CharSequence {
    if (count == null) return label
    val builder = SpannableStringBuilder(label).append("  ")
    val start = builder.length
    builder.append(count.toString())
    builder.setSpan(ForegroundColorSpan(countColor), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    return builder
}

private const val CHIP_TEXT_SP = 13.5f
