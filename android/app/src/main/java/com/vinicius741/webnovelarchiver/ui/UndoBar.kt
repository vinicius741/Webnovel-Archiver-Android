package com.vinicius741.webnovelarchiver.ui

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * A snackbar-style bar over the whole activity (it outlives screen renders): [message] and an
 * Undo action. It hides itself after [durationMs]; [onUndo] runs only when Undo is tapped. A new
 * bar replaces the one on screen.
 */
internal fun Activity.showUndoBar(
    message: String,
    durationMs: Long,
    onUndo: () -> Unit,
) {
    val content = findViewById<FrameLayout>(android.R.id.content) ?: return
    content.findViewWithTag<View>(UNDO_BAR_TAG)?.let { content.removeView(it) }
    val colors = ThemeManager.colors
    val radius = dp(Spacing.MD).toFloat()
    lateinit var bar: LinearLayout

    fun hide() {
        if (bar.parent == null) return
        bar
            .animate()
            .alpha(0f)
            .translationY(dp(Spacing.SM).toFloat())
            .setDuration(HIDE_MS)
            .withEndAction { content.removeView(bar) }
            .start()
    }
    val undo =
        TextView(this).apply {
            text = "Undo"
            setTextColor(colors.primaryContainer)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = Type.LABEL_LARGE.size()
            gravity = Gravity.CENTER
            setPadding(dp(Spacing.MD), 0, dp(Spacing.MD), 0)
            minHeight = dp(MIN_TOUCH_DP)
            background = ripple(roundedBg(android.graphics.Color.TRANSPARENT, radius), radius, colors.primaryContainer)
            setOnClickListener {
                hide()
                onUndo()
            }
        }
    bar =
        LinearLayout(this).apply {
            tag = UNDO_BAR_TAG
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(colors.onSurface, radius)
            elevate(BAR_ELEVATION_DP)
            setPadding(dp(Spacing.LG), dp(Spacing.XS), dp(Spacing.XS), dp(Spacing.XS))
            addView(
                makeText(this@showUndoBar, message, Type.BODY_MEDIUM, colors.surface),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(undo)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
    val bottomInset =
        ViewCompat
            .getRootWindowInsets(content)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())
            ?.bottom ?: 0
    val side = dp(Spacing.MD)
    content.addView(
        bar,
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(side, 0, side, bottomInset + dp(BOTTOM_CLEARANCE_DP))
            width = minOf(content.width - 2 * side, dp(MAX_WIDTH_DP)).takeIf { it > 0 } ?: ViewGroup.LayoutParams.MATCH_PARENT
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        },
    )
    bar.alpha = 0f
    bar
        .animate()
        .alpha(1f)
        .setDuration(SHOW_MS)
        .start()
    bar.postDelayed({ hide() }, durationMs)
}

private const val UNDO_BAR_TAG = "undo_bar"
private const val SHOW_MS = 180L
private const val HIDE_MS = 160L
private const val MIN_TOUCH_DP = 44
private const val BAR_ELEVATION_DP = 6f

/** Clears the bottom navigation shelf and mini player. */
private const val BOTTOM_CLEARANCE_DP = 92
private const val MAX_WIDTH_DP = 560
