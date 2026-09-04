package com.vinicius741.webnovelarchiver.tts

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.notification.AppNotificationChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class TtsForegroundService : Service() {
    private lateinit var engine: TtsEngine
    private lateinit var audioFocus: TtsAudioFocusManager
    private lateinit var notificationManager: TtsNotificationManager
    private lateinit var mediaSessionManager: TtsMediaSessionManager
    private val mediaButtonClaim = TtsMediaButtonClaim()
    private val noisyAudio =
        TtsNoisyAudioReceiver(this) {
            pausePlayback()
            refreshMediaStateFromEngine()
        }
    private var foregroundStarted = false
    private var resumeAfterFocusGain = false
    private var lastErrorText: String? = null
    private val stateScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateCollectionJob: Job? = null

    private var lastSnapshot: TtsPlaybackSnapshot? = null

    private val errorListener: (TtsPlaybackError) -> Unit = { error -> showPlaybackError(error) }

    override fun onCreate() {
        super.onCreate()
        // Process-wide shared engine — the same instance the activity's reader observes.
        engine = appContainer.ttsEngine
        notificationManager = TtsNotificationManager(this)
        mediaSessionManager =
            TtsMediaSessionManager(
                this,
                object : TtsMediaSessionManager.Callbacks {
                    override fun onPlay() = resumePlayback()

                    override fun onPause() {
                        pausePlayback()
                        refreshMediaStateFromEngine()
                    }

                    override fun onStop() = stopPlayback()

                    override fun onSkipChapter(delta: Int) = skipChapter(delta)

                    override fun onTogglePlayPause() = togglePlayPause()
                },
            )
        audioFocus =
            TtsAudioFocusManager(
                this,
                object : TtsAudioFocusManager.Callbacks {
                    override fun onFocusGained() {
                        if (!resumeAfterFocusGain) return
                        resumeAfterFocusGain = false
                        resumePlayback()
                    }

                    override fun onTransientFocusLoss() {
                        if (lastSnapshot?.isPlaying == true) {
                            resumeAfterFocusGain = true
                            pausePlayback(abandonFocus = false)
                        }
                    }

                    override fun onPermanentFocusLoss() {
                        // Podcast behavior: another app took the audio for good — pause and keep the
                        // session so the user can resume where they left off.
                        resumeAfterFocusGain = false
                        pausePlayback()
                        refreshMediaStateFromEngine()
                    }
                },
            )
        AppNotificationChannels.ensureCreated(this)
        mediaSessionManager.ensureSession()
        // One replaying state stream drives MediaSession + notification; no storage polling. An
        // authoritative null (explicit stop, natural completion, or a no-op command confirming an
        // idle engine) tears the foreground state down — otherwise a finished session or a stray
        // command leaves a zombie service behind. The non-authoritative startup replay must not.
        stateCollectionJob =
            stateScope.launch {
                engine.playbackState.collect { update ->
                    refreshMediaState(update.snapshot)
                    if (TtsPlaybackState.serviceShouldStop(update)) {
                        stopForegroundAndReset()
                        stopSelf()
                    }
                }
            }
        engine.addErrorListener(errorListener)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (Intent.ACTION_MEDIA_BUTTON == intent?.action) {
            // May arrive after the system restarted us for a media button: enter foreground
            // first (startForegroundService contract), then route the key to the session.
            startForegroundIfNeeded(buildNotification(lastSnapshot))
            mediaSessionManager.handleMediaButton(intent)
            return START_STICKY
        }
        when (intent?.action ?: ACTION_RESUME_SESSION) {
            ACTION_START -> startPlayback(intent)
            ACTION_RESUME_SESSION -> resumePlayback()
            ACTION_PAUSE -> {
                pausePlayback()
                refreshMediaStateFromEngine()
            }
            ACTION_PLAY_PAUSE -> togglePlayPause()
            ACTION_NEXT -> skipNext()
            ACTION_PREVIOUS -> skipPrevious()
            ACTION_NEXT_CHAPTER -> skipChapter(1)
            ACTION_PREVIOUS_CHAPTER -> skipChapter(-1)
            ACTION_SEEK_CHUNK -> {
                val chunkIndex = intent?.getIntExtra(EXTRA_CHUNK_INDEX, -1) ?: -1
                if (chunkIndex >= 0) {
                    engine.seekChunk(chunkIndex)
                    refreshMediaStateFromEngine()
                }
            }
            ACTION_SET_RATE -> {
                intent
                    ?.getFloatExtra(TtsNotificationActions.EXTRA_RATE, Float.NaN)
                    ?.takeIf { !it.isNaN() }
                    ?.let(engine::setRate)
            }
            ACTION_SET_SLEEP_TIMER -> {
                val minutes = intent?.getIntExtra(EXTRA_SLEEP_TIMER_MINUTES, 0) ?: 0
                when {
                    minutes > 0 -> engine.setSleepTimerDuration(minutes)
                    minutes == -1 -> engine.setSleepTimerEndOfChapter()
                    else -> engine.cancelSleepTimer()
                }
            }
            ACTION_STOP -> {
                val forgetPosition = intent?.getBooleanExtra(TtsNotificationActions.EXTRA_FORGET_POSITION, false) ?: false
                val storyId = intent?.getStringExtra(EXTRA_STORY_ID)
                stopPlayback(forgetPosition, storyId)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // The engine is process-wide and never shut down by a component; a system-killed service
        // must leave the persisted session intact so playback can resume next launch.
        stateCollectionJob?.cancel()
        stateCollectionJob = null
        stateScope.cancel()
        engine.removeErrorListener(errorListener)
        audioFocus.abandon()
        mediaSessionManager.release()
        mediaButtonClaim.stop()
        noisyAudio.unregister()
        super.onDestroy()
    }

    private fun startPlayback(intent: Intent?) {
        startForegroundIfNeeded(buildNotification(null))
        if (!requestAudioFocusOrShowError()) return
        val storyId = intent?.getStringExtra(EXTRA_STORY_ID)
        val chapterId = intent?.getStringExtra(EXTRA_CHAPTER_ID)
        val chunkIndex = intent?.takeIf { it.hasExtra(EXTRA_CHUNK_INDEX) }?.getIntExtra(EXTRA_CHUNK_INDEX, 0)
        if (storyId != null && chapterId != null) {
            // Null chunk index = resume wherever this story last stopped.
            engine.play(storyId, chapterId, chunkIndex)
        } else {
            refreshMediaStateFromEngine()
        }
    }

    private fun resumePlayback() {
        startForegroundIfNeeded(buildNotification(null))
        if (!requestAudioFocusOrShowError()) return
        engine.resumePersistedSession()
        // The engine only emits on real state changes; refresh regardless so buffering/no-session shows.
        refreshMediaStateFromEngine()
    }

    private fun togglePlayPause() {
        if (lastSnapshot?.isPaused != false) {
            resumePlayback()
        } else {
            pausePlayback()
            refreshMediaStateFromEngine()
        }
    }

    private fun pausePlayback(abandonFocus: Boolean = true) {
        engine.pause()
        if (abandonFocus) {
            resumeAfterFocusGain = false
            audioFocus.abandon()
        }
    }

    private fun stopPlayback(
        forgetPosition: Boolean = false,
        fallbackStoryId: String? = null,
    ) {
        engine.stop(forgetPosition, fallbackStoryId)
        resumeAfterFocusGain = false
        audioFocus.abandon()
        stopForegroundAndReset()
        stopSelf()
    }

    private fun skipNext() {
        if (!requestAudioFocusOrShowError()) return
        engine.next()
        refreshMediaStateFromEngine()
    }

    private fun skipPrevious() {
        if (!requestAudioFocusOrShowError()) return
        engine.previous()
        refreshMediaStateFromEngine()
    }

    /** Chapter skip (podcast episode semantics); stays paused if playback was paused. */
    private fun skipChapter(delta: Int) {
        engine.skipChapter(delta)
        refreshMediaStateFromEngine()
    }

    private fun requestAudioFocusOrShowError(): Boolean {
        if (audioFocus.request()) {
            lastErrorText = null
            return true
        }
        lastErrorText = getString(R.string.tts_error_audio_focus_denied)
        refreshMediaState(lastSnapshot)
        return false
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

    /** Also resets [foregroundStarted] so a later start re-enters foreground after a stop. */
    private fun stopForegroundAndReset() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
    }

    private fun refreshMediaState(snapshot: TtsPlaybackSnapshot?) {
        lastSnapshot = snapshot
        if (snapshot?.isPlaying == true) lastErrorText = null
        mediaSessionManager.updatePlaybackState(snapshot)
        mediaSessionManager.updateMetadata(snapshot)
        updateNotification()
        mediaButtonClaim.setSpeaking(snapshot?.isPlaying == true)
        noisyAudio.setActive(snapshot?.isPlaying == true)
    }

    private fun showPlaybackError(error: TtsPlaybackError) {
        lastErrorText = getString(TtsErrorPlanning.labelResId(error))
        updateNotification()
    }

    /** Rebuilds the snapshot from engine memory without decoding session JSON on the main thread. */
    private fun refreshMediaStateFromEngine() {
        refreshMediaState(engine.currentSnapshot(lastSnapshot?.isPlaying == true))
    }

    @SuppressLint("MissingPermission")
    private fun updateNotification() {
        if (!foregroundStarted) {
            startForegroundIfNeeded(buildNotification(lastSnapshot))
            return
        }
        // MediaSession notifications are exempt from POST_NOTIFICATIONS on 13+; the TTS channel still controls visibility.
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(lastSnapshot))
        }
    }

    private fun buildNotification(snapshot: TtsPlaybackSnapshot?): Notification =
        notificationManager.buildNotification(snapshot, lastErrorText, mediaSessionManager.sessionToken)

    companion object {
        private const val NOTIFICATION_ID = 1002
        private const val EXTRA_STORY_ID = "storyId"
        private const val EXTRA_CHAPTER_ID = "chapterId"
        private const val EXTRA_CHUNK_INDEX = "chunkIndex"
        private const val EXTRA_SLEEP_TIMER_MINUTES = "sleepTimerMinutes"
        const val ACTION_START = TtsNotificationActions.ACTION_START
        const val ACTION_RESUME_SESSION = TtsNotificationActions.ACTION_RESUME_SESSION
        const val ACTION_PAUSE = TtsNotificationActions.ACTION_PAUSE
        const val ACTION_PLAY_PAUSE = TtsNotificationActions.ACTION_PLAY_PAUSE
        const val ACTION_NEXT = TtsNotificationActions.ACTION_NEXT
        const val ACTION_PREVIOUS = TtsNotificationActions.ACTION_PREVIOUS
        const val ACTION_STOP = TtsNotificationActions.ACTION_STOP
        const val ACTION_NEXT_CHAPTER = TtsNotificationActions.ACTION_NEXT_CHAPTER
        const val ACTION_PREVIOUS_CHAPTER = TtsNotificationActions.ACTION_PREVIOUS_CHAPTER
        const val ACTION_SET_RATE = TtsNotificationActions.ACTION_SET_RATE
        const val ACTION_SEEK_CHUNK = "com.vinicius741.webnovelarchiver.action.TTS_SEEK_CHUNK"
        const val ACTION_SET_SLEEP_TIMER = "com.vinicius741.webnovelarchiver.action.TTS_SET_SLEEP_TIMER"

        /** Start (or resume) playback wherever this story last stopped. */
        fun start(
            context: Context,
            storyId: String,
            chapterId: String,
        ) {
            val intent =
                Intent(context, TtsForegroundService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_STORY_ID, storyId)
                    .putExtra(EXTRA_CHAPTER_ID, chapterId)
            startService(context, intent)
        }

        /** Start pinned to a chunk: the reader's tap-to-start-from-paragraph, restart-from-top. */
        fun startFromChunk(
            context: Context,
            storyId: String,
            chapterId: String,
            chunkIndex: Int,
        ) {
            val intent =
                Intent(context, TtsForegroundService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_STORY_ID, storyId)
                    .putExtra(EXTRA_CHAPTER_ID, chapterId)
                    .putExtra(EXTRA_CHUNK_INDEX, chunkIndex)
            startService(context, intent)
        }

        fun seekChunk(
            context: Context,
            chunkIndex: Int,
        ) {
            val intent =
                Intent(context, TtsForegroundService::class.java)
                    .setAction(ACTION_SEEK_CHUNK)
                    .putExtra(EXTRA_CHUNK_INDEX, chunkIndex)
            startService(context, intent)
        }

        fun setRate(
            context: Context,
            rate: Float,
        ) {
            startService(
                context,
                Intent(context, TtsForegroundService::class.java)
                    .setAction(TtsNotificationActions.ACTION_SET_RATE)
                    .putExtra(TtsNotificationActions.EXTRA_RATE, rate),
            )
        }

        fun setSleepTimer(
            context: Context,
            minutes: Int,
        ) {
            val intent =
                Intent(context, TtsForegroundService::class.java)
                    .setAction(ACTION_SET_SLEEP_TIMER)
                    .putExtra(EXTRA_SLEEP_TIMER_MINUTES, minutes)
            startService(context, intent)
        }

        fun command(
            context: Context,
            action: String,
        ) {
            startService(context, Intent(context, TtsForegroundService::class.java).setAction(action))
        }

        /** Stop that also forgets the story's saved position — chunk indices remap on variant switch. */
        fun stopForgettingPosition(
            context: Context,
            storyId: String? = null,
        ) {
            val intent =
                Intent(context, TtsForegroundService::class.java)
                    .setAction(ACTION_STOP)
                    .putExtra(TtsNotificationActions.EXTRA_FORGET_POSITION, true)
            storyId?.let { intent.putExtra(EXTRA_STORY_ID, it) }
            startService(context, intent)
        }

        private fun startService(
            context: Context,
            intent: Intent,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
