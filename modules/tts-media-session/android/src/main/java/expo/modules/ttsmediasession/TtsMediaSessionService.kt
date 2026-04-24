package expo.modules.ttsmediasession

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

class TtsMediaSessionService : Service(), TextToSpeech.OnInitListener {
  private var mediaSession: MediaSessionCompat? = null
  private var notificationManager: NotificationManager? = null
  private var tts: TextToSpeech? = null
  private var mediaPlayer: MediaPlayer? = null

  private var units: List<String> = emptyList()
  private var title: String = "Reading"
  private var storyId: String? = null
  private var chapterId: String? = null
  private var currentIndex: Int = 0
  private var pitch: Float = 1.0f
  private var rate: Float = 1.0f
  private var voiceIdentifier: String? = null

  private var isTtsReady = false
  private var isPlaying = false
  private var isPaused = false
  private var pendingPlay = false
  private var activePlaybackGeneration = 0

  private val synthesizedFiles = ConcurrentHashMap<Int, File>()
  private val pendingSynthesisFiles = ConcurrentHashMap<Int, File>()
  private val synthesizingIndexes = ConcurrentHashMap.newKeySet<Int>()

  override fun onCreate() {
    super.onCreate()
    notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    createNotificationChannel()
    ensureMediaSession()
    tts = TextToSpeech(applicationContext, this)
  }

  override fun onInit(status: Int) {
    if (status != TextToSpeech.SUCCESS) {
      Log.e(TAG, "TextToSpeech initialization failed: $status")
      emitState("error")
      return
    }

    isTtsReady = true
    configureTts()
    if (pendingPlay) {
      pendingPlay = false
      playCurrent()
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (Intent.ACTION_MEDIA_BUTTON == intent?.action) {
      MediaButtonReceiver.handleIntent(mediaSession, intent)
      return START_STICKY
    }

    when (intent?.action) {
      ACTION_START_PLAYBACK -> handleStartPlayback(intent)
      ACTION_PAUSE -> pausePlayback()
      ACTION_RESUME -> resumePlayback()
      ACTION_PLAY_PAUSE -> playPause()
      ACTION_NEXT -> skipTo(currentIndex + 1)
      ACTION_PREVIOUS -> skipTo(max(0, currentIndex - 1))
      ACTION_STOP -> stopPlayback(removeNotification = true)
      ACTION_SEEK_TO_UNIT -> skipTo(intent.getIntExtra(EXTRA_START_INDEX, currentIndex))
    }

    return START_STICKY
  }

  override fun onDestroy() {
    super.onDestroy()
    releasePlayer()
    tts?.stop()
    tts?.shutdown()
    tts = null
    mediaSession?.isActive = false
    mediaSession?.release()
    mediaSession = null
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun handleStartPlayback(intent: Intent) {
    val incomingUnits = intent.getStringArrayListExtra(EXTRA_UNITS)?.filter { it.isNotBlank() }.orEmpty()
    if (incomingUnits.isEmpty()) {
      stopPlayback(removeNotification = true)
      return
    }

    stopPlayback(removeNotification = false, clearUnits = false)
    units = incomingUnits
    title = intent.getStringExtra(EXTRA_TITLE) ?: "Reading"
    storyId = intent.getStringExtra(EXTRA_STORY_ID)
    chapterId = intent.getStringExtra(EXTRA_CHAPTER_ID)
    currentIndex = intent.getIntExtra(EXTRA_START_INDEX, 0).coerceIn(0, units.lastIndex)
    pitch = intent.getFloatExtra(EXTRA_PITCH, 1.0f).coerceIn(0.1f, 2.0f)
    rate = intent.getFloatExtra(EXTRA_RATE, 1.0f).coerceIn(0.1f, 2.0f)
    voiceIdentifier = intent.getStringExtra(EXTRA_VOICE_IDENTIFIER)
    isPlaying = true
    isPaused = false
    pendingPlay = false
    activePlaybackGeneration++
    synthesizedFiles.clear()
    pendingSynthesisFiles.clear()
    synthesizingIndexes.clear()
    configureTts()
    startAsForeground()
    emitState("buffering")

    if (isTtsReady) {
      playCurrent()
    } else {
      pendingPlay = true
    }
  }

  private fun configureTts() {
    val engine = tts ?: return
    engine.setPitch(pitch)
    engine.setSpeechRate(rate)
    engine.setAudioAttributes(
      AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    )
    voiceIdentifier?.let { identifier ->
      val matchingVoice = engine.voices?.firstOrNull { it.name == identifier }
      if (matchingVoice != null) {
        engine.voice = matchingVoice
      }
    }
    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
      override fun onStart(utteranceId: String?) = Unit

      override fun onDone(utteranceId: String?) {
        val index = parseUtteranceIndex(utteranceId) ?: return
        synthesizingIndexes.remove(index)
        pendingSynthesisFiles.remove(index)?.let { file ->
          if (file.exists()) {
            synthesizedFiles[index] = file
          }
        }
        if (index == currentIndex && isPlaying && !isPaused) {
          runOnMain { playCurrent() }
        }
      }

      @Deprecated("Deprecated in Java")
      override fun onError(utteranceId: String?) {
        onError(utteranceId, TextToSpeech.ERROR)
      }

      override fun onError(utteranceId: String?, errorCode: Int) {
        val index = parseUtteranceIndex(utteranceId)
        if (index != null) {
          synthesizingIndexes.remove(index)
          pendingSynthesisFiles.remove(index)
        }
        Log.e(TAG, "TTS synthesis failed for $utteranceId: $errorCode")
        if (index == currentIndex) {
          emitState("error")
          skipTo(currentIndex + 1)
        }
      }
    })
  }

  private fun playCurrent() {
    if (!isTtsReady || units.isEmpty() || currentIndex !in units.indices) return
    prefetchFrom(currentIndex)

    val audioFile = synthesizedFiles[currentIndex]
    if (audioFile == null || !audioFile.exists()) {
      emitState("buffering")
      return
    }

    val generation = ++activePlaybackGeneration
    releasePlayer()
    isPlaying = true
    isPaused = false

    mediaPlayer = MediaPlayer().apply {
      setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_MEDIA)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .build()
      )
      setDataSource(audioFile.absolutePath)
      setOnPreparedListener {
        if (generation != activePlaybackGeneration) return@setOnPreparedListener
        it.start()
        updatePlaybackState()
        updateNotification()
        emitState("playing")
      }
      setOnCompletionListener {
        if (generation != activePlaybackGeneration) return@setOnCompletionListener
        handleUnitComplete()
      }
      setOnErrorListener { _, what, extra ->
        Log.e(TAG, "MediaPlayer error: $what / $extra")
        emitState("error")
        skipTo(currentIndex + 1)
        true
      }
      prepareAsync()
    }
  }

  private fun handleUnitComplete() {
    if (currentIndex >= units.lastIndex) {
      emitState("completed")
      stopPlayback(removeNotification = true)
      return
    }
    currentIndex += 1
    emitState("playing")
    playCurrent()
  }

  private fun pausePlayback() {
    if (!isPlaying || isPaused) return
    isPaused = true
    mediaPlayer?.pause()
    updatePlaybackState()
    updateNotification()
    emitState("paused")
  }

  private fun resumePlayback() {
    if (units.isEmpty()) return
    if (!isPaused) {
      playCurrent()
      return
    }
    isPaused = false
    isPlaying = true
    val player = mediaPlayer
    if (player != null) {
      player.start()
      updatePlaybackState()
      updateNotification()
      emitState("playing")
    } else {
      playCurrent()
    }
  }

  private fun playPause() {
    if (isPaused || !isPlaying) {
      resumePlayback()
    } else {
      pausePlayback()
    }
  }

  private fun skipTo(index: Int) {
    if (units.isEmpty()) return
    currentIndex = index.coerceIn(0, units.lastIndex)
    activePlaybackGeneration++
    releasePlayer()
    isPlaying = true
    isPaused = false
    emitState("buffering")
    playCurrent()
  }

  private fun stopPlayback(
    removeNotification: Boolean,
    clearUnits: Boolean = true,
  ) {
    pendingPlay = false
    activePlaybackGeneration++
    releasePlayer()
    isPlaying = false
    isPaused = false
    if (clearUnits) {
      units = emptyList()
      currentIndex = 0
      synthesizedFiles.clear()
      pendingSynthesisFiles.clear()
      synthesizingIndexes.clear()
    }
    tts?.stop()
    updatePlaybackState()
    emitState("stopped")
    if (removeNotification) {
      stopForegroundService()
    }
  }

  private fun prefetchFrom(startIndex: Int) {
    if (!isTtsReady) return
    val endIndex = min(units.lastIndex, startIndex + PREFETCH_AHEAD_COUNT)
    for (index in startIndex..endIndex) {
      synthesizeIndex(index)
    }
  }

  private fun synthesizeIndex(index: Int) {
    if (index !in units.indices) return
    if (synthesizedFiles[index]?.exists() == true) return
    if (!synthesizingIndexes.add(index)) return

    val text = units[index].take(TextToSpeech.getMaxSpeechInputLength())
    val file = File(cacheDir, "tts_${System.currentTimeMillis()}_${index}.wav")
    pendingSynthesisFiles[index] = file
    val params = Bundle().apply {
      putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, buildUtteranceId(index))
    }
    val result = tts?.synthesizeToFile(text, params, file, buildUtteranceId(index))
    if (result != TextToSpeech.SUCCESS) {
      pendingSynthesisFiles.remove(index)
      synthesizingIndexes.remove(index)
      Log.e(TAG, "synthesizeToFile rejected index $index: $result")
      if (index == currentIndex) emitState("error")
    }
  }

  private fun releasePlayer() {
    try {
      mediaPlayer?.setOnCompletionListener(null)
      mediaPlayer?.setOnPreparedListener(null)
      mediaPlayer?.setOnErrorListener(null)
      mediaPlayer?.release()
    } catch (error: Exception) {
      Log.w(TAG, "Failed to release MediaPlayer: ${error.message}")
    } finally {
      mediaPlayer = null
    }
  }

  private fun ensureMediaSession() {
    if (mediaSession != null) return

    mediaSession = MediaSessionCompat(this, TAG).apply {
      setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
      setMediaButtonReceiver(
        MediaButtonReceiver.buildMediaButtonPendingIntent(
          this@TtsMediaSessionService,
          PlaybackStateCompat.ACTION_PLAY_PAUSE
        )
      )
      setCallback(object : MediaSessionCompat.Callback() {
        override fun onPlay() = resumePlayback()
        override fun onPause() = pausePlayback()
        override fun onStop() = stopPlayback(removeNotification = true)
        override fun onSkipToNext() = skipTo(currentIndex + 1)
        override fun onSkipToPrevious() = skipTo(currentIndex - 1)

        override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
          val keyEvent: KeyEvent? = if (Build.VERSION.SDK_INT >= 33) {
            mediaButtonEvent?.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
          } else {
            @Suppress("DEPRECATION")
            mediaButtonEvent?.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
          }
          if (keyEvent?.action == KeyEvent.ACTION_DOWN) {
            when (keyEvent.keyCode) {
              KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
              KeyEvent.KEYCODE_HEADSETHOOK -> {
                playPause()
                return true
              }
              KeyEvent.KEYCODE_MEDIA_PLAY -> {
                resumePlayback()
                return true
              }
              KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                pausePlayback()
                return true
              }
              KeyEvent.KEYCODE_MEDIA_NEXT -> {
                skipTo(currentIndex + 1)
                return true
              }
              KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                skipTo(currentIndex - 1)
                return true
              }
            }
          }
          return super.onMediaButtonEvent(mediaButtonEvent)
        }
      })
      isActive = true
    }

    updatePlaybackState()
  }

  private fun updatePlaybackState() {
    val state = when {
      isPlaying && !isPaused -> PlaybackStateCompat.STATE_PLAYING
      isPaused -> PlaybackStateCompat.STATE_PAUSED
      else -> PlaybackStateCompat.STATE_STOPPED
    }
    val actions = PlaybackStateCompat.ACTION_PLAY_PAUSE or
      PlaybackStateCompat.ACTION_PLAY or
      PlaybackStateCompat.ACTION_PAUSE or
      PlaybackStateCompat.ACTION_STOP or
      PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
      PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS

    mediaSession?.setPlaybackState(
      PlaybackStateCompat.Builder()
        .setActions(actions)
        .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
        .build()
    )
    mediaSession?.setMetadata(
      MediaMetadataCompat.Builder()
        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Webnovel Archiver")
        .build()
    )
  }

  private fun buildNotification(): Notification {
    ensureMediaSession()
    val playPauseAction = NotificationCompat.Action(
      if (isPlaying && !isPaused) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
      if (isPlaying && !isPaused) "Pause" else "Play",
      servicePendingIntent(ACTION_PLAY_PAUSE, 10)
    )
    val previousAction = NotificationCompat.Action(
      android.R.drawable.ic_media_previous,
      "Previous",
      servicePendingIntent(ACTION_PREVIOUS, 11)
    )
    val nextAction = NotificationCompat.Action(
      android.R.drawable.ic_media_next,
      "Next",
      servicePendingIntent(ACTION_NEXT, 12)
    )
    val stopAction = NotificationCompat.Action(
      android.R.drawable.ic_menu_close_clear_cancel,
      "Stop",
      servicePendingIntent(ACTION_STOP, 13)
    )
    val style = MediaStyle()
      .setMediaSession(mediaSession?.sessionToken)
      .setShowActionsInCompactView(0, 1, 2)

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle(title)
      .setContentText(notificationBody())
      .setSmallIcon(android.R.drawable.ic_media_play)
      .addAction(previousAction)
      .addAction(playPauseAction)
      .addAction(nextAction)
      .addAction(stopAction)
      .setContentIntent(buildContentIntent())
      .setStyle(style)
      .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setOnlyAlertOnce(true)
      .setOngoing(isPlaying)
      .build()
  }

  private fun startAsForeground() {
    val notification = buildNotification()
    if (Build.VERSION.SDK_INT >= 29) {
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  private fun updateNotification() {
    if (units.isEmpty()) return
    notificationManager?.notify(NOTIFICATION_ID, buildNotification())
  }

  private fun stopForegroundService() {
    try {
      if (Build.VERSION.SDK_INT >= 24) {
        stopForeground(STOP_FOREGROUND_REMOVE)
      } else {
        @Suppress("DEPRECATION")
        stopForeground(true)
      }
    } catch (error: Exception) {
      Log.w(TAG, "Failed to stop foreground service: ${error.message}")
    }
    stopSelf()
  }

  private fun buildContentIntent(): PendingIntent {
    val encodedChapter = chapterId?.let { Uri.encode(it) }
    val targetUri = if (!storyId.isNullOrBlank() && !encodedChapter.isNullOrBlank()) {
      "webnovel-archiver://reader/${storyId}/${encodedChapter}?resumeSession=true"
    } else {
      "webnovel-archiver://"
    }
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUri)).apply {
      setPackage(packageName)
      addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
    return PendingIntent.getActivity(this, 0, intent, pendingIntentFlags())
  }

  private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
    val intent = Intent(this, TtsMediaSessionService::class.java).apply {
      this.action = action
    }
    return PendingIntent.getService(this, requestCode, intent, pendingIntentFlags())
  }

  private fun pendingIntentFlags(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    } else {
      PendingIntent.FLAG_UPDATE_CURRENT
    }

  private fun emitState(status: String) {
    updatePlaybackState()
    if (units.isNotEmpty()) updateNotification()
    TtsMediaSessionEventEmitter.emitPlaybackState(
      status = status,
      title = title,
      currentIndex = currentIndex,
      total = units.size,
      isPlaying = isPlaying && !isPaused,
      isPaused = isPaused,
      storyId = storyId,
      chapterId = chapterId,
    )
  }

  private fun notificationBody(): String {
    if (units.isEmpty()) return ""
    val prefix = if (isPaused) "Paused" else "Reading"
    return "$prefix ${currentIndex + 1} / ${units.size}"
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT < 26) return
    val channel = NotificationChannel(CHANNEL_ID, "TTS Playback", NotificationManager.IMPORTANCE_LOW)
    notificationManager?.createNotificationChannel(channel)
  }

  private fun buildUtteranceId(index: Int): String = "tts-$activePlaybackGeneration-$index"

  private fun parseUtteranceIndex(utteranceId: String?): Int? =
    utteranceId?.substringAfterLast("-")?.toIntOrNull()

  private fun runOnMain(action: () -> Unit) {
    android.os.Handler(mainLooper).post(action)
  }

  companion object {
    private const val TAG = "TtsMediaSession"
    private const val CHANNEL_ID = "tts_media"
    private const val NOTIFICATION_ID = 7751
    private const val PREFETCH_AHEAD_COUNT = 3

    private const val ACTION_START_PLAYBACK = "expo.modules.ttsmediasession.action.START_PLAYBACK"
    private const val ACTION_PAUSE = "expo.modules.ttsmediasession.action.PAUSE"
    private const val ACTION_RESUME = "expo.modules.ttsmediasession.action.RESUME"
    private const val ACTION_PLAY_PAUSE = "expo.modules.ttsmediasession.action.PLAY_PAUSE"
    private const val ACTION_NEXT = "expo.modules.ttsmediasession.action.NEXT"
    private const val ACTION_PREVIOUS = "expo.modules.ttsmediasession.action.PREVIOUS"
    private const val ACTION_STOP = "expo.modules.ttsmediasession.action.STOP"
    private const val ACTION_SEEK_TO_UNIT = "expo.modules.ttsmediasession.action.SEEK_TO_UNIT"

    private const val EXTRA_UNITS = "units"
    private const val EXTRA_TITLE = "title"
    private const val EXTRA_STORY_ID = "storyId"
    private const val EXTRA_CHAPTER_ID = "chapterId"
    private const val EXTRA_START_INDEX = "startIndex"
    private const val EXTRA_PITCH = "pitch"
    private const val EXTRA_RATE = "rate"
    private const val EXTRA_VOICE_IDENTIFIER = "voiceIdentifier"

    fun startPlayback(
      context: Context,
      units: ArrayList<String>,
      title: String,
      storyId: String?,
      chapterId: String?,
      startIndex: Int,
      pitch: Float,
      rate: Float,
      voiceIdentifier: String?,
    ) {
      val intent = Intent(context, TtsMediaSessionService::class.java).apply {
        action = ACTION_START_PLAYBACK
        putStringArrayListExtra(EXTRA_UNITS, units)
        putExtra(EXTRA_TITLE, title)
        putExtra(EXTRA_STORY_ID, storyId)
        putExtra(EXTRA_CHAPTER_ID, chapterId)
        putExtra(EXTRA_START_INDEX, startIndex)
        putExtra(EXTRA_PITCH, pitch)
        putExtra(EXTRA_RATE, rate)
        putExtra(EXTRA_VOICE_IDENTIFIER, voiceIdentifier)
      }
      if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
    }

    fun command(context: Context, action: String) {
      val intent = Intent(context, TtsMediaSessionService::class.java).apply {
        this.action = action
      }
      context.startService(intent)
    }

    fun seekToUnit(context: Context, index: Int) {
      val intent = Intent(context, TtsMediaSessionService::class.java).apply {
        action = ACTION_SEEK_TO_UNIT
        putExtra(EXTRA_START_INDEX, index)
      }
      context.startService(intent)
    }

    fun getVoices(context: Context, callback: (List<Voice>) -> Unit) {
      var engine: TextToSpeech? = null
      engine = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
          callback(engine?.voices?.toList().orEmpty())
        } else {
          callback(emptyList())
        }
        engine?.shutdown()
      }
    }
  }
}
