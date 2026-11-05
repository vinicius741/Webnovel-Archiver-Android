package com.vinicius741.webnovelarchiver.tts

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.media.session.MediaButtonReceiver
import com.vinicius741.webnovelarchiver.R

/**
 * Encapsulates the [MediaSessionCompat] for TTS playback:
 * - Transport controls (play/pause/stop/skip)
 * - Headset and Bluetooth media button key events
 * - Media metadata (title, author, track number)
 * - Playback state synchronization
 */
@Suppress("DEPRECATION")
internal class TtsMediaSessionManager(
    private val context: Context,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onPlay()

        fun onPause()

        fun onStop()

        fun onSkipChapter(delta: Int)

        fun onSeekBy(deltaMs: Long)

        fun onSeekTo(positionMs: Long)

        fun onTogglePlayPause()

        /** Whether next/previous controls mean whole chapters rather than short skips. */
        fun chapterSkipButtons(): Boolean
    }

    private var session: MediaSessionCompat? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingTapRunnable: Runnable? = null
    private var burstTapCount = 0
    private var lastMetadataKey: List<Any?>? = null

    val sessionToken: MediaSessionCompat.Token?
        get() = session?.sessionToken

    fun handleMediaButton(intent: Intent?) {
        session?.let { MediaButtonReceiver.handleIntent(it, intent) }
    }

    fun ensureSession() {
        if (session != null) return
        session =
            MediaSessionCompat(context, TAG).apply {
                setFlags(
                    MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
                )
                setMediaButtonReceiver(
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        context,
                        PlaybackStateCompat.ACTION_PLAY_PAUSE,
                    ),
                )
                setCallback(
                    object : MediaSessionCompat.Callback() {
                        override fun onPlay() {
                            callbacks.onPlay()
                        }

                        override fun onPause() {
                            callbacks.onPause()
                        }

                        override fun onStop() {
                            callbacks.onStop()
                        }

                        override fun onSkipToNext() = perform(TtsHeadsetTapPlanning.forwardAction(callbacks.chapterSkipButtons()))

                        override fun onSkipToPrevious() = perform(TtsHeadsetTapPlanning.backAction(callbacks.chapterSkipButtons()))

                        override fun onFastForward() = perform(TtsHeadsetTapAction.SkipForward)

                        override fun onRewind() = perform(TtsHeadsetTapAction.SkipBack)

                        override fun onSeekTo(pos: Long) = callbacks.onSeekTo(pos)

                        override fun onCustomAction(
                            action: String?,
                            extras: android.os.Bundle?,
                        ) {
                            when (action) {
                                CUSTOM_SKIP_BACK -> perform(TtsHeadsetTapAction.SkipBack)
                                CUSTOM_SKIP_FORWARD -> perform(TtsHeadsetTapAction.SkipForward)
                            }
                        }

                        override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                            val keyEvent = mediaButtonEvent?.getKeyEventCompat() ?: return super.onMediaButtonEvent(mediaButtonEvent)
                            if (keyEvent.action != KeyEvent.ACTION_DOWN) return super.onMediaButtonEvent(mediaButtonEvent)
                            // A held button emits repeated ACTION_DOWN events. Count only the first
                            // event so a long press cannot masquerade as a multi-tap chapter skip.
                            if (!TtsHeadsetTapPlanning.shouldCountKeyDown(keyEvent.repeatCount)) return true
                            when (keyEvent.keyCode) {
                                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                                    registerToggleTap()
                                    return true
                                }
                                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                                    callbacks.onPlay()
                                    return true
                                }
                                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                    callbacks.onPause()
                                    return true
                                }
                                KeyEvent.KEYCODE_MEDIA_STOP -> {
                                    callbacks.onStop()
                                    return true
                                }
                                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                                    perform(TtsHeadsetTapPlanning.forwardAction(callbacks.chapterSkipButtons()))
                                    return true
                                }
                                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                                    perform(TtsHeadsetTapPlanning.backAction(callbacks.chapterSkipButtons()))
                                    return true
                                }
                                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                                    perform(TtsHeadsetTapAction.SkipForward)
                                    return true
                                }
                                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                                    perform(TtsHeadsetTapAction.SkipBack)
                                    return true
                                }
                            }
                            return super.onMediaButtonEvent(mediaButtonEvent)
                        }
                    },
                )
                isActive = true
            }
    }

    fun updatePlaybackState(snapshot: TtsPlaybackSnapshot?) {
        val state =
            when {
                snapshot == null -> PlaybackStateCompat.STATE_STOPPED
                snapshot.isPaused -> PlaybackStateCompat.STATE_PAUSED
                snapshot.isPlaying -> PlaybackStateCompat.STATE_PLAYING
                else -> PlaybackStateCompat.STATE_STOPPED
            }
        val chapterButtons = callbacks.chapterSkipButtons()
        var actions = (
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_REWIND or
                PlaybackStateCompat.ACTION_FAST_FORWARD or
                PlaybackStateCompat.ACTION_SEEK_TO
        )
        if (chapterButtons) {
            actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        }
        val builder =
            PlaybackStateCompat
                .Builder()
                .setActions(actions)
                .setState(
                    state,
                    snapshot?.positionMs ?: PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    snapshot?.rate ?: 1f,
                    snapshot?.capturedAtElapsedMs?.takeIf { it > 0L } ?: SystemClock.elapsedRealtime(),
                )
        // Without skip actions, system media controls give these two the previous and next slots.
        if (!chapterButtons) {
            builder.addCustomAction(CUSTOM_SKIP_BACK, context.getString(R.string.tts_action_skip_back), R.drawable.wna_replay)
            builder.addCustomAction(CUSTOM_SKIP_FORWARD, context.getString(R.string.tts_action_skip_forward), R.drawable.wna_forward_skip)
        }
        session?.setPlaybackState(builder.build())
    }

    fun updateMetadata(
        snapshot: TtsPlaybackSnapshot?,
        cover: Bitmap?,
    ) {
        // Metadata with artwork is costly to send, and most sentence changes alter none of it.
        val key =
            listOf(snapshot?.title, snapshot?.storyTitle, snapshot?.chapterIndex, snapshot?.durationMs?.div(DURATION_GRANULARITY_MS), cover)
        if (key == lastMetadataKey) return
        lastMetadataKey = key
        session?.setMetadata(
            MediaMetadataCompat
                .Builder()
                .apply {
                    putString(MediaMetadataCompat.METADATA_KEY_TITLE, snapshot?.title ?: context.getString(R.string.tts_notif_title))
                    putString(
                        MediaMetadataCompat.METADATA_KEY_ARTIST,
                        snapshot?.storyTitle?.takeIf { it.isNotBlank() } ?: context.getString(R.string.app_name),
                    )
                    putString(
                        MediaMetadataCompat.METADATA_KEY_ALBUM,
                        snapshot?.storyTitle?.takeIf { it.isNotBlank() } ?: context.getString(R.string.tts_metadata_reading),
                    )
                    if (snapshot != null && snapshot.durationMs > 0L) {
                        putLong(MediaMetadataCompat.METADATA_KEY_DURATION, snapshot.durationMs)
                    }
                    if (snapshot != null && snapshot.chapterIndex >= 0) {
                        putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, snapshot.chapterIndex + 1L)
                        putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, snapshot.chapterCount.toLong())
                    }
                    if (cover != null) putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, cover)
                }.build(),
        )
    }

    fun release() {
        pendingTapRunnable?.let(mainHandler::removeCallbacks)
        pendingTapRunnable = null
        burstTapCount = 0
        session?.run {
            isActive = false
            release()
        }
        session = null
    }

    /** 1 toggle / 2 next / 3 previous — the burst waits briefly for a possible extra tap. */
    private fun registerToggleTap() {
        burstTapCount++
        pendingTapRunnable?.let(mainHandler::removeCallbacks)
        if (burstTapCount >= 3) {
            dispatchTapBurst()
            return
        }
        val fire =
            Runnable {
                pendingTapRunnable = null
                dispatchTapBurst()
            }
        pendingTapRunnable = fire
        mainHandler.postDelayed(fire, TtsHeadsetTapPlanning.MULTI_TAP_WINDOW_MS)
    }

    private fun dispatchTapBurst() {
        val action = TtsHeadsetTapPlanning.actionForTapCount(burstTapCount, callbacks.chapterSkipButtons())
        burstTapCount = 0
        perform(action)
    }

    private fun perform(action: TtsHeadsetTapAction) {
        when (action) {
            TtsHeadsetTapAction.TogglePlayPause -> callbacks.onTogglePlayPause()
            TtsHeadsetTapAction.SkipForward -> callbacks.onSeekBy(TtsHeadsetTapPlanning.SKIP_FORWARD_MS)
            TtsHeadsetTapAction.SkipBack -> callbacks.onSeekBy(-TtsHeadsetTapPlanning.SKIP_BACK_MS)
            TtsHeadsetTapAction.NextChapter -> callbacks.onSkipChapter(1)
            TtsHeadsetTapAction.PreviousChapter -> callbacks.onSkipChapter(-1)
        }
    }

    private companion object {
        private const val TAG = "WNA-TtsMediaSession"
        private const val CUSTOM_SKIP_BACK = "wna.tts.SKIP_BACK"
        private const val CUSTOM_SKIP_FORWARD = "wna.tts.SKIP_FORWARD"
        private const val DURATION_GRANULARITY_MS = 5_000L
    }
}

private fun Intent.getKeyEventCompat(): KeyEvent? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(Intent.EXTRA_KEY_EVENT)
    }
