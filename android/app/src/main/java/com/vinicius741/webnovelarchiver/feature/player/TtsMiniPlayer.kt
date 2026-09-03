package com.vinicius741.webnovelarchiver.feature.player

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.TtsForegroundService
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackSnapshot
import com.vinicius741.webnovelarchiver.ui.FAB_VIEW_TAG
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.iconButton
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.systemBarBottom
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * The floating TTS bar: play/pause, chapter + story title, sentence progress. Built once per
 * activity; [applySnapshot] patches it in place from the engine's replaying state flow.
 */
private class TtsMiniPlayerBar(
    context: Context,
    onPlayPause: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    // Re-read on every snapshot: the bar is built before the startup theme hint applies, so the
    // construction-time palette can be stale by the time the bar first becomes visible.
    private var colors = ThemeManager.colors
    private val playPauseButton: ImageView
    private val chevronButton: ImageView
    private val chapterText: TextView
    private val storyText: TextView
    private val progressTrack: ProgressBar

    val view =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background =
                ripple(roundedBg(colors.elevation2, context.dp(Spacing.LG).toFloat()), context.dp(Spacing.LG).toFloat(), colors.onSurface)
            elevation = context.dp(6).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener { onOpenPlayer() }
        }

    init {
        val row =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(context.dp(Spacing.SM), context.dp(Spacing.XS + 2), context.dp(Spacing.SM), 0)
            }
        playPauseButton =
            context.iconButton(R.drawable.wna_play, "Play TTS", colors.primary, onClick = onPlayPause)
        row.addView(playPauseButton)

        val textColumn =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(Spacing.XS), context.dp(Spacing.XS), context.dp(Spacing.XS), context.dp(Spacing.XS))
            }
        chapterText =
            makeText(context, "", Type.BODY_MEDIUM, colors.onSurface).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        storyText =
            makeText(context, "", Type.BODY_SMALL, colors.onSurfaceVariant).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        textColumn.addView(chapterText)
        textColumn.addView(storyText)
        row.addView(textColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        chevronButton = context.iconButton(R.drawable.wna_chevron_down, "Open player", colors.onSurfaceVariant, onClick = onOpenPlayer)
        row.addView(chevronButton)
        view.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        progressTrack =
            ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                progressTintList =
                    android.content.res.ColorStateList
                        .valueOf(colors.primary)
                max = 0
                setPadding(context.dp(Spacing.SM), 0, context.dp(Spacing.SM), context.dp(Spacing.XS + 2))
            }
        view.addView(progressTrack, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(8)))
    }

    fun applySnapshot(snapshot: TtsPlaybackSnapshot?) {
        applyColors()
        val isPaused = snapshot?.isPaused != false
        playPauseButton.setImageDrawable(
            view.context.tintedIcon(if (isPaused) R.drawable.wna_play else R.drawable.wna_pause, colors.primary),
        )
        playPauseButton.contentDescription = if (isPaused) "Play TTS" else "Pause TTS"
        chapterText.text = snapshot?.title ?: ""
        storyText.text = snapshot?.storyTitle?.takeIf { it.isNotBlank() } ?: ""
        progressTrack.max = snapshot?.totalChunks ?: 0
        progressTrack.progress = ((snapshot?.chunkIndex ?: -1) + 1).coerceIn(0, progressTrack.max)
    }

    private fun applyColors() {
        colors = ThemeManager.colors
        val radius = view.context.dp(Spacing.LG).toFloat()
        view.background = ripple(roundedBg(colors.elevation2, radius), radius, colors.onSurface)
        chapterText.setTextColor(colors.onSurface)
        storyText.setTextColor(colors.onSurfaceVariant)
        chevronButton.setImageDrawable(view.context.tintedIcon(R.drawable.wna_chevron_down, colors.onSurfaceVariant))
        progressTrack.progressTintList =
            android.content.res.ColorStateList
                .valueOf(colors.primary)
    }
}

/**
 * App-level TTS mini-player: a floating bar pinned above every screen while a session is loaded,
 * so playback stays visible and controllable anywhere in the app (podcast behavior). Hidden on the
 * Reader route, which has its own transport + highlight.
 */
internal fun ScreenHost.attachTtsMiniPlayer(root: ViewGroup): Job {
    val bar =
        TtsMiniPlayerBar(
            context = app,
            onPlayPause = { TtsForegroundService.command(app, TtsForegroundService.ACTION_PLAY_PAUSE) },
            onOpenPlayer = { showPlayer() },
        )
    bar.applySnapshot(ttsEngine.playbackState.value.snapshot)
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

/** Keeps the current screen's FAB clear of the bar by lifting its bottom margin while visible. */
private fun liftScreenFabAboveBar(
    root: ViewGroup,
    frame: FrameLayout,
    bar: View,
    barVisible: Boolean,
) {
    root.post {
        val fab = frame.findViewWithTag<View>(FAB_VIEW_TAG) ?: return@post
        val lp = fab.layoutParams as? FrameLayout.LayoutParams ?: return@post
        val lift = if (barVisible) bar.height + fab.context.dp(Spacing.SM) else 0
        val target = fab.context.dp(Spacing.LG) + systemBarBottomPx(fab) + lift
        if (lp.bottomMargin != target) {
            lp.bottomMargin = target
            fab.layoutParams = lp
        }
    }
}

private fun systemBarBottomPx(view: View): Int {
    val res = view.context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
    return if (res > 0) view.context.resources.getDimensionPixelSize(res) else 0
}
