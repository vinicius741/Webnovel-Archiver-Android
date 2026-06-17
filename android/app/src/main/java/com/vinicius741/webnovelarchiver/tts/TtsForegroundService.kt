package com.vinicius741.webnovelarchiver.tts

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import com.vinicius741.webnovelarchiver.MainActivity
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.appContainer
import com.vinicius741.webnovelarchiver.core.TtsEngine
import com.vinicius741.webnovelarchiver.core.TtsNotificationActions
import com.vinicius741.webnovelarchiver.core.TtsPlaybackSnapshot
import com.vinicius741.webnovelarchiver.core.TtsPlaybackState

class TtsForegroundService : Service() {
    private lateinit var engine: TtsEngine
    private var foregroundStarted = false
    /** Real Android MediaSession (parity gaps 1 & 2): owns the lock-screen / system media UI and
     *  receives headset-hook / Bluetooth media-button events. Its token drives the MediaStyle
     *  notification so the standard media card + transport metadata appear system-wide. */
    private var mediaSession: MediaSessionCompat? = null
    /** Last snapshot fanned out by the engine; drives notification + MediaSession refresh. */
    private var lastSnapshot: TtsPlaybackSnapshot? = null
    /** The service's own listener reference, kept so [onDestroy] can detach exactly this one. */
    private val stateListener: (TtsPlaybackSnapshot?) -> Unit = { snapshot -> refreshMediaState(snapshot) }

    override fun onCreate() {
        super.onCreate()
        // Process-wide shared engine (M2): the same instance the activity's reader observes, so a
        // playback the service drives fires the reader's highlight/transport listener too.
        engine = appContainer.ttsEngine
        createNotificationChannel()
        ensureMediaSession()
        // Single hook (R3): the engine emits a snapshot after every playback state change, so the
        // service refreshes the MediaSession + notification in one place instead of polling storage.
        engine.addStateListener(stateListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Gap 2: hardware media buttons (headset hook, Bluetooth) arrive as ACTION_MEDIA_BUTTON.
        // Hand them to the MediaSession's callback, which maps them onto engine play/pause/skip.
        if (Intent.ACTION_MEDIA_BUTTON == intent?.action) {
            MediaButtonReceiver.handleIntent(mediaSession, intent)
            return START_STICKY
        }
        when (intent?.action ?: ACTION_RESUME_SESSION) {
            ACTION_START -> startPlayback(intent)
            ACTION_RESUME_SESSION -> resumePlayback()
            ACTION_PAUSE -> {
                engine.pause()
                refreshMediaStateFromEngine()
            }
            ACTION_PLAY_PAUSE -> {
                if (lastSnapshot?.isPaused == true || lastSnapshot?.isPlaying != true) {
                    resumePlayback()
                } else {
                    engine.pause()
                    refreshMediaStateFromEngine()
                }
            }
            ACTION_NEXT -> {
                engine.next()
                refreshMediaStateFromEngine()
            }
            ACTION_PREVIOUS -> {
                engine.previous()
                refreshMediaStateFromEngine()
            }
            ACTION_STOP -> {
                engine.stop()
                stopForegroundAndReset()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Detach only this service's observer; the TTS engine itself is process-wide (shared with
        // the activity's reader) and is never shut down by a component. We intentionally do not
        // touch playback here: the ACTION_STOP / ACTION_PAUSE handlers already put the engine into
        // the right state before stopSelf(), and a system-killed service should leave the persisted
        // session intact so playback can resume next launch.
        engine.removeStateListener(stateListener)
        mediaSession?.run {
            isActive = false
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private fun startPlayback(intent: Intent?) {
        startForegroundIfNeeded(buildNotification(null))
        val storyId = intent?.getStringExtra(EXTRA_STORY_ID)
        val chapterId = intent?.getStringExtra(EXTRA_CHAPTER_ID)
        val chunkIndex = intent?.takeIf { it.hasExtra(EXTRA_CHUNK_INDEX) }?.getIntExtra(EXTRA_CHUNK_INDEX, 0)
        val story = storyId?.let { appContainer.storage.getStory(it) }
        val chapter = chapterId?.let { id -> story?.chapters?.firstOrNull { it.id == id } }
        if (story != null && chapter != null) {
            if (chunkIndex != null) {
                engine.playFromChunk(story, chapter, chunkIndex)
            } else {
                engine.play(story, chapter)
            }
        } else {
            refreshMediaStateFromEngine()
        }
    }

    private fun resumePlayback() {
        startForegroundIfNeeded(buildNotification(null))
        engine.resumePersistedSession()
        // The engine only emits state when it actually starts speaking; update the notification
        // surface regardless so "no session" / "buffering" is reflected immediately.
        refreshMediaStateFromEngine()
    }

    private fun startForegroundIfNeeded(notification: Notification) {
        if (foregroundStarted) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    /**
     * Tears down foreground state AND resets the [foregroundStarted] flag so a subsequent
     * [startForegroundIfNeeded] (e.g. a new ACTION_START in the same process) re-enters foreground
     * correctly. Without the reset the flag would stay `true` after a stop, making the next start
     * skip `startForeground()` — a latent bug that could prevent the service from re-establishing
     * foreground state.
     */
    private fun stopForegroundAndReset() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
    }

    /**
     * Single refresh entry point invoked from the engine's multicast state-listener hook
     * (see [TtsEngine.addStateListener]). Updates the cached snapshot, the MediaSession playback
     * state + metadata, and the notification.
     */
    private fun refreshMediaState(snapshot: TtsPlaybackSnapshot?) {
        lastSnapshot = snapshot
        updateMediaSessionPlaybackState()
        updateMediaSessionMetadata()
        updateNotification()
    }

    /** Rebuilds the snapshot from the persisted session (used by control actions that bypass the
     *  engine hook, e.g. PAUSE/NEXT arrive via the notification and the engine emits afterwards). */
    private fun refreshMediaStateFromEngine() {
        // Read the persisted session once (it's a file-backed JSON decode, so avoid the duplicate
        // read the previous form did).
        val session = appContainer.storage.getTtsSession()
        val snapshot = TtsPlaybackState.snapshotForSession(
            session = session,
            totalChunks = engine.currentChunkCount(),
            isPlaying = lastSnapshot?.isPlaying == true && session?.isPaused != true,
        )
        refreshMediaState(snapshot)
    }

    private fun updateNotification() {
        if (!foregroundStarted) {
            startForegroundIfNeeded(buildNotification(lastSnapshot))
            return
        }
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(lastSnapshot))
            }
        }
    }

    private fun buildNotification(snapshot: TtsPlaybackSnapshot?): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            11,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun serviceAction(requestCode: Int, action: String): PendingIntent =
            PendingIntent.getService(
                this,
                requestCode,
                Intent(this, TtsForegroundService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        val isPaused = snapshot?.isPaused == true
        val title = snapshot?.title ?: "Text to Speech"
        val body = snapshot
            ?.let { TtsPlaybackState.chunkProgress(it.chunkIndex, it.totalChunks) }
            ?: "TTS paused"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(openIntent)
            // Ongoing while ANY session exists (playing OR paused), so a paused-but-active session
            // keeps the foreground service + media notification alive. Drops to dismissable only once
            // playback stops and the snapshot becomes null.
            .setOngoing(snapshot != null)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        TtsNotificationActions.actions(isPaused).forEachIndexed { index, action ->
            builder.addAction(0, action.label, serviceAction(12 + index, action.action))
        }
        // Gap 1: MediaStyle ties the notification to the MediaSession so the system renders the
        // standard media card (lock screen + quick-settings shade) with the transport controls.
        builder.setStyle(
            MediaStyle()
                .setMediaSession(mediaSession?.sessionToken)
                .setShowActionsInCompactView(*TtsNotificationActions.COMPACT_ACTION_INDICES),
        )
        return builder.build()
    }

    private fun ensureMediaSession() {
        if (mediaSession != null) return
        mediaSession = MediaSessionCompat(this, TAG).apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            // Gap 2: a hardware media button press (headset hook / Bluetooth) is delivered to the
            // service as ACTION_MEDIA_BUTTON; the MediaButtonReceiver hands it to this pending
            // intent's session, whose callback (below) maps it to play/pause/skip.
            setMediaButtonReceiver(
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this@TtsForegroundService,
                    PlaybackStateCompat.ACTION_PLAY_PAUSE,
                ),
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    resumePlayback()
                }
                override fun onPause() {
                    engine.pause()
                    refreshMediaStateFromEngine()
                }
                override fun onStop() {
                    engine.stop()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                override fun onSkipToNext() {
                    engine.next()
                    refreshMediaStateFromEngine()
                }
                override fun onSkipToPrevious() {
                    engine.previous()
                    refreshMediaStateFromEngine()
                }
                override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                    val keyEvent = mediaButtonEvent?.getKeyEventCompat() ?: return super.onMediaButtonEvent(mediaButtonEvent)
                    if (keyEvent.action != KeyEvent.ACTION_DOWN) return super.onMediaButtonEvent(mediaButtonEvent)
                    when (keyEvent.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                            togglePlayPause(); return true
                        }
                        KeyEvent.KEYCODE_MEDIA_PLAY -> { resumePlayback(); return true }
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                            engine.pause(); refreshMediaStateFromEngine(); return true
                        }
                        KeyEvent.KEYCODE_MEDIA_NEXT -> {
                            engine.next(); refreshMediaStateFromEngine(); return true
                        }
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                            engine.previous(); refreshMediaStateFromEngine(); return true
                        }
                    }
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }
            })
            isActive = true
        }
        updateMediaSessionPlaybackState()
    }

    private fun togglePlayPause() {
        if (lastSnapshot?.isPaused == true || lastSnapshot?.isPlaying != true) resumePlayback()
        else { engine.pause(); refreshMediaStateFromEngine() }
    }

    private fun updateMediaSessionPlaybackState() {
        val snapshot = lastSnapshot
        val state = when {
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
        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .build(),
        )
    }

    private fun updateMediaSessionMetadata() {
        val snapshot = lastSnapshot
        mediaSession?.setMetadata(
            MediaMetadataCompat.Builder().apply {
                putString(MediaMetadataCompat.METADATA_KEY_TITLE, snapshot?.title ?: "Text to Speech")
                putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Webnovel Archiver")
                putString(MediaMetadataCompat.METADATA_KEY_ALBUM, snapshot?.title ?: "Reading")
                if (snapshot != null && snapshot.totalChunks > 0) {
                    putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, snapshot.chunkIndex.toLong() + 1L)
                    putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, snapshot.totalChunks.toLong())
                }
            }.build(),
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Text to Speech", NotificationManager.IMPORTANCE_LOW).apply {
            description = "TTS playback controls"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "WebnovelTts"
        private const val CHANNEL_ID = "webnovel_tts"
        private const val NOTIFICATION_ID = 1002
        private const val EXTRA_STORY_ID = "storyId"
        private const val EXTRA_CHAPTER_ID = "chapterId"
        private const val EXTRA_CHUNK_INDEX = "chunkIndex"
        const val ACTION_START = TtsNotificationActions.ACTION_START
        const val ACTION_RESUME_SESSION = TtsNotificationActions.ACTION_RESUME_SESSION
        const val ACTION_PAUSE = TtsNotificationActions.ACTION_PAUSE
        const val ACTION_PLAY_PAUSE = TtsNotificationActions.ACTION_PLAY_PAUSE
        const val ACTION_NEXT = TtsNotificationActions.ACTION_NEXT
        const val ACTION_PREVIOUS = TtsNotificationActions.ACTION_PREVIOUS
        const val ACTION_STOP = TtsNotificationActions.ACTION_STOP

        fun start(context: Context, storyId: String, chapterId: String, chunkIndex: Int? = null) {
            val intent = Intent(context, TtsForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_STORY_ID, storyId)
                .putExtra(EXTRA_CHAPTER_ID, chapterId)
            chunkIndex?.let { intent.putExtra(EXTRA_CHUNK_INDEX, it) }
            startService(context, intent)
        }

        fun command(context: Context, action: String) {
            startService(context, Intent(context, TtsForegroundService::class.java).setAction(action))
        }

        private fun startService(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

/** Extracts the KeyEvent from a MEDIA_BUTTON intent across API levels. */
private fun Intent.getKeyEventCompat(): KeyEvent? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(Intent.EXTRA_KEY_EVENT)
    }
