package com.vinicius741.webnovelarchiver.tts

import android.content.Context
import android.content.Intent
import android.os.Build
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
                            when (keyEvent.keyCode) {
                                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                                    callbacks.onTogglePlayPause()
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
        session?.run {
            isActive = false
            release()
        }
        session = null
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
