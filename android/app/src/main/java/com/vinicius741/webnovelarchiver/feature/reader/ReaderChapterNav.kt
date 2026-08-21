package com.vinicius741.webnovelarchiver.feature.reader

import android.content.Context
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.tintedIcon

// Slim docked chapter navigation: keep the controls thumb-reachable without taking a large
// bite out of the reading viewport.
internal fun readerChapterNav(
    context: Context,
    chapterCount: Int,
    currentIndex: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
): LinearLayout {
    fun dp(value: Int): Int = context.dp(value)

    val navBar =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(ThemeManager.colors.elevation2)
            setPadding(dp(Spacing.MD), dp(Spacing.XS), dp(Spacing.MD), dp(Spacing.XS))
        }
    val hasPrev = currentIndex > 0
    val hasNext = currentIndex < chapterCount - 1
    val progress =
        makeText(
            context,
            "${currentIndex + 1} / $chapterCount",
            Type.LABEL_MEDIUM,
            ThemeManager.colors.onSurfaceVariant,
        ).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

    fun navButton(
        desc: String,
        icon: Int,
        enabled: Boolean,
        action: () -> Unit,
    ) = ImageView(context).apply {
        contentDescription = desc
        val tint = if (enabled) ThemeManager.colors.primary else ThemeManager.colors.onSurfaceVariant
        setImageDrawable(context.tintedIcon(icon, tint))
        alpha = if (enabled) 1f else 0.38f
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val pad = dp(Spacing.SM)
        setPadding(pad, pad, pad, pad)
        background = selectableRipple(ThemeManager.colors.onSurface)
        isEnabled = enabled
        isClickable = enabled
        isFocusable = enabled
        if (enabled) setOnClickListener { action() }
        layoutParams =
            LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                marginStart = dp(Spacing.XS)
                marginEnd = dp(Spacing.XS)
            }
    }
    navBar.addView(navButton("Previous chapter", R.drawable.wna_skip_prev, hasPrev, onPrevious))
    navBar.addView(progress)
    navBar.addView(navButton("Next chapter", R.drawable.wna_skip_next, hasNext, onNext))
    return navBar
}
