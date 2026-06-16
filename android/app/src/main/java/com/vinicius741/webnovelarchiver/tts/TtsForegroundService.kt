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
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.vinicius741.webnovelarchiver.MainActivity
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.appContainer
import com.vinicius741.webnovelarchiver.core.TtsEngine
import com.vinicius741.webnovelarchiver.core.TtsNotificationActions

class TtsForegroundService : Service() {
    private lateinit var engine: TtsEngine
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        // Share the process-wide AppStorage via the container (M2); R8 thread-safe TtsEngine.
        engine = TtsEngine(this, appContainer.storage)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_RESUME_SESSION) {
            ACTION_START -> startPlayback(intent)
            ACTION_RESUME_SESSION -> resumePlayback()
            ACTION_PAUSE -> {
                engine.pause()
                updateNotification("TTS paused", isPaused = true)
            }
            ACTION_NEXT -> {
                engine.next()
                updateNotification("TTS playing next chunk")
            }
            ACTION_PREVIOUS -> {
                engine.previous()
                updateNotification("TTS playing previous chunk")
            }
            ACTION_STOP -> {
                engine.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        engine.shutdown()
        super.onDestroy()
    }

    private fun startPlayback(intent: Intent?) {
        startForegroundIfNeeded(buildNotification("Starting TTS"))
        val storyId = intent?.getStringExtra(EXTRA_STORY_ID)
        val chapterId = intent?.getStringExtra(EXTRA_CHAPTER_ID)
        val story = storyId?.let { appContainer.storage.getStory(it) }
        val chapter = chapterId?.let { id -> story?.chapters?.firstOrNull { it.id == id } }
        if (story != null && chapter != null) {
            engine.play(story, chapter)
            updateNotification("Playing ${chapter.title}")
        } else {
            updateNotification("Could not start TTS")
        }
    }

    private fun resumePlayback() {
        startForegroundIfNeeded(buildNotification("Resuming TTS"))
        if (engine.resumePersistedSession()) updateNotification("Playing saved TTS session") else updateNotification("No saved TTS session")
    }

    private fun startForegroundIfNeeded(notification: Notification) {
        if (foregroundStarted) return
        startForeground(NOTIFICATION_ID, notification)
        foregroundStarted = true
    }

    private fun updateNotification(body: String, isPaused: Boolean = false) {
        if (!foregroundStarted) {
            startForegroundIfNeeded(buildNotification(body, isPaused))
            return
        }
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(body, isPaused))
            }
        }
    }

    private fun buildNotification(body: String, isPaused: Boolean = false): Notification {
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

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Text to Speech")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        TtsNotificationActions.actions(isPaused).forEachIndexed { index, action ->
            builder.addAction(0, action.label, serviceAction(12 + index, action.action))
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Text to Speech", NotificationManager.IMPORTANCE_LOW).apply {
            description = "TTS playback controls"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "webnovel_tts"
        private const val NOTIFICATION_ID = 1002
        private const val EXTRA_STORY_ID = "storyId"
        private const val EXTRA_CHAPTER_ID = "chapterId"
        const val ACTION_START = TtsNotificationActions.ACTION_START
        const val ACTION_RESUME_SESSION = TtsNotificationActions.ACTION_RESUME_SESSION
        const val ACTION_PAUSE = TtsNotificationActions.ACTION_PAUSE
        const val ACTION_NEXT = TtsNotificationActions.ACTION_NEXT
        const val ACTION_PREVIOUS = TtsNotificationActions.ACTION_PREVIOUS
        const val ACTION_STOP = TtsNotificationActions.ACTION_STOP

        fun start(context: Context, storyId: String, chapterId: String) {
            val intent = Intent(context, TtsForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_STORY_ID, storyId)
                .putExtra(EXTRA_CHAPTER_ID, chapterId)
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
