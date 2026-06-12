package com.vinicius741.webnovelarchiver.download

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
import com.vinicius741.webnovelarchiver.core.AppStorage
import com.vinicius741.webnovelarchiver.core.DownloadEngine
import com.vinicius741.webnovelarchiver.core.DownloadProgress
import com.vinicius741.webnovelarchiver.core.NetworkClient

class DownloadForegroundService : Service() {
    private lateinit var storage: AppStorage
    private lateinit var engine: DownloadEngine
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        storage = AppStorage(this)
        storage.recoverInterruptedDownloads()
        engine = DownloadEngine(storage, NetworkClient())
        engine.onProgress = ::updateNotification
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> {
                startForegroundIfNeeded(engine.currentProgress())
                engine.start()
            }
            ACTION_PAUSE -> {
                engine.pauseAll()
                updateNotification(engine.currentProgress())
            }
            ACTION_RESUME -> {
                startForegroundIfNeeded(engine.currentProgress())
                engine.resumeAll()
            }
            ACTION_STOP -> {
                engine.pauseAll()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundIfNeeded(progress: DownloadProgress) {
        if (foregroundStarted) return
        startForeground(NOTIFICATION_ID, buildNotification(progress))
        foregroundStarted = true
    }

    private fun updateNotification(progress: DownloadProgress) {
        if (!foregroundStarted) {
            startForegroundIfNeeded(progress)
            return
        }

        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(progress))
            }
        }
        if (progress.unfinished == 0) {
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private fun buildNotification(progress: DownloadProgress): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pauseIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, DownloadForegroundService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val resumeIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, DownloadForegroundService::class.java).setAction(ACTION_RESUME),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            4,
            Intent(this, DownloadForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val title = if (progress.unfinished > 0) "Downloading chapters" else "Downloads finished"
        val body = progress.activeTitle
            ?: "Pending ${progress.pending}, active ${progress.active}, completed ${progress.completed}, failed ${progress.failed}"
        val max = progress.total.coerceAtLeast(1)
        val done = (progress.completed + progress.failed + progress.cancelled).coerceAtMost(max)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openIntent)
            .setOngoing(progress.unfinished > 0)
            .setOnlyAlertOnce(true)
            .setProgress(max, done, progress.total == 0)
            .addAction(0, "Pause", pauseIntent)
            .addAction(0, "Resume", resumeIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Downloads",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Chapter download progress"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "webnovel_downloads"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.vinicius741.webnovelarchiver.download.START"
        const val ACTION_PAUSE = "com.vinicius741.webnovelarchiver.download.PAUSE"
        const val ACTION_RESUME = "com.vinicius741.webnovelarchiver.download.RESUME"
        const val ACTION_STOP = "com.vinicius741.webnovelarchiver.download.STOP"

        fun start(context: Context) {
            val intent = Intent(context, DownloadForegroundService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
