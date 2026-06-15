package com.vinicius741.webnovelarchiver.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R
import android.util.TypedValue

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
            setMargins(0, 0, context.dp(Space.MD + 2), 0)
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
            setMargins(0, 0, context.dp(Space.MD + 2), 0)
        }
        roundCorners(t.shapes.cardRadius.toFloat() * 0.7f)
        addView(ImageView(context).apply {
            setImageDrawable(context.tintedIcon(R.drawable.wna_book_open, t.colors.onSurfaceVariant))
            layoutParams = LinearLayout.LayoutParams(context.dp(Space.XL + 4), context.dp(Space.XL + 4))
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
    val radius = context.dp(Space.SM + 2).toFloat()
    return TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.LABEL_SMALL.size())
        typeface = Typeface.create(typeface, Typeface.BOLD)
        setTextColor(fgColor)
        setPadding(context.dp(Space.SM), context.dp(3), context.dp(Space.SM), context.dp(3))
        includeFontPadding = false
        background = roundedBg(bgColor, radius)
    }
}

fun makeStatPill(context: Context, label: String, value: String): View {
    val t = ThemeManager.current
    val radius = context.dp(t.shapes.chipRadius).toFloat()
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(Space.MD), context.dp(Space.SM), context.dp(Space.MD), context.dp(Space.SM))
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

/** Small rounded count chip (e.g. "3 failed") tinted by `fg`. Drops to zero-width when value is 0. */
fun makeCountChip(context: Context, label: String, value: Int, fg: Int): View {
    val t = ThemeManager.current
    val radius = context.dp(t.shapes.chipRadius).toFloat()
    return TextView(context).apply {
        text = "$value $label"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.LABEL_MEDIUM.size())
        typeface = Typeface.create(typeface, Typeface.BOLD)
        setTextColor(fg)
        setPadding(context.dp(Space.SM + 2), context.dp(Space.XS + 1), context.dp(Space.SM + 2), context.dp(Space.XS + 1))
        includeFontPadding = false
        background = roundedBg(t.colors.surfaceVariant, radius)
        visibility = if (value > 0) View.VISIBLE else View.GONE
    }
}

/** Compact "3 / 9 chapters" progress summary: a fractional value on the left, a thin bar on the right. */
fun makeProgressSummary(context: Context, done: Int, total: Int): View {
    val t = ThemeManager.current
    val ratio = if (total > 0) done.toFloat() / total else 0f
    return LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, 0)
        addView(TextView(context).apply {
            text = "$done / $total"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.LABEL_MEDIUM.size())
            typeface = Typeface.create(typeface, Typeface.BOLD)
            setTextColor(t.colors.onSurface)
            includeFontPadding = false
        })
        addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = (ratio.coerceIn(0f, 1f) * 100).toInt()
            progressTintList = ColorStateList.valueOf(t.colors.primary)
            progressBackgroundTintList = ColorStateList.valueOf(t.colors.surfaceVariant)
            layoutParams = LinearLayout.LayoutParams(0, context.dp(4), 1f).apply { marginStart = context.dp(Space.SM + 2) }
        })
    }
}
