package com.vinicius741.webnovelarchiver.ui

import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.max

internal fun View.installKeyboardAwareBottomPadding(scrollable: Boolean) {
    val root = this
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        // The app shell's shelf below the screen owns the gesture-bar inset, so only the part of the
        // keyboard that overlaps this screen needs padding: its height minus the gap between the
        // screen's bottom and the window's (the shelf and the bar under it).
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        val bottomInset = if (ime > 0 && view.height > 0) max(0, ime - view.gapToWindowBottom()) else 0
        if (ime > 0 && view.height == 0) view.post { ViewCompat.requestApplyInsets(view) }
        view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bottomInset)
        if (scrollable && insets.isVisible(WindowInsetsCompat.Type.ime())) {
            view.findFocus()?.post { view.findFocus()?.requestKeyboardVisibleRect() }
        }
        insets
    }
    if (scrollable) {
        val focusListener =
            ViewTreeObserver.OnGlobalFocusChangeListener { _, focused ->
                val insets = ViewCompat.getRootWindowInsets(root)
                if (focused != null && insets?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
                    focused.post { focused.requestKeyboardVisibleRect() }
                }
            }
        val observer = viewTreeObserver
        observer.addOnGlobalFocusChangeListener(focusListener)
        addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit

                override fun onViewDetachedFromWindow(v: View) {
                    val removalObserver = if (observer.isAlive) observer else v.viewTreeObserver
                    if (removalObserver.isAlive) {
                        removalObserver.removeOnGlobalFocusChangeListener(focusListener)
                    }
                }
            },
        )
    }
    ViewCompat.requestApplyInsets(this)
}

/** Distance from this view's bottom edge (ignoring its own bottom padding) to the window's bottom. */
private fun View.gapToWindowBottom(): Int {
    val location = IntArray(2)
    getLocationInWindow(location)
    return (rootView.height - (location[1] + height)).coerceAtLeast(0)
}

private fun View.requestKeyboardVisibleRect() {
    val padding = context.dp(Spacing.LG)
    requestRectangleOnScreen(Rect(0, -padding, width, height + padding), false)
}
