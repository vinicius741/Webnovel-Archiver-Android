package com.vinicius741.webnovelarchiver.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.setPadding

/* ------------------------------------------------------------------ */
/* Density + type-scale helpers                                       */
/* ------------------------------------------------------------------ */

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
fun Context.sp(value: Int): Float = value * resources.displayMetrics.scaledDensity

/** MD3 type scale tokens used across the app. */
enum class Type {
    HEADLINE, TITLE_LARGE, TITLE_MEDIUM, TITLE_SMALL,
    BODY_LARGE, BODY_MEDIUM, BODY_SMALL,
    LABEL_LARGE, LABEL_MEDIUM, LABEL_SMALL, CAPTION,
}

private fun Type.size(): Float = when (this) {
    Type.HEADLINE -> 24f
    Type.TITLE_LARGE -> 22f
    Type.TITLE_MEDIUM -> 16f
    Type.TITLE_SMALL -> 14f
    Type.BODY_LARGE -> 16f
    Type.BODY_MEDIUM -> 14f
    Type.BODY_SMALL -> 12f
    Type.LABEL_LARGE -> 14f
    Type.LABEL_MEDIUM -> 12f
    Type.LABEL_SMALL -> 11f
    Type.CAPTION -> 11f
}

private fun Type.bold(): Boolean = when (this) {
    Type.HEADLINE, Type.TITLE_LARGE, Type.TITLE_MEDIUM, Type.TITLE_SMALL,
    Type.LABEL_LARGE, Type.LABEL_MEDIUM -> true
    else -> false
}

/* ------------------------------------------------------------------ */
/* Drawable builders                                                  */
/* ------------------------------------------------------------------ */

/** Rounded solid background. `radii` is [lt, rt, rb, lb] in px. */
fun roundedBg(color: Int, radiiPx: FloatArray): GradientDrawable {
    val outer = floatArrayOf(
        radiiPx[0], radiiPx[0], radiiPx[1], radiiPx[1],
        radiiPx[2], radiiPx[2], radiiPx[3], radiiPx[3],
    )
    return GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadii = outer
        setColor(color)
    }
}

fun roundedBg(color: Int, radiusPx: Float): GradientDrawable =
    roundedBg(color, floatArrayOf(radiusPx, radiusPx, radiusPx, radiusPx))

fun strokeBg(fillColor: Int, radiusPx: Float, strokeColor: Int, strokePx: Int): GradientDrawable =
    (roundedBg(fillColor, radiusPx)).apply {
        setStroke(strokePx, strokeColor)
    }

/** Ripple clipped to a rounded-rect mask matching `radiusPx`. */
fun ripple(content: Drawable, radiusPx: Float, rippleColor: Int): RippleDrawable {
    val mask = roundedBg(Color.WHITE, radiusPx)
    return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
}

/** Borderless/selectable-row ripple (no mask, full-view ripple). */
fun selectableRipple(rippleColor: Int): Drawable {
    val mask = GradientDrawable().apply { setColor(Color.WHITE) }
    return RippleDrawable(ColorStateList.valueOf(rippleColor), null, mask)
}

/* ------------------------------------------------------------------ */
/* View styling extensions                                            */
/* ------------------------------------------------------------------ */

/** Clips a view (and its contents) to a rounded rectangle. API 21+. */
fun View.roundCorners(radiusDp: Float) {
    val density = resources.displayMetrics.density
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radiusDp * density)
        }
    }
    clipToOutline = true
}

fun View.elevate(dp: Float) {
    elevation = dp * resources.displayMetrics.density
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        outlineAmbientShadowColor = ThemeManager.current.shadowColor
        outlineSpotShadowColor = ThemeManager.current.shadowColor
    }
}

/** Load + tint a vector drawable, or null if `res` is 0. */
fun Context.tintedIcon(res: Int, color: Int): Drawable? {
    if (res == 0) return null
    val d = ContextCompat.getDrawable(this, res) ?: return null
    val m = DrawableCompat.wrap(d).mutate()
    DrawableCompat.setTint(m, color)
    return m
}

/* ------------------------------------------------------------------ */
/* Text                                                               */
/* ------------------------------------------------------------------ */

fun makeText(
    context: Context,
    text: CharSequence,
    type: Type,
    color: Int = ThemeManager.colors.onSurface,
): TextView = TextView(context).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, type.size())
    setTextColor(color)
    if (type.bold()) setTypeface(typeface, Typeface.BOLD)
    includeFontPadding = false
}

fun Context.text(
    text: CharSequence,
    type: Type,
    color: Int = ThemeManager.colors.onSurface,
) = makeText(this, text, type, color)

/* ------------------------------------------------------------------ */
/* Cards / surfaces                                                   */
/* ------------------------------------------------------------------ */

/**
 * A themed surface card. Uses the theme's elevation surface color, rounded
 * corners, and either a drop shadow (SHADOW styles) or a thin outline border
 * (Midnight / BORDER style).
 */
fun makeCard(context: Context, elevationLevel: Int = 1): LinearLayout {
    val t = ThemeManager.current
    val colors = t.colors
    val radiusPx = context.dp(t.shapes.cardRadius).toFloat()
    val surfaceColor = when (elevationLevel.coerceIn(0, 5)) {
        0 -> colors.surface
        1 -> colors.elevation1
        2 -> colors.elevation2
        3 -> colors.elevation3
        4 -> colors.elevation4
        else -> colors.elevation5
    }
    val pad = context.dp(16)
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad, pad, pad)
        background = when (t.shapes.elevationStyle) {
            ElevationStyle.BORDER -> strokeBg(surfaceColor, radiusPx, colors.outline, context.dp(1))
            else -> roundedBg(surfaceColor, radiusPx).also {
                if (elevationLevel > 0) elevate(elevationLevel.toFloat() + 1f)
            }
        }
        val gap = context.dp(8)
        showDividers = LinearLayout.SHOW_DIVIDER_NONE
        clipToPadding = false
        dividerPadding = gap
    }
}

/** Section header (e.g. "Appearance", "Downloads"). */
fun makeSectionHeader(context: Context, title: String): TextView =
    makeText(context, title, Type.LABEL_LARGE, ThemeManager.colors.primary).apply {
        setPadding(context.dp(2), context.dp(14), context.dp(2), context.dp(6))
        letterSpacing = 0.08f
    }

/** Thin divider line using the theme's outline-variant color. */
fun makeDivider(context: Context): View = View(context).apply {
    setBackgroundColor(ThemeManager.colors.outlineVariant)
    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(1)).apply {
        setMargins(0, context.dp(8), 0, context.dp(8))
    }
}

/* ------------------------------------------------------------------ */
/* Buttons                                                            */
/* ------------------------------------------------------------------ */

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
    val padH = context.dp(16)
    val padV = context.dp(9)

    fun bgWithRipple(content: Drawable, rippleColor: Int): Drawable = ripple(content, radiusPx, rippleColor)

    val (bgDrawable, fgColor, ripple) = when (v) {
        Btn.FILLED -> Triple(
            roundedBg(colors.primary, radiusPx),
            colors.onPrimary,
            colors.onPrimary,
        )
        Btn.TONAL -> Triple(
            roundedBg(colors.secondaryContainer, radiusPx),
            colors.onSecondaryContainer,
            colors.onSecondaryContainer,
        )
        Btn.ELEVATED -> {
            val d = roundedBg(colors.elevation2, radiusPx)
            Triple(d, colors.primary, colors.onSurface)
        }
        Btn.OUTLINED -> Triple(
            strokeBg(Color.TRANSPARENT, radiusPx, colors.outline, context.dp(1)),
            colors.primary,
            colors.primary,
        )
        Btn.TEXT -> Triple(
            roundedBg(Color.TRANSPARENT, radiusPx),
            colors.primary,
            colors.primary,
        )
        Btn.ERROR -> Triple(
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
        minHeight = 0
        minWidth = 0
        minimumHeight = 0
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

/* ------------------------------------------------------------------ */
/* Filter chips                                                       */
/* ------------------------------------------------------------------ */

fun makeChip(
    context: Context,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
): TextView {
    val t = ThemeManager.current
    val colors = t.colors
    val radiusPx = context.dp(t.shapes.chipRadius).toFloat()
    val padH = context.dp(14)
    val padV = context.dp(7)
    val (fill, fg) = if (selected) colors.secondaryContainer to colors.onSecondaryContainer
    else strokeBg(Color.TRANSPARENT, radiusPx, colors.outline, context.dp(1)).let { it to colors.onSurfaceVariant }

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
        background = ripple(
            if (selected) roundedBg(colors.secondaryContainer, radiusPx) else strokeBg(Color.TRANSPARENT, radiusPx, colors.outline, context.dp(1)),
            radiusPx,
            colors.onSurface,
        )
        setOnClickListener { onClick() }
    }
}

/* ------------------------------------------------------------------ */
/* Inputs                                                             */
/* ------------------------------------------------------------------ */

private fun EditText.applyInputStyle(hintText: String, inputType: Int) {
    val t = ThemeManager.current
    val colors = t.colors
    val radiusPx = context.dp(t.shapes.searchRadius).toFloat()
    setHintTextColor(colors.onSurfaceVariant)
    setTextColor(colors.onSurface)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.BODY_LARGE.size())
    this.inputType = inputType
    setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(12))
    includeFontPadding = false
    background = strokeBg(colors.surfaceVariant, radiusPx, colors.outline, context.dp(1))
    this.hint = hintText
    setSingleLine()
}

fun makeSearchField(context: Context, hint: String): EditText =
    EditText(context).apply { applyInputStyle(hint, InputType.TYPE_CLASS_TEXT) }

fun makeField(context: Context, value: String, hint: String, inputType: Int): EditText =
    EditText(context).apply {
        applyInputStyle(hint, inputType)
        setText(value)
    }

fun styledCheckBox(checkBox: CheckBox) {
    val colors = ThemeManager.colors
    checkBox.setTextColor(colors.onSurface)
    checkBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.BODY_MEDIUM.size())
    checkBox.setPadding(checkBox.context.dp(6), 0, 0, 0)
    checkBox.includeFontPadding = false
}

/* ------------------------------------------------------------------ */
/* Cover image + placeholder                                          */
/* ------------------------------------------------------------------ */

fun makeCover(context: Context, widthDp: Int, heightDp: Int): ImageView {
    val t = ThemeManager.current
    return ImageView(context).apply {
        contentDescription = "Cover"
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(t.colors.surfaceVariant)
        layoutParams = LinearLayout.LayoutParams(context.dp(widthDp), context.dp(heightDp)).apply {
            setMargins(0, 0, context.dp(14), 0)
        }
        roundCorners(t.shapes.cardRadius.toFloat() * 0.7f)
    }
}

fun makeCoverPlaceholder(context: Context, widthDp: Int, heightDp: Int): View {
    val t = ThemeManager.current
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(t.colors.surfaceVariant)
        layoutParams = LinearLayout.LayoutParams(context.dp(widthDp), context.dp(heightDp)).apply {
            setMargins(0, 0, context.dp(14), 0)
        }
        roundCorners(t.shapes.cardRadius.toFloat() * 0.7f)
        addView(ImageView(context).apply {
            setImageDrawable(context.tintedIcon(R.drawable.wna_book_open, t.colors.onSurfaceVariant))
            layoutParams = LinearLayout.LayoutParams(context.dp(28), context.dp(28))
        })
    }
}

/* ------------------------------------------------------------------ */
/* Progress bar (thin, rounded, themed)                               */
/* ------------------------------------------------------------------ */

fun makeProgress(context: Context, progress: Float, visible: Boolean = true): View {
    val t = ThemeManager.current
    val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        this.progress = (progress.coerceIn(0f, 1f) * 100).toInt()
        progressTintList = ColorStateList.valueOf(t.colors.primary)
        progressBackgroundTintList = ColorStateList.valueOf(t.colors.surfaceVariant)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(4))
        visibility = if (visible) View.VISIBLE else View.GONE
    }
    return bar
}

/* ------------------------------------------------------------------ */
/* Badges + pills                                                     */
/* ------------------------------------------------------------------ */

fun makeBadge(context: Context, text: String, bgColor: Int, fgColor: Int): TextView {
    val radius = context.dp(10).toFloat()
    return TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.LABEL_SMALL.size())
        typeface = Typeface.create(typeface, Typeface.BOLD)
        setTextColor(fgColor)
        setPadding(context.dp(8), context.dp(3), context.dp(8), context.dp(3))
        includeFontPadding = false
        background = roundedBg(bgColor, radius)
    }
}

fun makeStatPill(context: Context, label: String, value: String): View {
    val t = ThemeManager.current
    val radius = context.dp(t.shapes.chipRadius).toFloat()
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))
        background = roundedBg(t.colors.surfaceVariant, radius)
        addView(makeText(context, value, Type.TITLE_MEDIUM, t.colors.onSurface).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        addView(makeText(context, label, Type.LABEL_SMALL, t.colors.onSurfaceVariant).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 2, 0, 0)
        })
    }
}

/* ------------------------------------------------------------------ */
/* Empty state                                                        */
/* ------------------------------------------------------------------ */

fun makeEmptyState(context: Context, message: String, iconRes: Int = R.drawable.wna_book_open): View {
    val t = ThemeManager.current
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(context.dp(24), context.dp(48), context.dp(24), context.dp(48))
        addView(ImageView(context).apply {
            setImageDrawable(context.tintedIcon(iconRes, t.colors.outlineVariant))
            layoutParams = LinearLayout.LayoutParams(context.dp(56), context.dp(56)).apply {
                bottomMargin = context.dp(16)
            }
            alpha = 0.8f
        })
        addView(makeText(context, message, Type.BODY_LARGE, t.colors.onSurfaceVariant).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
    }
}

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
    override fun generateLayoutParams(attrs: android.util.AttributeSet?): LayoutParams = MarginLayoutParams(context, attrs)
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
    override fun generateLayoutParams(attrs: android.util.AttributeSet?): LayoutParams = MarginLayoutParams(context, attrs)
    override fun generateDefaultLayoutParams(): LayoutParams = MarginLayoutParams(WRAP_CONTENT, WRAP_CONTENT)
}

private const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
