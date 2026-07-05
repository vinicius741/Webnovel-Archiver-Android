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
        val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        val bottomInset = max(systemBars.bottom, ime.bottom)
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

private fun View.requestKeyboardVisibleRect() {
    val padding = context.dp(Spacing.LG)
    requestRectangleOnScreen(Rect(0, -padding, width, height + padding), false)
}
