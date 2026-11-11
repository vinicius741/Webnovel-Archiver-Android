package com.vinicius741.webnovelarchiver.feature.player

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.TtsForegroundService
import com.vinicius741.webnovelarchiver.ui.BOTTOM_BAR_VIEW_TAG
import com.vinicius741.webnovelarchiver.ui.FAB_VIEW_TAG
import com.vinicius741.webnovelarchiver.ui.SCROLL_BODY_VIEW_TAG
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.systemBarBottom
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * App-level TTS mini-player: a floating bar pinned above every screen while a session is loaded,
 * so playback stays visible and controllable anywhere in the app (podcast behavior). Hidden on the
 * Reader route, which docks its own copy of the bar (see [TtsMiniPlayerBar]) + highlight.
 */
internal fun ScreenHost.attachTtsMiniPlayer(root: ViewGroup): Job {
    val bar =
        TtsMiniPlayerBar(
            context = app,
            onPlayPause = { TtsForegroundService.command(app, TtsForegroundService.ACTION_PLAY_PAUSE) },
            onOpenPlayer = { showPlayer() },
            onClose = { TtsForegroundService.command(app, TtsForegroundService.ACTION_STOP) },
        )
    bar.applySnapshot(ttsEngine.playbackState.value.snapshot)
    bar.view.tag = TTS_MINI_PLAYER_TAG
    root.addView(
        bar.view,
        FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            marginStart = dp(Spacing.MD)
            marginEnd = dp(Spacing.MD)
            bottomMargin = dp(Spacing.SM) + systemBarBottom()
        },
    )

    bar.view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        if (bar.view.visibility == View.VISIBLE) {
            liftScreenFabAboveBar(root, frame, bar.view, true)
        }
    }

    var latest = ttsEngine.playbackState.value.snapshot
    var isCurrentlyVisible: Boolean? = null

    fun refresh(animate: Boolean = true) {
        val visible = latest != null && navigator.current !is AppRoute.Reader && navigator.current !is AppRoute.Player
        if (isCurrentlyVisible == visible) {
            // Screen rebuilds re-add the FAB with the default margin; re-lift even when visibility is unchanged.
            if (visible) liftScreenFabAboveBar(root, frame, bar.view, true)
            return
        }
        isCurrentlyVisible = visible

        bar.view.animate().cancel()
        if (visible) {
            bar.view.visibility = View.VISIBLE
            if (animate) {
                bar.view.alpha = 0f
                bar.view.translationY = dp(24).toFloat()
                bar.view
                    .animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(180L)
                    .start()
            } else {
                bar.view.alpha = 1f
                bar.view.translationY = 0f
            }
        } else {
            if (animate && bar.view.visibility == View.VISIBLE) {
                bar.view
                    .animate()
                    .alpha(0f)
                    .translationY(dp(24).toFloat())
                    .setDuration(150L)
                    .withEndAction {
                        bar.view.visibility = View.GONE
                        bar.view.alpha = 1f
                        bar.view.translationY = 0f
                    }.start()
            } else {
                bar.view.visibility = View.GONE
                bar.view.alpha = 1f
                bar.view.translationY = 0f
            }
        }
        liftScreenFabAboveBar(root, frame, bar.view, visible)
    }

    onScreenBuilt = { refresh(animate = false) }
    refresh(animate = false)
    return scope.launch {
        ttsEngine.playbackState.collect { update ->
            latest = update.snapshot
            bar.applySnapshot(update.snapshot)
            refresh(animate = true)
        }
    }
}

/** Lets other floating chrome (the AI job chip) stack above the bar. */
internal const val TTS_MINI_PLAYER_TAG = "wna_tts_mini_player"

/**
 * Keeps the current screen's FAB and bottom action bar clear of the bar by lifting their bottom
 * margins while it is visible, and pads a scrollable body so its last content can scroll clear.
 */
private fun liftScreenFabAboveBar(
    root: ViewGroup,
    frame: FrameLayout,
    bar: View,
    barVisible: Boolean,
) {
    root.post {
        val lift = if (barVisible) bar.height + frame.context.dp(Spacing.SM) else 0
        frame.findViewWithTag<View>(FAB_VIEW_TAG)?.let { fab ->
            setBottomMargin(fab, fab.context.dp(Spacing.LG) + systemBarBottomPx(fab) + lift)
        }
        // The screen column already reserves the system bar, so only the lift applies.
        frame.findViewWithTag<View>(BOTTOM_BAR_VIEW_TAG)?.let { setBottomMargin(it, lift) }
        frame.findViewWithTag<View>(SCROLL_BODY_VIEW_TAG)?.let { body ->
            if (body.paddingBottom != lift) body.setPadding(body.paddingLeft, body.paddingTop, body.paddingRight, lift)
        }
    }
}

private fun setBottomMargin(
    view: View,
    margin: Int,
) {
    val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
    if (lp.bottomMargin != margin) {
        lp.bottomMargin = margin
        view.layoutParams = lp
    }
}

private fun systemBarBottomPx(view: View): Int {
    val res = view.context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
    return if (res > 0) view.context.resources.getDimensionPixelSize(res) else 0
}
