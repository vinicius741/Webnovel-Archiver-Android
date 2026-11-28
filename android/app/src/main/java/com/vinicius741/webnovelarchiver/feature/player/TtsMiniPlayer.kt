package com.vinicius741.webnovelarchiver.feature.player

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackSnapshot
import com.vinicius741.webnovelarchiver.ui.coverImage
import com.vinicius741.webnovelarchiver.ui.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * App-level TTS mini-player: docked in the app shell's bottom shelf above the navigation bar while a
 * session is loaded, so playback stays visible and controllable anywhere in the app (podcast
 * behavior). The shell's shelf sits below the screen frame, so the bar never covers content.
 * Hidden where the shell says the route does not want it (Reader, which docks its own
 * [TtsMiniPlayerBar], Now Playing and Listen; see `ShellNavigationPlanning`).
 *
 * App chrome, not a screen: the activity owns one, [attach]es it once and calls [onRouteRendered]
 * after every screen build. It follows the playback flow by itself.
 */
internal class TtsMiniPlayer(
    private val host: ScreenHost,
    private val controls: PlaybackControls = ServicePlaybackControls(host),
) {
    private val shell get() = host.shell
    private var bar: TtsMiniPlayerBar? = null
    private var latest: TtsPlaybackSnapshot? = null
    private var isCurrentlyVisible: Boolean? = null

    fun attach(slot: ViewGroup): Job {
        val app = host.app
        val playback = host.ttsEngine.playbackState
        val bar =
            TtsMiniPlayerBar(
                context = app,
                onPlayPause = controls::playPause,
                onOpenPlayer = { host.showPlayer() },
                onClose = controls::stop,
                coverFor = { storyId ->
                    host.repository.readOnlyStory(storyId)?.let { host.coverImage(it, COVER_WIDTH_DP, COVER_HEIGHT_DP, tapToOpen = false) }
                },
            )
        this.bar = bar
        latest = playback.value.snapshot
        bar.applySnapshot(latest)
        bar.view.tag = TTS_MINI_PLAYER_TAG
        bar.view.visibility = View.GONE
        slot.addView(
            bar.view,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT),
        )
        refresh(animate = false)
        return host.scope.launch {
            playback.collect { update ->
                latest = update.snapshot
                bar.applySnapshot(update.snapshot)
                refresh(animate = true)
            }
        }
    }

    /** A screen was built: the new route may show or hide the bar (without animating it). */
    fun onRouteRendered() = refresh(animate = false)

    private fun refresh(animate: Boolean) {
        val view = bar?.view ?: return
        val visible = latest != null && shell.miniPlayerAllowed
        if (isCurrentlyVisible == visible) {
            shell.onMiniPlayerVisibilityChanged()
            return
        }
        isCurrentlyVisible = visible
        view.animate().cancel()
        view.visibility = if (visible) View.VISIBLE else View.GONE
        view.alpha = 1f
        view.translationY = 0f
        if (visible && animate) {
            view.alpha = 0f
            view.translationY = view.context.dp(ENTER_OFFSET_DP).toFloat()
            view
                .animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(ENTER_MS)
                .start()
        }
        shell.onMiniPlayerVisibilityChanged()
    }
}

private const val ENTER_OFFSET_DP = 16
private const val ENTER_MS = 180L
private const val COVER_WIDTH_DP = 34
private const val COVER_HEIGHT_DP = 50

/** Lets other floating chrome find the bar. */
internal const val TTS_MINI_PLAYER_TAG = "wna_tts_mini_player"
