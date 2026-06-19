package com.vinicius741.webnovelarchiver.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.widget.Button
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R

// ------------------------------------------------------------------
// Buttons
// ------------------------------------------------------------------

enum class Btn { THEME_DEFAULT, FILLED, TONAL, OUTLINED, TEXT, ELEVATED, ERROR }

private fun resolvedVariant(v: Btn): Btn {
    if (v != Btn.THEME_DEFAULT) return v
    // Match legacy theme buttonDefaults.mode.
    return when (ThemeManager.current.id) {
        "midnight" -> Btn.OUTLINED
        "forest" -> Btn.ELEVATED
        else -> Btn.TONAL
    }
}

fun makeButton(
    context: Context,
    label: String,
    variant: Btn = Btn.THEME_DEFAULT,
    iconRes: Int = 0,
    onClick: () -> Unit,
): Button {
    val t = ThemeManager.current
    val colors = t.colors
    val radiusPx = context.dp(t.shapes.buttonRadius).toFloat()
    val v = resolvedVariant(variant)
    val padH = context.dp(Space.LG)
    val padV = context.dp(Space.SM + 2)

    fun bgWithRipple(
        content: Drawable,
        rippleColor: Int,
    ): Drawable = ripple(content, radiusPx, rippleColor)

    val (bgDrawable, fgColor, ripple) =
        when (v) {
            Btn.FILLED ->
                Triple(
                    roundedBg(colors.primary, radiusPx),
                    colors.onPrimary,
                    colors.onPrimary,
                )
            Btn.TONAL ->
                Triple(
                    roundedBg(colors.secondaryContainer, radiusPx),
                    colors.onSecondaryContainer,
                    colors.onSecondaryContainer,
                )
            Btn.ELEVATED -> {
                val d = roundedBg(colors.elevation2, radiusPx)
                Triple(d, colors.primary, colors.onSurface)
            }
            Btn.OUTLINED ->
                Triple(
                    strokeBg(Color.TRANSPARENT, radiusPx, colors.outline, context.dp(1)),
                    colors.primary,
                    colors.primary,
                )
            Btn.TEXT ->
                Triple(
                    roundedBg(Color.TRANSPARENT, radiusPx),
                    colors.primary,
                    colors.primary,
                )
            Btn.ERROR ->
                Triple(
                    roundedBg(colors.errorContainer, radiusPx),
                    colors.onErrorContainer,
                    colors.onErrorContainer,
                )
            Btn.THEME_DEFAULT -> error("unreachable")
        }

    return Button(context).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.LABEL_LARGE.size())
        typeface = Typeface.create(typeface, Typeface.BOLD)
        setTextColor(fgColor)
        letterSpacing = 0.04f
        isAllCaps = false
        transformationMethod = null
        includeFontPadding = false
        setPadding(padH, padV, padH, padV)
        minHeight = context.dp(44)
        minWidth = 0
        minimumHeight = context.dp(44)
        stateListAnimator = null
        background = bgWithRipple(bgDrawable, 0x33000000)
        if (v == Btn.ELEVATED) elevate(2f)
        setOnClickListener { onClick() }
        if (iconRes != 0) {
            context.tintedIcon(iconRes, fgColor)?.let {
                setCompoundDrawablesRelativeWithIntrinsicBounds(it, null, null, null)
                compoundDrawablePadding = context.dp(6)
            }
        }
    }
}

// ------------------------------------------------------------------
// Filter chips
// ------------------------------------------------------------------

fun makeChip(
    context: Context,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
): TextView {
    val t = ThemeManager.current
    val colors = t.colors
    val radiusPx = context.dp(t.shapes.chipRadius).toFloat()
    val padH = context.dp(Space.MD + 2)
    val padV = context.dp(Space.XS + 1)
    val fg = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant

    return TextView(context).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.LABEL_LARGE.size())
        typeface = Typeface.create(typeface, Typeface.BOLD)
        setTextColor(fg)
        letterSpacing = 0.02f
        includeFontPadding = false
        setPadding(padH, padV, padH, padV)
        isClickable = true
        isFocusable = true
        background =
            ripple(
                if (selected) {
                    roundedBg(
                        colors.secondaryContainer,
                        radiusPx,
                    )
                } else {
                    strokeBg(Color.TRANSPARENT, radiusPx, colors.outline, context.dp(1))
                },
                radiusPx,
                colors.onSurface,
            )
        setOnClickListener { onClick() }
    }
}

/**
 * A filter chip for a *source* provider (RoyalRoad / ScribbleHub). Visually distinct from a genre
 * tag chip via a leading globe icon + a filled tint, so the two filter kinds don't read as one
 * undifferentiated row (audit L4).
 */
fun makeSourceChip(
    context: Context,
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
): android.widget.LinearLayout {
    val t = ThemeManager.current
    val colors = t.colors
    val radiusPx = context.dp(t.shapes.chipRadius).toFloat()
    val padH = context.dp(Space.SM + 2)
    val padV = context.dp(Space.XS + 1)
    val containerColor = if (selected) colors.secondaryContainer else colors.surfaceVariant
    val contentColor = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant
    val icon =
        android.widget.ImageView(context).apply {
            setImageDrawable(context.tintedIcon(com.vinicius741.webnovelarchiver.R.drawable.wna_globe, contentColor))
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            layoutParams =
                android.widget.LinearLayout.LayoutParams(context.dp(16), context.dp(16)).apply {
                    marginEnd = context.dp(Space.XS + 2)
                }
        }
    val text =
        TextView(context).apply {
            text = "$label ($count)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.LABEL_LARGE.size())
            typeface = Typeface.create(typeface, Typeface.BOLD)
            setTextColor(contentColor)
            letterSpacing = 0.02f
            includeFontPadding = false
        }
    return android.widget.LinearLayout(context).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(padH, padV, padH, padV)
        isClickable = true
        isFocusable = true
        background = ripple(roundedBg(containerColor, radiusPx), radiusPx, colors.onSurface)
        addView(icon)
        addView(text)
        setOnClickListener { onClick() }
    }
}
