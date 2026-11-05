package com.vinicius741.webnovelarchiver.tts

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.ai.AiCoverPlanning
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.data.repository.coverFile
import com.vinicius741.webnovelarchiver.domain.model.TtsHistoryEntry
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
    private var repositoryReady = false
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var coverStoryId: String? = null
    private var coverBitmap: Bitmap? = null

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

                    override fun onSeekBy(deltaMs: Long) = engine.seekBy(deltaMs)

                    override fun onSeekTo(positionMs: Long) = engine.seekToMs(positionMs)

                    override fun onTogglePlayPause() = togglePlayPause()

                    override fun chapterSkipButtons(): Boolean = this@TtsForegroundService.chapterSkipButtons()
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
        // authoritative null tears the foreground state down (explicit stop, completion, idle
        // command) — but never the creation-time replay of a PREVIOUS instance's stop, which would
        // stopSelf this service before its first command runs and zombie the process-wide engine.
        val replayAtCreate = engine.playbackState.value
        stateCollectionJob =
            stateScope.launch {
                engine.playbackState.collect { update ->
                    val staleReplay = update === replayAtCreate
                    refreshMediaState(update.snapshot)
                    if (!staleReplay && TtsPlaybackState.serviceShouldStop(update)) {
                        stopForegroundAndReset()
                        stopSelf()
                    }
                }
            }
        stateScope.launch {
            runCatching { appContainer.awaitRepositoryReady() }.onSuccess {
                repositoryReady = true
                refreshMediaState(lastSnapshot)
            }
        }
        engine.addErrorListener(errorListener)
        // A timer that ran out decides for the listener: nothing may auto-resume after it.
        engine.onSleepTimerExpired = {
            resumeAfterFocusGain = false
            audioFocus.abandon()
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // Every start, not only a media button: with a restored session this service may be started
        // by any command, and the startForegroundService contract holds for all of them.
        startForegroundIfNeeded(buildNotification(lastSnapshot))
        if (Intent.ACTION_MEDIA_BUTTON == intent?.action) {
            mediaSessionManager.handleMediaButton(intent)
            return START_STICKY
        }
        when (intent?.action) {
            // The system restarting a killed service: it must not turn a paused book into speech.
            null -> engine.restoreAfterServiceRestart(::requestAudioFocusOrShowError)
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
                val chunkIndex = intent.getIntExtra(EXTRA_CHUNK_INDEX, -1)
                if (chunkIndex >= 0) {
                    engine.seekChunk(chunkIndex)
                    refreshMediaStateFromEngine()
                }
            }
            ACTION_SKIP_BACK, ACTION_SKIP_FORWARD, ACTION_SEEK_TO, ACTION_JUMP_TO_HISTORY -> handleTimeCommand(intent)
            ACTION_SET_RATE -> {
                intent
                    .getFloatExtra(TtsNotificationActions.EXTRA_RATE, Float.NaN)
                    .takeIf { !it.isNaN() }
                    ?.let(engine::setRate)
            }
            ACTION_SET_SLEEP_TIMER -> {
                val minutes = intent.getIntExtra(EXTRA_SLEEP_TIMER_MINUTES, 0)
                when {
                    minutes > 0 -> engine.setSleepTimerDuration(minutes)
                    minutes == -1 -> engine.setSleepTimerEndOfChapter()
                    else -> engine.cancelSleepTimer()
                }
            }
            ACTION_STOP -> {
                val forgetPosition = intent.getBooleanExtra(TtsNotificationActions.EXTRA_FORGET_POSITION, false)
                val storyId = intent.getStringExtra(EXTRA_STORY_ID)
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
        engine.onSleepTimerExpired = null
        audioFocus.abandon()
        mediaSessionManager.release()
        mediaButtonClaim.stop()
        setCpuAwake(false)
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
        engine.next()
        refreshMediaStateFromEngine()
    }

    private fun skipPrevious() {
        engine.previous()
        refreshMediaStateFromEngine()
    }

    private fun handleTimeCommand(intent: Intent) {
        when (intent.action) {
            ACTION_SKIP_BACK -> engine.seekBy(-TtsHeadsetTapPlanning.SKIP_BACK_MS)
            ACTION_SKIP_FORWARD -> engine.seekBy(TtsHeadsetTapPlanning.SKIP_FORWARD_MS)
            ACTION_SEEK_TO -> intent.getLongExtra(EXTRA_POSITION_MS, -1L).takeIf { it >= 0L }?.let(engine::seekToMs)
            ACTION_JUMP_TO_HISTORY -> historyEntry(intent)?.let(engine::jumpToHistory)
        }
    }

    private fun historyEntry(intent: Intent): TtsHistoryEntry? {
        val chapterId = intent.getStringExtra(EXTRA_CHAPTER_ID) ?: return null
        return TtsHistoryEntry(
            chapterId = chapterId,
            chunkIndex = intent.getIntExtra(EXTRA_CHUNK_INDEX, 0),
            chunkOffsetMs = intent.getLongExtra(EXTRA_POSITION_MS, 0L),
            anchorText = intent.getStringExtra(EXTRA_ANCHOR),
            chunkingVersion = intent.getIntExtra(EXTRA_CHUNKING_VERSION, 0),
        )
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
        loadCoverFor(snapshot?.storyId)
        mediaSessionManager.updateMetadata(snapshot, coverBitmap)
        updateNotification()
        // Rendered audio plays in this process, so this app is already the active player.
        mediaButtonClaim.setSpeaking(snapshot?.isPlaying == true && snapshot.isLiveOutput)
        setCpuAwake(snapshot?.isPlaying == true)
        noisyAudio.setActive(snapshot?.isPlaying == true)
    }

    /** This app now feeds the audio itself, so the CPU must stay up with the screen off while it plays. */
    @SuppressLint("WakelockTimeout")
    private fun setCpuAwake(awake: Boolean) {
        val lock =
            cpuWakeLock
                ?: getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "webnovelarchiver:tts")
                    .apply { setReferenceCounted(false) }
                    .also { cpuWakeLock = it }
        if (awake) {
            lock.acquire()
        } else if (lock.isHeld) {
            lock.release()
        }
    }

    /** The same cover the app shows: the active AI cover, else the source image Coil has cached. */
    private fun loadCoverFor(storyId: String?) {
        if (storyId == coverStoryId) return
        coverStoryId = storyId
        coverBitmap = null
        if (storyId == null) return
        stateScope.launch {
            val repository = appContainer.repository
            val story = repository.story(storyId) ?: return@launch
            val source: Any =
                repository.coverFile(story, checkExists = false)?.takeIf { AiCoverPlanning.isAiCoverActive(story) }
                    ?: story.coverUrl?.takeIf { it.isNotBlank() }
                    ?: return@launch
            val request =
                ImageRequest
                    .Builder(this@TtsForegroundService)
                    .data(source)
                    .size(COVER_SIZE_PX)
                    .allowHardware(false)
                    .build()
            val cover =
                runCatching {
                    SingletonImageLoader
                        .get(this@TtsForegroundService)
                        .execute(request)
                        .image
                        ?.toBitmap()
                }.getOrNull()
            if (coverStoryId == storyId && cover != null) {
                coverBitmap = cover
                refreshMediaState(lastSnapshot)
            }
        }
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

    /**
     * The default until the repository has loaded, which a restarted service runs ahead of. Settings
     * must not be read before then: the first read loads from disk and can block on startup's locks.
     */
    private fun chapterSkipButtons(): Boolean =
        repositoryReady &&
            appContainer.repository.ttsSettings
                .get()
                .chapterSkipButtons

    private fun buildNotification(snapshot: TtsPlaybackSnapshot?): Notification =
        notificationManager.buildNotification(
            snapshot,
            lastErrorText,
            mediaSessionManager.sessionToken,
            chapterSkipButtons(),
            coverBitmap,
        )

    companion object {
        private const val NOTIFICATION_ID = 1002
        private const val EXTRA_STORY_ID = "storyId"
        private const val EXTRA_CHAPTER_ID = "chapterId"
        private const val EXTRA_CHUNK_INDEX = "chunkIndex"
        private const val EXTRA_SLEEP_TIMER_MINUTES = "sleepTimerMinutes"
        private const val EXTRA_POSITION_MS = "positionMs"
        private const val EXTRA_ANCHOR = "anchor"
        private const val EXTRA_CHUNKING_VERSION = "chunkingVersion"
        private const val COVER_SIZE_PX = 320
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
        const val ACTION_SKIP_BACK = TtsNotificationActions.ACTION_SKIP_BACK
        const val ACTION_SKIP_FORWARD = TtsNotificationActions.ACTION_SKIP_FORWARD
        const val ACTION_SEEK_TO = "com.vinicius741.webnovelarchiver.action.TTS_SEEK_TO"
        const val ACTION_JUMP_TO_HISTORY = "com.vinicius741.webnovelarchiver.action.TTS_JUMP_TO_HISTORY"
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

        fun seekTo(
            context: Context,
            positionMs: Long,
        ) {
            startService(
                context,
                Intent(context, TtsForegroundService::class.java).setAction(ACTION_SEEK_TO).putExtra(EXTRA_POSITION_MS, positionMs),
            )
        }

        fun jumpToHistory(
            context: Context,
            entry: TtsHistoryEntry,
        ) {
            startService(
                context,
                Intent(context, TtsForegroundService::class.java)
                    .setAction(ACTION_JUMP_TO_HISTORY)
                    .putExtra(EXTRA_CHAPTER_ID, entry.chapterId)
                    .putExtra(EXTRA_CHUNK_INDEX, entry.chunkIndex)
                    .putExtra(EXTRA_POSITION_MS, entry.chunkOffsetMs)
                    .putExtra(EXTRA_ANCHOR, entry.anchorText)
                    .putExtra(EXTRA_CHUNKING_VERSION, entry.chunkingVersion),
            )
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
