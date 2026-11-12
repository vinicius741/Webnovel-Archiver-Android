package com.vinicius741.webnovelarchiver.feature.settings

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.SCREEN_SIDE_PADDING_DP
import com.vinicius741.webnovelarchiver.ui.SCROLL_BODY_VIEW_TAG
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.currentScreenLayout
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.layout.WidthClass
import com.vinicius741.webnovelarchiver.ui.layout.settingsMaxWidth
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.verticalFill

// Page-level layout for Settings: the large-screen width cap and the docked Save bar.

/** Centers a scrollable settings body at the settings width on large screens; no-op on phones. */
internal fun ScreenHost.capSettingsWidth(content: LinearLayout) {
    val widthClass = currentScreenLayout().widthClass
    if (widthClass == WidthClass.COMPACT) return
    content.layoutParams =
        FrameLayout.LayoutParams(
            content.context.dp(settingsMaxWidth(widthClass)),
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL,
        )
}

/** The docked Save bar: hidden until there is something to save, then pinned under the content. */
internal class DockedSaveBar(
    context: Context,
    label: String,
    onSave: () -> Unit,
) {
    val view: LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(ThemeManager.colors.background)
            visibility = View.GONE
            addView(
                View(context).apply {
                    setBackgroundColor(ThemeManager.colors.line)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1),
            )
            val button = makeButton(context, label, Btn.FILLED, R.drawable.wna_save) { onSave() }
            addView(
                button,
                LinearLayout
                    .LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply {
                        setMargins(
                            context.dp(SCREEN_SIDE_PADDING_DP),
                            context.dp(Spacing.SM + 2),
                            context.dp(SCREEN_SIDE_PADDING_DP),
                            context.dp(Spacing.SM + 2),
                        )
                    },
            )
        }

    fun show(visible: Boolean) {
        val target = if (visible) View.VISIBLE else View.GONE
        if (view.visibility == target) return
        view.visibility = target
        if (visible) {
            view.translationY = view.context.dp(BAR_SLIDE_DP).toFloat()
            view
                .animate()
                .translationY(0f)
                .setDuration(BAR_SLIDE_MS)
                .start()
        }
    }
}

/**
 * Builds a settings page whose scrolling body sits above a docked [bar] (call from a non-scrollable
 * `screen(fullWidthBody = true)` block). The bar never covers content: it takes its own space at
 * the bottom of the frame, which already ends above the player and navigation shelf.
 */
internal fun ScreenHost.scrollWithDockedBar(
    container: LinearLayout,
    bar: DockedSaveBar,
    build: LinearLayout.() -> Unit,
) {
    container.setPadding(0, 0, 0, 0)
    val side = container.context.dp(SCREEN_SIDE_PADDING_DP)
    val content =
        LinearLayout(container.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, context.dp(Spacing.MD), side, context.dp(Spacing.XL))
            build()
        }
    capSettingsWidth(content)
    val saved = routeMemory.scrollPositions[navigator.current.stableKey] ?: 0
    val scroller =
        ScrollView(container.context).apply {
            isFillViewport = true
            tag = SCROLL_BODY_VIEW_TAG
            clipToPadding = false
            addView(content)
            if (saved > 0) post { scrollTo(0, saved) }
        }
    container.addView(scroller, verticalFill())
    container.addView(bar.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
}

/** Dims a row (and its children) and stops it taking taps while [enabled] is false. */
internal fun View.setRowEnabled(enabled: Boolean) {
    isEnabled = enabled
    alpha = if (enabled) 1f else DISABLED_ALPHA
    if (this is ViewGroup) {
        for (i in 0 until childCount) getChildAt(i).setRowEnabled(enabled)
    }
}

private const val DISABLED_ALPHA = 0.4f
private const val BAR_SLIDE_DP = 24
private const val BAR_SLIDE_MS = 220L
