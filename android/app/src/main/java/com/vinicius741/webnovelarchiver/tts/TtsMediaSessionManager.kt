package com.vinicius741.webnovelarchiver.tts

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
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

        fun onTogglePlayPause()
    }

    private var session: MediaSessionCompat? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingTapRunnable: Runnable? = null
    private var burstTapCount = 0

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

                        override fun onSkipToNext() {
                            callbacks.onSkipChapter(1)
                        }

                        override fun onSkipToPrevious() {
                            callbacks.onSkipChapter(-1)
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
                                    callbacks.onSkipChapter(1)
                                    return true
                                }
                                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                                    callbacks.onSkipChapter(-1)
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
        val actions = (
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        )
        session?.setPlaybackState(
            PlaybackStateCompat
                .Builder()
                .setActions(actions)
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .build(),
        )
    }

    fun updateMetadata(snapshot: TtsPlaybackSnapshot?) {
        session?.setMetadata(
            MediaMetadataCompat
                .Builder()
                .apply {
                    putString(MediaMetadataCompat.METADATA_KEY_TITLE, snapshot?.title ?: context.getString(R.string.tts_notif_title))
                    putString(
                        MediaMetadataCompat.METADATA_KEY_ARTIST,
                        snapshot?.storyTitle?.takeIf { it.isNotBlank() } ?: context.getString(R.string.app_name),
                    )
                    putString(MediaMetadataCompat.METADATA_KEY_ALBUM, snapshot?.title ?: context.getString(R.string.tts_metadata_reading))
                    if (snapshot != null && snapshot.totalChunks > 0) {
                        putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, snapshot.chunkIndex.toLong() + 1L)
                        putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, snapshot.totalChunks.toLong())
                    }
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
        val action = TtsHeadsetTapPlanning.actionForTapCount(burstTapCount)
        burstTapCount = 0
        when (action) {
            TtsHeadsetTapAction.TogglePlayPause -> callbacks.onTogglePlayPause()
            TtsHeadsetTapAction.NextChapter -> callbacks.onSkipChapter(1)
            TtsHeadsetTapAction.PreviousChapter -> callbacks.onSkipChapter(-1)
        }
    }

    private companion object {
        private const val TAG = "WNA-TtsMediaSession"
    }
}

private fun Intent.getKeyEventCompat(): KeyEvent? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(Intent.EXTRA_KEY_EVENT)
    }
