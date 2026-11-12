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
import com.vinicius741.webnovelarchiver.navigation.BackIntercept
import com.vinicius741.webnovelarchiver.navigation.ScreenBack
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

/** Gap between a screen's content and the display's sides. The app bar's title lines up with it. */
internal const val SCREEN_SIDE_PADDING_DP = Spacing.LG

internal enum class ScreenChrome {
    STANDARD,
    IMMERSIVE,
}

@Suppress("LongParameterList") // Programmatic-View scaffold DSL; call sites use named arguments.
internal fun ScreenHost.screen(
    route: AppRoute,
    title: String,
    subtitle: String? = null,
    actions: List<AppBarAction> = emptyList(),
    scrollable: Boolean = false,
    chrome: ScreenChrome = ScreenChrome.STANDARD,
    closeAction: (() -> Unit)? = null,
    backIntercept: BackIntercept? = null,
    // True when the body has content that must reach the display's sides. It then insets its other children itself.
    fullWidthBody: Boolean = false,
    block: LinearLayout.() -> Unit,
) {
    val perfBuildStartedNanos = if (com.vinicius741.webnovelarchiver.BuildConfig.DEBUG) System.nanoTime() else 0L
    aiControlsScreenState.binding = null
    screenObserver?.cancel()
    screenObserver = null
    // The reader's TTS collector (highlight/auto-follow) must die with the reader screen: its
    // WebView is destroyed here, so updates past this point are dead writes — and auto-follow would
    // hijack the screen the user navigated to. showReader re-registers on the next reader build.
    if (route !is AppRoute.Reader) detachReaderTtsListener()
    navigator.navigate(route)
    shell.onRoute(navigator.history)
    // A screen that did not set its own rerender inherits the previous screen's, which on a fold
    // would re-open that screen. Default to re-rendering this route instead.
    if (rerender === shell.rerenderAtLastScreen) {
        val current = navigator.current
        rerender = { renderRoute(current) }
    }
    shell.rerenderAtLastScreen = rerender
    val screenKey = navigator.current.stableKey
    captureScreenViewState()
    routeMemory.trackedList = null
    routeMemory.retainOnly(navigator.viewMemoryKeys)
    val savedScrollY = if (scrollable) routeMemory.scrollPositions[screenKey] ?: 0 else 0
    // Destroy any WebViews in the outgoing tree before removing it. WebViews are heavy and hold
    // activity references; without explicit destroy() they leak across navigation.
    WebViewSafety.disposeAll(frame)
    frame.removeAllViews()
    // Back always returns to the previous history entry (or this route's parent); only the Library
    // root has none. The transient Working screen holds Back until its operation finishes, since the
    // operation would otherwise yank the user back to it. [closeAction] is a modal exit (Library
    // multi-select) that wins for both the app-bar button (shown as a close icon) and system back.
    val navigateUp: (() -> Unit)? =
        when {
            route == AppRoute.Working -> ({ toast("Still working…") })
            navigator.canGoBack -> ({ navigateBack() })
            else -> null
        }
    screenBack = ScreenBack(close = closeAction, intercept = backIntercept, navigateUp = navigateUp)
    // The app-bar arrow navigates directly; only system Back clears in-screen state first.
    // Destinations reached from the navigation bar show no back arrow; system Back still returns.
    val appBarBack =
        closeAction ?: navigateUp?.takeIf {
            route != AppRoute.Working &&
                !com.vinicius741.webnovelarchiver.navigation.ShellNavigationPlanning
                    .isDestinationRoot(route)
        }
    val column =
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ThemeManager.colors.background)
            // The app shell's bottom shelf below the frame owns the gesture-bar inset.
        }
    if (chrome ==
        ScreenChrome.STANDARD
    ) {
        column.addView(
            appBar(
                title,
                subtitle,
                appBarBack,
                actions,
                if (closeAction !=
                    null
                ) {
                    R.drawable.wna_close
                } else {
                    R.drawable.wna_arrow_back
                },
            ),
        )
    }
    val content =
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            if (chrome == ScreenChrome.STANDARD) {
                val side = if (fullWidthBody) 0 else dp(SCREEN_SIDE_PADDING_DP)
                setPadding(side, dp(Spacing.MD), side, dp(Spacing.XL))
            }
            block()
            dropUnusedBaselineAlignment()
        }
    val body: View =
        if (scrollable) {
            // Wrap the whole body in a single scroller so tall forms (Settings, Cleanup, Tabs) can
            // always be reached instead of being clipped by the fixed-weight content area.
            ScrollView(app).apply {
                isFillViewport = true
                tag = SCROLL_BODY_VIEW_TAG
                clipToPadding = false
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
    onScreenBuilt?.invoke()
    if (com.vinicius741.webnovelarchiver.BuildConfig.DEBUG) {
        com.vinicius741.webnovelarchiver.perf.PerfInstrumentation.recordScreenBuilt(
            route = route.name,
            title = title,
            subtitle = subtitle,
            buildDurationNanos = System.nanoTime() - perfBuildStartedNanos,
        )
    }
}

/** Tags the app-bar title so a screen can retitle itself in place (see [setAppBarTitle]). */
private const val APP_BAR_TITLE_TAG = "wna_app_bar_title"

private const val APP_BAR_TITLE_START_DP = Spacing.XS + 2

/** Retitles the current screen's app bar without rebuilding it. */
internal fun ScreenHost.setAppBarTitle(title: String) {
    frame.findViewWithTag<android.widget.TextView>(APP_BAR_TITLE_TAG)?.text = title
}

/** Tags the app-bar subtitle (when the screen has one) so a screen can update it in place. */
internal const val APP_BAR_SUBTITLE_TAG = "wna_app_bar_subtitle"

/** Tags a bottom action bar (e.g. Library selection) so the TTS mini-player can lift it clear. */
internal const val BOTTOM_BAR_VIEW_TAG = "wna_screen_bottom_bar"

/** Tags a scrollable screen body so the TTS mini-player can pad its end clear of the bar. */
internal const val SCROLL_BODY_VIEW_TAG = "wna_screen_scroll_body"

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
    backIcon: Int,
): View =
    LinearLayout(app).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(ThemeManager.colors.elevation2)
        // G2: symmetric edge gap (was dp(4) right-only) so the icon strip isn't flush with the edge.
        setPadding(dp(Spacing.SM), systemBarTop() + dp(Spacing.SM), dp(Spacing.SM), dp(Spacing.SM))
        if (onBack != null) {
            addView(
                app.iconButton(backIcon, if (backIcon == R.drawable.wna_close) "Close" else "Back") { onBack() }.apply {
                    (layoutParams as LinearLayout.LayoutParams).marginStart = dp(Spacing.XS)
                },
            )
        } else {
            // With the bar's padding and the title column's, the title starts on the screen's side padding.
            val titleLead = SCREEN_SIDE_PADDING_DP - Spacing.SM - APP_BAR_TITLE_START_DP
            addView(Space(context).apply { layoutParams = LinearLayout.LayoutParams(dp(titleLead), dp(1)) })
        }
        val titleCol =
            LinearLayout(app).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(APP_BAR_TITLE_START_DP), 0, dp(Spacing.SM), 0)
            }
        // Header-only clamp: source titles can be arbitrarily long (tag-stuffed RoyalRoad names
        // wrap 3+ lines and balloon the bar); cap at 2 lines with an end ellipsis. The full title
        // stays available on the story screen body.
        titleCol.addView(
            makeText(app, title, Type.TITLE_LARGE, ThemeManager.colors.onSurface).apply {
                tag = APP_BAR_TITLE_TAG
                includeFontPadding = false
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
        )
        subtitle?.let {
            titleCol.addView(
                makeText(app, it, Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
                    tag = APP_BAR_SUBTITLE_TAG
                    includeFontPadding = false
                    setPadding(0, dp(2), 0, 0)
                },
            )
        }
        addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.forEach { a ->
            addView(
                app.iconButton(a.icon, a.label, a.tint ?: ThemeManager.colors.onSurface) { a.onClick() }.apply {
                    (layoutParams as LinearLayout.LayoutParams).marginStart = dp(Spacing.XS)
                },
            )
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
    repository.coverFile(story, checkExists = false)?.takeIf { AiCoverPlanning.isAiCoverActive(story) }
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
        loadImage(
            source,
            coverView as ImageView,
            fallbackOnError = if (source is java.io.File) story.coverUrl?.takeIf { it.isNotBlank() } else null,
        )
    }
    return coverView
}
