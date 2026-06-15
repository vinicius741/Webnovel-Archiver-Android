package com.vinicius741.webnovelarchiver.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat

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
