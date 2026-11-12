package com.vinicius741.webnovelarchiver.feature.reader

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.strokeBg
import com.vinicius741.webnovelarchiver.ui.tintedIcon

// The reader's docked chapter navigation (v3): a rounded bar with "Previous", the chapter position
// and "Next", kept thumb-reachable without taking a big bite out of the reading viewport.
internal fun readerChapterNav(
    context: Context,
    chapterCount: Int,
    currentIndex: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
): LinearLayout {
    val colors = ThemeManager.colors
    val radius = context.dp(NAV_RADIUS_DP).toFloat()
    val navBar =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = strokeBg(colors.elevation2, radius, colors.line, context.dp(1).coerceAtLeast(1))
            setPadding(context.dp(Spacing.XS), 0, context.dp(Spacing.XS), 0)
            minimumHeight = context.dp(NAV_HEIGHT_DP)
        }
    navBar.addView(navButton(context, "Previous", R.drawable.wna_chevron_left, iconAtStart = true, enabled = currentIndex > 0, onPrevious))
    navBar.addView(
        makeText(context, "${currentIndex + 1} / $chapterCount", Type.LABEL_MEDIUM, colors.onSurfaceVariant).apply {
            gravity = Gravity.CENTER
            fontFeatureSettings = "tnum"
        },
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
    )
    navBar.addView(
        navButton(context, "Next", R.drawable.wna_chevron_right, iconAtStart = false, enabled = currentIndex < chapterCount - 1, onNext),
    )
    return navBar
}

private fun navButton(
    context: Context,
    label: String,
    icon: Int,
    iconAtStart: Boolean,
    enabled: Boolean,
    action: () -> Unit,
): TextView {
    val colors = ThemeManager.colors
    val tint = if (enabled) colors.primary else colors.onSurfaceVariant
    val radius = context.dp(BUTTON_RADIUS_DP).toFloat()
    return makeText(context, label, Type.LABEL_LARGE, tint).apply {
        gravity = Gravity.CENTER_VERTICAL
        contentDescription = "$label chapter"
        val drawable = context.tintedIcon(icon, tint)
        if (iconAtStart) {
            setCompoundDrawablesRelativeWithIntrinsicBounds(drawable, null, null, null)
        } else {
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, drawable, null)
        }
        compoundDrawablePadding = context.dp(2)
        setPadding(context.dp(Spacing.SM), 0, context.dp(Spacing.SM + 4), 0)
        if (!iconAtStart) setPadding(context.dp(Spacing.SM + 4), 0, context.dp(Spacing.SM), 0)
        minHeight = context.dp(BUTTON_HEIGHT_DP)
        background = ripple(roundedBg(android.graphics.Color.TRANSPARENT, radius), radius, colors.primary)
        alpha = if (enabled) 1f else DISABLED_ALPHA
        isEnabled = enabled
        isClickable = enabled
        isFocusable = enabled
        if (enabled) setOnClickListener { action() }
    }
}

private const val NAV_HEIGHT_DP = 60
private const val NAV_RADIUS_DP = 20
private const val BUTTON_HEIGHT_DP = 48
private const val BUTTON_RADIUS_DP = 24
private const val DISABLED_ALPHA = 0.38f
