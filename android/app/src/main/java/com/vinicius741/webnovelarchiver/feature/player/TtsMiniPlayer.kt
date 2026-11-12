package com.vinicius741.webnovelarchiver.feature.player

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.TtsForegroundService
import com.vinicius741.webnovelarchiver.ui.coverImage
import com.vinicius741.webnovelarchiver.ui.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * App-level TTS mini-player: docked in the app shell's bottom shelf above the navigation bar while a
 * session is loaded, so playback stays visible and controllable anywhere in the app (podcast
 * behavior). The shell's shelf sits below the screen frame, so the bar never covers content.
 * Hidden on the Reader (which docks its own [TtsMiniPlayerBar]) and on Now Playing.
 */
internal fun ScreenHost.attachTtsMiniPlayer(slot: ViewGroup): Job {
    val bar =
        TtsMiniPlayerBar(
            context = app,
            onPlayPause = { TtsForegroundService.command(app, TtsForegroundService.ACTION_PLAY_PAUSE) },
            onOpenPlayer = { showPlayer() },
            onClose = { TtsForegroundService.command(app, TtsForegroundService.ACTION_STOP) },
            coverFor = { storyId -> repository.story(storyId)?.let { coverImage(it, COVER_WIDTH_DP, COVER_HEIGHT_DP, tapToOpen = false) } },
        )
    bar.applySnapshot(ttsEngine.playbackState.value.snapshot)
    bar.view.tag = TTS_MINI_PLAYER_TAG
    bar.view.visibility = View.GONE
    slot.addView(
        bar.view,
        FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT),
    )

    var latest = ttsEngine.playbackState.value.snapshot
    var isCurrentlyVisible: Boolean? = null

    fun refresh(animate: Boolean = true) {
        val visible = latest != null && shell.miniPlayerAllowed
        if (isCurrentlyVisible == visible) {
            shell.onMiniPlayerVisibilityChanged()
            return
        }
        isCurrentlyVisible = visible
        bar.view.animate().cancel()
        bar.view.visibility = if (visible) View.VISIBLE else View.GONE
        bar.view.alpha = 1f
        bar.view.translationY = 0f
        if (visible && animate) {
            bar.view.alpha = 0f
            bar.view.translationY = dp(16).toFloat()
            bar.view
                .animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(180L)
                .start()
        }
        shell.onMiniPlayerVisibilityChanged()
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

private const val COVER_WIDTH_DP = 34
private const val COVER_HEIGHT_DP = 50

/** Lets other floating chrome find the bar. */
internal const val TTS_MINI_PLAYER_TAG = "wna_tts_mini_player"
