package com.vinicius741.webnovelarchiver.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.ScreenHost
import com.vinicius741.webnovelarchiver.core.Story
import com.vinicius741.webnovelarchiver.showCoverDialog

internal data class AppBarAction(
    val icon: Int,
    val label: String,
    /** Optional icon tint. Defaults to [ThemeColors.onSurface] when null — pass `colors.primary` to
     *  signal an active/selected state (e.g. the reader's bookmarked chapter). Placed before
     *  [onClick] so existing `AppBarAction(icon, label) { … }` call sites keep their trailing
     *  lambda binding to [onClick]. */
    val tint: Int? = null,
    val onClick: () -> Unit,
)

internal fun ScreenHost.screen(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: List<AppBarAction> = emptyList(),
    fab: (() -> Unit)? = null,
    scrollable: Boolean = false,
    block: LinearLayout.() -> Unit,
) {
    // Capture the outgoing ScrollView's position before the tree is torn down, so a re-render of the
    // same screen (e.g. download-progress ticks, which re-run showDetails → screen(...)) doesn't snap
    // back to the top. scrollTo clamps to the valid range, so this is safe if the new content differs.
    val savedScrollY = if (scrollable) findScrollView(frame)?.scrollY ?: 0 else 0
    // R9: destroy any WebViews in the outgoing tree before removing it. WebViews are heavy and hold
    // activity references; without explicit destroy() they leak across navigation.
    disposeWebViews(frame)
    frame.removeAllViews()
    // Make the system back button mirror this screen's app-bar back arrow. `null` (root) disables
    // hardware/gesture back navigation so the OS default (exit) applies.
    backHandler = onBack
    val column = LinearLayout(app).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(ThemeManager.colors.background)
        // Edge-to-edge window: reserve the gesture/navigation bar on the non-scrolling root so
        // content stays clear of it whether the body scrolls or not.
        setPadding(0, 0, 0, systemBarBottom())
    }
    column.addView(appBar(title, subtitle, onBack, actions))
    val content = LinearLayout(app).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(Spacing.XL), dp(Spacing.MD), dp(Spacing.XL), dp(Spacing.XL))
        block()
    }
    val body: View = if (scrollable) {
        // Wrap the whole body in a single scroller so tall forms (Settings, Cleanup, Tabs) can
        // always be reached instead of being clipped by the fixed-weight content area.
        ScrollView(app).apply {
            isFillViewport = true
            addView(content)
            // Restore the scroll position captured before the re-render. `post` runs after this
            // ScrollView is attached and measured, so scrollTo sees the real scrollable range.
            if (savedScrollY > 0) post { scrollTo(0, savedScrollY) }
        }
    } else {
        content
    }
    column.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    frame.addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    fab?.let { onClick ->
        val fabView = makeFab(app) { onClick() }
        val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
        lp.setMargins(dp(Spacing.LG), dp(Spacing.LG), dp(Spacing.LG), dp(Spacing.LG) + systemBarBottom())
        frame.addView(fabView, lp)
    }
}

/** Locates the first [ScrollView] anywhere under [root], so a re-render can capture the outgoing
 *  scroll position before the view tree is torn down. */
private fun findScrollView(root: View): ScrollView? {
    if (root is ScrollView) return root
    if (root is ViewGroup) {
        for (i in 0 until root.childCount) {
            findScrollView(root.getChildAt(i))?.let { return it }
        }
    }
    return null
}

/**
 * Recursively stops loading, clears history/state, detaches, and destroys every [android.webkit.WebView]
 * in the [root] tree (R9). Called before `removeAllViews()` on navigation so Reader/Browser WebViews
 * don't outlive their screen and leak activity references, network work, or JS state.
 */
private fun disposeWebViews(root: View) {
    if (root is android.webkit.WebView) {
        WebViewSafety.destroy(root)
        return
    }
    if (root is ViewGroup) {
        // Iterate over a copy: destroy() mutates the child list.
        (0 until root.childCount).map { root.getChildAt(it) }.forEach { child -> disposeWebViews(child) }
    }
}

private fun ScreenHost.appBar(title: String, subtitle: String?, onBack: (() -> Unit)?, actions: List<AppBarAction>): View {
    val t = ThemeManager.current
    return LinearLayout(app).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(t.colors.elevation2)
        // G2: symmetric edge gap (was dp(4) right-only) so the icon strip isn't flush with the edge.
        setPadding(dp(Spacing.SM), systemBarTop() + dp(Spacing.SM), dp(Spacing.SM), dp(Spacing.SM))
        if (onBack != null) {
            addView(iconButton(R.drawable.wna_arrow_back, "Back") { onBack() })
        } else {
            addView(Space(context).apply { layoutParams = LinearLayout.LayoutParams(dp(Spacing.MD), dp(1)) })
        }
        val titleCol = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(Spacing.XS + 2), 0, dp(Spacing.SM), 0)
        }
        titleCol.addView(makeText(app, title, Type.TITLE_LARGE, t.colors.onSurface).apply { includeFontPadding = false })
        subtitle?.let {
            titleCol.addView(makeText(app, it, Type.BODY_SMALL, t.colors.onSurfaceVariant).apply {
                includeFontPadding = false
                setPadding(0, dp(2), 0, 0)
            })
        }
        addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.forEach { a ->
            addView(iconButton(a.icon, a.label, a.tint) { a.onClick() })
        }
    }
}

private fun ScreenHost.iconButton(iconRes: Int, desc: String, tint: Int? = null, onClick: () -> Unit): View {
    val t = ThemeManager.current
    val iconColor = tint ?: t.colors.onSurface
    val size = dp(44)
    return ImageView(app).apply {
        contentDescription = desc
        setImageDrawable(app.tintedIcon(iconRes, iconColor))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(Spacing.SM + 2), dp(Spacing.SM + 2), dp(Spacing.SM + 2), dp(Spacing.SM + 2))
        background = selectableRipple(t.colors.onSurface)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        // G2: widen the gap between adjacent app-bar icons so each reads as its own action,
        // not a single cluttered strip.
        layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = dp(Spacing.XS) }
    }
}

private fun makeFab(context: Context, onClick: () -> Unit): View {
    val t = ThemeManager.current
    val size = context.dp(56)
    return ImageView(context).apply {
        contentDescription = "Add"
        setImageDrawable(context.tintedIcon(R.drawable.wna_add, t.colors.onPrimary))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(context.dp(Spacing.XL - 8), context.dp(Spacing.XL - 8), context.dp(Spacing.XL - 8), context.dp(Spacing.XL - 8))
        background = ripple(roundedBg(t.colors.primary, context.dp(Spacing.LG).toFloat()), context.dp(Spacing.LG).toFloat(), t.colors.onPrimary)
        elevate(6f)
        setOnClickListener { onClick() }
        layoutParams = FrameLayout.LayoutParams(size, size)
    }
}

internal fun LinearLayout.centerLoading(message: String) {
    val col = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(0, context.dp(Spacing.XL + Spacing.XL - 8), 0, context.dp(Spacing.XL + Spacing.XL - 8))
    }
    col.addView(ProgressBar(context).apply {
        val lp = LinearLayout.LayoutParams(context.dp(40), context.dp(40))
        lp.bottomMargin = context.dp(Spacing.LG)
        layoutParams = lp
        indeterminateTintList = ColorStateList.valueOf(ThemeManager.colors.primary)
    })
    col.addView(makeText(context, message, Type.TITLE_MEDIUM, ThemeManager.colors.onSurface))
    addView(col)
}

internal fun ScreenHost.systemBarTop(): Int {
    val res = app.resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (res > 0) app.resources.getDimensionPixelSize(res) else dp(24)
}

internal fun ScreenHost.systemBarBottom(): Int {
    val res = app.resources.getIdentifier("navigation_bar_height", "dimen", "android")
    return if (res > 0) app.resources.getDimensionPixelSize(res) else 0
}

/**
 * Builds a cover image (or placeholder). Returns the view without attaching it — callers
 * `addView` it into the current container, matching how `card {}` etc. behave.
 */
internal fun ScreenHost.coverImage(story: Story, widthDp: Int, heightDp: Int, tapToOpen: Boolean): View {
    val url = story.coverUrl?.takeIf { it.isNotBlank() }
    val coverView: View = if (url == null) makeCoverPlaceholder(app, widthDp, heightDp)
    else makeCover(app, widthDp, heightDp)
    if (url != null) {
        if (tapToOpen) coverView.setOnClickListener { showCoverDialog(story) }
        loadImage(url, coverView as ImageView)
    }
    return coverView
}
