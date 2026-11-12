package com.vinicius741.webnovelarchiver.ui

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar

/**
 * Turns off baseline alignment on every horizontal [LinearLayout] under this view whose direct
 * children cannot report a text baseline (containers, images, spacers). For those it changes
 * nothing on screen, but it is on by default and makes the row measure each weighted child an extra
 * time at unbounded width, just to find baselines. Nested rows multiply that: on Updates (row →
 * card → column) it made the first measure several times slower than the content needs.
 */
internal fun View.dropUnusedBaselineAlignment() {
    if (this !is ViewGroup) return
    if (this is LinearLayout && orientation == LinearLayout.HORIZONTAL && isBaselineAligned && !hasBaselineChild()) {
        isBaselineAligned = false
    }
    for (index in 0 until childCount) getChildAt(index).dropUnusedBaselineAlignment()
}

private fun LinearLayout.hasBaselineChild(): Boolean = (0 until childCount).any { getChildAt(it).mayReportBaseline() }

// Only view types known to answer -1 from getBaseline() count as baseline-free; anything else
// (text, buttons, chips, spinners, custom views) keeps the alignment.
private fun View.mayReportBaseline(): Boolean =
    when (this) {
        is LinearLayout -> baselineAlignedChildIndex >= 0
        is ImageView -> baseline >= 0 || baselineAlignBottom
        is FrameLayout, is ProgressBar, is android.widget.Space -> false
        else -> javaClass != View::class.java
    }
