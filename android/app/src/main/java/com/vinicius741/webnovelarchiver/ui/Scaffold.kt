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
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.ai.AiCoverPlanning
import com.vinicius741.webnovelarchiver.data.repository.coverFile
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.feature.reader.detachReaderTtsListener
import com.vinicius741.webnovelarchiver.feature.story.showCoverDialog
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.platform.WebViewSafety

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

internal enum class ScreenChrome {
    STANDARD,
    IMMERSIVE,
}

@Suppress("LongParameterList") // Programmatic-View scaffold DSL; call sites use named arguments.
internal fun ScreenHost.screen(
    route: AppRoute,
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: List<AppBarAction> = emptyList(),
    fab: (() -> Unit)? = null,
    scrollable: Boolean = false,
    chrome: ScreenChrome = ScreenChrome.STANDARD,
    onSubtitleClick: (() -> Unit)? = null,
    block: LinearLayout.() -> Unit,
) {
    screenObserver?.cancel()
    screenObserver = null
    // The reader's TTS collector (highlight/auto-follow) must die with the reader screen: its
    // WebView is destroyed here, so updates past this point are dead writes — and auto-follow would
    // hijack the screen the user navigated to. showReader re-registers on the next reader build.
    if (route !is AppRoute.Reader) detachReaderTtsListener()
    navigator.navigate(route)
    val screenKey = navigator.current.stableKey
    val previousScreenKey = frame.tag as? String
    if (previousScreenKey != null) {
        findScrollView(frame)?.let { routeScrollPositions[previousScreenKey] = it.scrollY }
    }
    val savedScrollY = if (scrollable) routeScrollPositions[screenKey] ?: 0 else 0
    // Destroy any WebViews in the outgoing tree before removing it. WebViews are heavy and hold
    // activity references; without explicit destroy() they leak across navigation.
    WebViewSafety.disposeAll(frame)
    frame.removeAllViews()
    // Make the system back button mirror this screen's app-bar back arrow. `null` (root) disables
    // hardware/gesture back navigation so the OS default (exit) applies.
    val effectiveBack = if (onBack != null && navigator.canGoBack) ({ navigateBack() }) else onBack
    backHandler = effectiveBack
    val column =
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ThemeManager.colors.background)
            // Edge-to-edge window: reserve the gesture/navigation bar on the non-scrolling root so
            // content stays clear of it whether the body scrolls or not.
            setPadding(0, 0, 0, systemBarBottom())
        }
    if (chrome == ScreenChrome.STANDARD) column.addView(appBar(title, subtitle, effectiveBack, actions, onSubtitleClick))
    val content =
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            if (chrome == ScreenChrome.STANDARD) setPadding(dp(Spacing.XL), dp(Spacing.MD), dp(Spacing.XL), dp(Spacing.XL))
            block()
        }
    val body: View =
        if (scrollable) {
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
    column.installKeyboardAwareBottomPadding(scrollable)
    frame.tag = screenKey
    fab?.let { onClick ->
        val fabView = makeFab(app) { onClick() }
        val lp =
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END,
            )
        lp.setMargins(dp(Spacing.LG), dp(Spacing.LG), dp(Spacing.LG), dp(Spacing.LG) + systemBarBottom())
        fabView.tag = FAB_VIEW_TAG
        frame.addView(fabView, lp)
    }
    onScreenBuilt?.invoke()
}

/** Tags the screen FAB so the TTS mini-player can lift it out of the bar's way. */
internal const val FAB_VIEW_TAG = "wna_screen_fab"

/** Locates the first [ScrollView] anywhere under [root], so a re-render can capture the outgoing
 *  scroll position before the view tree is torn down. */
internal fun findScrollView(root: View): ScrollView? {
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
private fun ScreenHost.appBar(
    title: String,
    subtitle: String?,
    onBack: (() -> Unit)?,
    actions: List<AppBarAction>,
    onSubtitleClick: (() -> Unit)? = null,
): View {
    val t = ThemeManager.current
    return LinearLayout(app).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(t.colors.elevation2)
        // G2: symmetric edge gap (was dp(4) right-only) so the icon strip isn't flush with the edge.
        setPadding(dp(Spacing.SM), systemBarTop() + dp(Spacing.SM), dp(Spacing.SM), dp(Spacing.SM))
        if (onBack != null) {
            addView(
                app.iconButton(R.drawable.wna_arrow_back, "Back") { onBack() }.apply {
                    (layoutParams as LinearLayout.LayoutParams).marginStart = dp(Spacing.XS)
                },
            )
        } else {
            addView(Space(context).apply { layoutParams = LinearLayout.LayoutParams(dp(Spacing.MD), dp(1)) })
        }
        val titleCol =
            LinearLayout(app).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(Spacing.XS + 2), 0, dp(Spacing.SM), 0)
            }
        // Header-only clamp: source titles can be arbitrarily long (tag-stuffed RoyalRoad names
        // wrap 3+ lines and balloon the bar); cap at 2 lines with an end ellipsis. The full title
        // stays available on the story screen body.
        titleCol.addView(
            makeText(app, title, Type.TITLE_LARGE, t.colors.onSurface).apply {
                includeFontPadding = false
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
        )
        subtitle?.let {
            titleCol.addView(
                makeText(app, it, Type.BODY_SMALL, t.colors.onSurfaceVariant).apply {
                    includeFontPadding = false
                    setPadding(0, dp(2), 0, 0)
                    // The reader's "n / total · Polished" badge: tapping the subtitle flips the
                    // chapter's content version without leaving the reader.
                    onSubtitleClick?.let { click ->
                        isClickable = true
                        isFocusable = true
                        background = selectableRipple(t.colors.onSurfaceVariant)
                        setOnClickListener { click() }
                    }
                },
            )
        }
        addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.forEach { a ->
            addView(
                app.iconButton(a.icon, a.label, a.tint ?: t.colors.onSurface) { a.onClick() }.apply {
                    (layoutParams as LinearLayout.LayoutParams).marginStart = dp(Spacing.XS)
                },
            )
        }
    }
}

private fun makeFab(
    context: Context,
    onClick: () -> Unit,
): View {
    val t = ThemeManager.current
    val size = context.dp(56)
    return ImageView(context).apply {
        contentDescription = "Add"
        setImageDrawable(context.tintedIcon(R.drawable.wna_add, t.colors.onPrimary))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(context.dp(Spacing.XL - 8), context.dp(Spacing.XL - 8), context.dp(Spacing.XL - 8), context.dp(Spacing.XL - 8))
        background =
            ripple(roundedBg(t.colors.primary, context.dp(Spacing.LG).toFloat()), context.dp(Spacing.LG).toFloat(), t.colors.onPrimary)
        elevate(6f)
        setOnClickListener { onClick() }
        layoutParams = FrameLayout.LayoutParams(size, size)
    }
}

internal fun LinearLayout.centerLoading(message: String) {
    val col =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, context.dp(Spacing.XL + Spacing.XL - 8), 0, context.dp(Spacing.XL + Spacing.XL - 8))
        }
    col.addView(
        ProgressBar(context).apply {
            val lp = LinearLayout.LayoutParams(context.dp(40), context.dp(40))
            lp.bottomMargin = context.dp(Spacing.LG)
            layoutParams = lp
            indeterminateTintList = ColorStateList.valueOf(ThemeManager.colors.primary)
        },
    )
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
 * The cover the app should display right now: the locally generated AI cover when it is active
 * ([AiCoverPlanning.isAiCoverActive] — the user's AI/original preference) and its file is on disk,
 * else the source cover URL. Shared by every cover surface (cards, headers, zoom viewer) so the
 * preference reads identically everywhere.
 */
internal fun ScreenHost.activeCoverSource(story: Story): Any? =
    repository.coverFile(story)?.takeIf { AiCoverPlanning.isAiCoverActive(story) }
        ?: story.coverUrl?.takeIf { it.isNotBlank() }

/**
 * Builds a cover image (or placeholder). Returns the view without attaching it — callers
 * `addView` it into the current container, matching how `card {}` etc. behave.
 */
internal fun ScreenHost.coverImage(
    story: Story,
    widthDp: Int,
    heightDp: Int,
    tapToOpen: Boolean,
): View {
    val source: Any? = activeCoverSource(story)
    val coverView: View =
        if (source == null) {
            makeCoverPlaceholder(app, widthDp, heightDp)
        } else {
            makeCover(app, widthDp, heightDp)
        }
    if (source != null) {
        if (tapToOpen) coverView.setOnClickListener { showCoverDialog(story) }
        loadImage(source, coverView as ImageView)
    }
    return coverView
}
