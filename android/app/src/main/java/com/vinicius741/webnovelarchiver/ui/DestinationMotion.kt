package com.vinicius741.webnovelarchiver.ui

import android.view.View
import android.view.animation.PathInterpolator

// Motion for switching between the top-level destinations (navigation bar / rail taps).

internal object DestinationMotion {
    /** Material 3 "emphasized decelerate": quick start, long settle. */
    val emphasized = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)

    const val ENTER_MS = 300L

    /** How far the incoming screen starts from its resting place, toward the side it came from. */
    const val ENTER_SHIFT_DP = 48
}

/**
 * Fades this screen root in while it slides from the side of the destination it came from:
 * [direction] 1 (a later destination) enters from the end / bottom, -1 from the start / top, 0
 * fades in place. Runs on a hardware layer so the slide costs the main thread almost nothing.
 * Honors the system animation scale (off means it lands at once).
 */
internal fun View.playDestinationEnter(
    direction: Int,
    vertical: Boolean,
) {
    animate().cancel()
    val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
    val shift = context.dp(DestinationMotion.ENTER_SHIFT_DP).toFloat() * direction
    alpha = 0f
    if (vertical) {
        translationY = shift
    } else {
        translationX = if (rtl) -shift else shift
    }
    animate()
        .alpha(1f)
        .translationX(0f)
        .translationY(0f)
        .setDuration(DestinationMotion.ENTER_MS)
        .setInterpolator(DestinationMotion.emphasized)
        .withLayer()
        .start()
}
