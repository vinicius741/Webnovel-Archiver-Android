package com.vinicius741.webnovelarchiver.feature.details

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.dp

// Chapter-list leading status slot views, split out of [ChapterListAdapter] so the adapter file
// stays under its line budget. These build the fixed-width FrameLayout that hosts the status
// dot/spinner shared across chapter rows.

/**
 * Small indeterminate spinner sized to match the chapter status dot (10dp), used in place of the
 * dot for a chapter currently being fetched. Primary-tinted so it reads as "active work".
 */
internal fun chapterSpinner(context: Context): View {
    val t = ThemeManager.current
    val size = context.dp(16)
    return ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
        indeterminateTintList = ColorStateList.valueOf(t.colors.primary)
        layoutParams = FrameLayout.LayoutParams(size, size)
    }
}

/** Fixed-width leading slot shared by dots and spinners so status changes never move row text. */
internal fun chapterStatusSlot(
    context: Context,
    status: View,
): FrameLayout =
    FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(context.dp(18), context.dp(18))
        val isSpinner = status is ProgressBar
        addView(
            status,
            FrameLayout.LayoutParams(
                context.dp(if (isSpinner) 16 else 10),
                context.dp(if (isSpinner) 16 else 10),
                Gravity.START or Gravity.CENTER_VERTICAL,
            ),
        )
    }
