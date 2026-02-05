package expo.modules.ttsmediasession

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat

class TtsMediaSessionService : Service() {
  private var mediaSession: MediaSessionCompat? = null
  private var notificationManager: NotificationManager? = null

  private var isPlaying: Boolean = false
  private var title: String = "Reading"
  private var body: String = ""

  private var lastEmitAt: Long = 0L

  override fun onCreate() {
    super.onCreate()
    notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    createNotificationChannel()
    ensureMediaSession()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val action = intent?.action

    if (Intent.ACTION_MEDIA_BUTTON == action) {
      MediaButtonReceiver.handleIntent(mediaSession, intent)
      return START_STICKY
    }

    when (action) {
      ACTION_START -> {
        updateFromIntent(intent)
        startAsForeground()
      }
      ACTION_UPDATE -> {
        updateFromIntent(intent)
        updateNotification()
      }
      ACTION_STOP -> {
        stopForegroundService()
      }
      else -> {
        // No-op
      }
    }

    return START_STICKY
  }

  override fun onDestroy() {
    super.onDestroy()
    mediaSession?.isActive = false
    mediaSession?.release()
    mediaSession = null
  }

  override fun onBind(intent: Intent?): IBinder? {
    return null
  }

  private fun updateFromIntent(intent: Intent?) {
    if (intent == null) return
    title = intent.getStringExtra(EXTRA_TITLE) ?: title
    body = intent.getStringExtra(EXTRA_BODY) ?: body
    isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, isPlaying)
    updatePlaybackState()
  }

  private fun startAsForeground() {
    val notification = buildNotification()
    if (Build.VERSION.SDK_INT >= 29) {
      startForeground(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
      )
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  private fun updateNotification() {
    val notification = buildNotification()
    notificationManager?.notify(NOTIFICATION_ID, notification)
  }

  private fun stopForegroundService() {
    try {
      if (Build.VERSION.SDK_INT >= 24) {
        stopForeground(STOP_FOREGROUND_REMOVE)
      } else {
        @Suppress("DEPRECATION")
        stopForeground(true)
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to stop foreground: ${e.message}")
    }
    stopSelf()
  }

  private fun ensureMediaSession() {
    if (mediaSession != null) return

    mediaSession = MediaSessionCompat(this, TAG).apply {
      setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)

      val mediaButtonPendingIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(
        this@TtsMediaSessionService,
        PlaybackStateCompat.ACTION_PLAY_PAUSE
      )
      setMediaButtonReceiver(mediaButtonPendingIntent)

      setCallback(object : MediaSessionCompat.Callback() {
        override fun onPlay() {
          emitPlayPauseOnce()
        }

        override fun onPause() {
          emitPlayPauseOnce()
        }

        override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
          val keyEvent: KeyEvent? = mediaButtonEvent?.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
          if (keyEvent?.action == KeyEvent.ACTION_DOWN) {
            when (keyEvent.keyCode) {
              KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
              KeyEvent.KEYCODE_MEDIA_PLAY,
              KeyEvent.KEYCODE_MEDIA_PAUSE,
              KeyEvent.KEYCODE_HEADSETHOOK -> {
                emitPlayPauseOnce()
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

  private fun emitPlayPauseOnce() {
    val now = SystemClock.elapsedRealtime()
    if (now - lastEmitAt < 250) return
    lastEmitAt = now
    Log.d(TAG, "Media button play/pause received")
    TtsMediaSessionEventEmitter.emitPlayPause()
  }

  private fun updatePlaybackState() {
    val state = if (isPlaying) {
      PlaybackStateCompat.STATE_PLAYING
    } else {
      PlaybackStateCompat.STATE_PAUSED
    }
    val actions = PlaybackStateCompat.ACTION_PLAY_PAUSE or
      PlaybackStateCompat.ACTION_PLAY or
      PlaybackStateCompat.ACTION_PAUSE

    val playbackState = PlaybackStateCompat.Builder()
      .setActions(actions)
      .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
      .build()

    mediaSession?.setPlaybackState(playbackState)
  }

  private fun buildNotification(): Notification {
    ensureMediaSession()

    val playPauseAction = NotificationCompat.Action(
      if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
      if (isPlaying) "Pause" else "Play",
      MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY_PAUSE)
    )

    val style = MediaStyle()
      .setMediaSession(mediaSession?.sessionToken)
      .setShowActionsInCompactView(0)

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle(title)
      .setContentText(body)
      .setSmallIcon(android.R.drawable.ic_media_play)
      .addAction(playPauseAction)
      .setStyle(style)
      .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setOnlyAlertOnce(true)
      .setOngoing(true)
      .build()
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT < 26) return
    val channel = NotificationChannel(
      CHANNEL_ID,
      "TTS Playback",
      NotificationManager.IMPORTANCE_LOW
    )
    notificationManager?.createNotificationChannel(channel)
  }

  companion object {
    private const val TAG = "TtsMediaSession"
    private const val CHANNEL_ID = "tts_media"
    private const val NOTIFICATION_ID = 7751

    private const val ACTION_START = "expo.modules.ttsmediasession.action.START"
    private const val ACTION_UPDATE = "expo.modules.ttsmediasession.action.UPDATE"
    private const val ACTION_STOP = "expo.modules.ttsmediasession.action.STOP"

    private const val EXTRA_TITLE = "title"
    private const val EXTRA_BODY = "body"
    private const val EXTRA_IS_PLAYING = "isPlaying"

    fun start(context: Context, title: String, body: String, isPlaying: Boolean) {
      val intent = Intent(context, TtsMediaSessionService::class.java).apply {
        action = ACTION_START
        putExtra(EXTRA_TITLE, title)
        putExtra(EXTRA_BODY, body)
        putExtra(EXTRA_IS_PLAYING, isPlaying)
      }
      if (Build.VERSION.SDK_INT >= 26) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun update(context: Context, title: String, body: String, isPlaying: Boolean) {
      val intent = Intent(context, TtsMediaSessionService::class.java).apply {
        action = ACTION_UPDATE
        putExtra(EXTRA_TITLE, title)
        putExtra(EXTRA_BODY, body)
        putExtra(EXTRA_IS_PLAYING, isPlaying)
      }
      context.startService(intent)
    }

    fun stop(context: Context) {
      val intent = Intent(context, TtsMediaSessionService::class.java).apply {
        action = ACTION_STOP
      }
      context.startService(intent)
    }
  }
}
