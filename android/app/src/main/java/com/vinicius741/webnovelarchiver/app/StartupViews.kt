package com.vinicius741.webnovelarchiver.app

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.window.layout.WindowMetricsCalculator
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.ui.GridLayout
import com.vinicius741.webnovelarchiver.ui.MaxWidthFrameLayout
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.layout.ScreenLayout
import com.vinicius741.webnovelarchiver.ui.layout.libraryMaxContentWidth
import com.vinicius741.webnovelarchiver.ui.layout.resolveScreenLayout
import com.vinicius741.webnovelarchiver.ui.makeCard
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.systemBarBottom
import com.vinicius741.webnovelarchiver.ui.systemBarTop
import timber.log.Timber

// Startup chrome shown on the root frame while (or after) repository hydration runs. The branded
// loading state replaces what QA 2026-08-27 F1 called "a bare centered ProgressBar": it paints the
// user's theme (from StartupThemeHint), names the app, says what it is doing, and lays out skeleton
// story cards in the same grid shape the Library will occupy, so the first paint shows content shape.

// Holds the platform splash screen over the window until the first real content frame: released as
// soon as repository startup finishes — fast cold starts go splash → first screen with no
// intermediate state — or after [StartupPlanning.SPLASH_HOLD_GRACE_MS], so slow hydrations fall
// through to the branded startup state below instead of staring at a static splash.
internal fun MainActivity.holdSplashScreenUntilFirstContent(isUiReady: () -> Boolean) {
    val content = findViewById<View>(android.R.id.content) ?: return
    val startedAt = SystemClock.uptimeMillis()
    var deadlineCheckScheduled = false
    content.viewTreeObserver.addOnPreDrawListener(
        object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val release =
                    StartupPlanning.shouldReleaseSplashHold(
                        elapsedMs = SystemClock.uptimeMillis() - startedAt,
                        uiReady = isUiReady(),
                    )
                if (release) {
                    content.viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }
                // Opening the gate is not enough on API 31+: the system only reveals the window
                // (and dismisses the splash) on the traversal AFTER the gate opens, and without
                // this self-scheduled invalidate that reveal waits for the next organic traversal
                // — the hydration-complete render — so the splash would cover the whole startup
                // no matter how long hydration takes.
                if (!deadlineCheckScheduled) {
                    deadlineCheckScheduled = true
                    content.postDelayed(
                        { content.invalidate() },
                        StartupPlanning.SPLASH_HOLD_GRACE_MS - (SystemClock.uptimeMillis() - startedAt),
                    )
                }
                return false
            }
        },
    )
}

internal fun MainActivity.showStartupLoading() {
    val ctx: Context = this
    val colors = ThemeManager.colors
    frame.removeAllViews()
    val root =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.background)
            setPadding(ctx.dp(Space.XL), systemBarTop() + ctx.dp(Space.LG), ctx.dp(Space.XL), systemBarBottom())
        }
    root.addView(
        makeStartupBranding(),
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        },
    )
    // The skeleton area must be the weighted child: an unweighted wrap-content grid taller than
    // the window (landscape, split-screen) measures first and starves the branding block to 0px.
    root.addView(
        makeStartupSkeletonArea(),
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
    )
    frame.addView(
        root,
        FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
    )
}

private fun MainActivity.makeStartupBranding(): LinearLayout {
    val ctx: Context = this
    val colors = ThemeManager.colors
    return LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(
            makeText(ctx, ctx.getString(R.string.startup_app_name), Type.HEADLINE, colors.onBackground).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            },
        )
        addView(
            makeText(ctx, ctx.getString(R.string.startup_loading_library), Type.BODY_LARGE, colors.onSurfaceVariant).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, ctx.dp(Space.XS), 0, 0)
            },
        )
        addView(
            ProgressBar(ctx).apply {
                indeterminateTintList = ColorStateList.valueOf(colors.primary)
                layoutParams =
                    LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(40)).apply {
                        topMargin = ctx.dp(Space.LG)
                        gravity = Gravity.CENTER_HORIZONTAL
                    }
            },
        )
    }
}

// Skeleton story cards laid out like the Library grid (same column rule, spacing, and content
// max-width) so the transition into the hydrated library reads as content filling in.
private fun MainActivity.makeStartupSkeletonArea(): View {
    val ctx: Context = this
    val columns = startupColumnCount()
    val grid =
        GridLayout(ctx).apply {
            columnCount = columns
            horizontalSpacingDp = Space.LG
            verticalSpacingDp = Space.XS
        }
    repeat(StartupPlanning.skeletonCardCount(columns)) { grid.addView(makeStartupSkeletonCard(ctx)) }
    val pulse =
        ValueAnimator.ofFloat(1f, 0.55f).apply {
            duration = 900L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animation -> grid.alpha = animation.animatedValue as Float }
        }
    grid.addOnAttachStateChangeListener(
        object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                pulse.start()
            }

            override fun onViewDetachedFromWindow(view: View) {
                pulse.cancel()
            }
        },
    )
    return MaxWidthFrameLayout(ctx).apply {
        maxContentWidthDp = libraryMaxContentWidth(columns)
        // Bottom-anchored: tall windows keep the skeleton flush at the screen bottom like before,
        // while short windows clip the grid's overflow instead of the branding block above it.
        addView(
            grid,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM,
            ),
        )
    }
}

// Deliberately resolves the layout from window metrics alone: this runs before repository
// hydration (no DisplayPreferences read) and before the FoldTracker exists, so the skeleton can
// only approximate the auto layout the hydrated screens will use.
private fun MainActivity.startupColumnCount(): Int {
    val bounds = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(this).bounds
    val density = resources.displayMetrics.density.coerceAtLeast(0.001f)
    val layout =
        resolveScreenLayout(
            ScreenLayout(
                widthDp = (bounds.width() / density).toInt().coerceAtLeast(0),
                heightDp = (bounds.height() / density).toInt().coerceAtLeast(0),
                hasFoldingFeature = false,
            ),
        )
    return layout.numColumns.coerceAtLeast(1)
}

// Mirrors buildStoryCard: 80×120 cover on the left, title/meta strips to its right, and the
// chapter-coverage bar below — all as inert surfaceVariant blocks.
private fun makeStartupSkeletonCard(context: Context): LinearLayout =
    makeCard(context).apply {
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(makeSkeletonStrip(context, widthDp = 80, heightDp = 120, radiusDp = 6))
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(makeSkeletonStrip(context, widthDp = 128, heightDp = 14))
                        addView(
                            makeSkeletonStrip(context, widthDp = 88, heightDp = 10, topMarginDp = Space.XS + 2),
                        )
                        addView(
                            makeSkeletonStrip(context, widthDp = 64, heightDp = 10, topMarginDp = Space.XS + 2),
                        )
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = context.dp(Space.LG)
                    },
                )
            },
        )
        addView(makeSkeletonStrip(context, widthDp = 0, heightDp = 8, topMarginDp = Space.MD))
    }

private fun makeSkeletonStrip(
    context: Context,
    widthDp: Int,
    heightDp: Int,
    topMarginDp: Int = 0,
    radiusDp: Int = 4,
): View =
    View(context).apply {
        background = roundedBg(ThemeManager.colors.surfaceVariant, context.dp(radiusDp).toFloat())
        layoutParams =
            LinearLayout
                .LayoutParams(
                    if (widthDp <= 0) LinearLayout.LayoutParams.MATCH_PARENT else context.dp(widthDp),
                    context.dp(heightDp),
                ).apply { topMargin = context.dp(topMarginDp) }
    }

internal fun MainActivity.showStartupFailure(error: Throwable) {
    Timber.e(error, "Repository startup failed")
    val ctx: Context = this
    val colors = ThemeManager.colors
    frame.removeAllViews()
    frame.addView(
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(colors.background)
            setPadding(ctx.dp(Space.XL), systemBarTop(), ctx.dp(Space.XL), systemBarBottom())
            addView(
                makeText(ctx, ctx.getString(R.string.startup_failed_title), Type.TITLE_MEDIUM, colors.error).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                },
            )
            addView(
                makeText(ctx, ctx.getString(R.string.startup_failed_message), Type.BODY_MEDIUM, colors.onSurfaceVariant).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, ctx.dp(Space.XS), 0, 0)
                },
            )
        },
        FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
    )
}
