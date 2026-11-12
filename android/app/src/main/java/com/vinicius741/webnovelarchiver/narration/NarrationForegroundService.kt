package com.vinicius741.webnovelarchiver.narration

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.app.openAppPendingIntent
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.notification.AppNotificationCategory
import com.vinicius741.webnovelarchiver.notification.AppNotificationChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Keeps the process in the foreground while chapter audio is generated, so generation continues
 * with the screen off or another app in front. The service owns no work: the process-wide
 * [NarrationCoordinator] runs the queue, and the service stops itself once the queue is empty or
 * paused. Type `specialUse`: on-device speech synthesis fits none of the listed types, and the
 * `dataSync`/`mediaProcessing` types are capped at 6 hours a day, which an overnight batch exceeds.
 */
class NarrationForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foregroundStarted = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val coordinator get() = appContainer.narration

    override fun onCreate() {
        super.onCreate()
        AppNotificationChannels.ensureCreated(this)
        serviceScope.launch {
            combine(coordinator.queueState, coordinator.progress) { _, progress -> progress }.collect { progress ->
                if (!foregroundStarted) return@collect
                if (!coordinator.hasRunnableWork() && progress == null) {
                    stopNow()
                } else {
                    refreshWakeLock()
                    post(notification(progress))
                }
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // startForegroundService demands startForeground first, even when stopping right away.
        startInForeground(notification(coordinator.progress.value))
        when (intent?.action) {
            ACTION_PAUSE -> coordinator.pause()
        }
        // After a restart the queue may still be loading: decide once it has.
        serviceScope.launch {
            coordinator.awaitQueueLoaded()
            if (foregroundStarted && !coordinator.hasRunnableWork() && coordinator.progress.value == null) stopNow()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        coordinator.setGenerationAllowed(false)
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        foregroundStarted = true
        coordinator.setGenerationAllowed(true)
        refreshWakeLock()
    }

    /** Held in hour-long leases renewed on every progress update, so a stuck service cannot hold it forever. */
    private fun refreshWakeLock() {
        val lock =
            wakeLock ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WebnovelArchiver:narration")
                .apply { setReferenceCounted(false) }
                .also { wakeLock = it }
        lock.acquire(WAKE_LOCK_LEASE_MS)
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun stopNow() {
        foregroundStarted = false
        coordinator.setGenerationAllowed(false)
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun post(notification: Notification) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification) }
    }

    private fun notification(progress: NarrationProgress?): Notification {
        val queued =
            coordinator.queueState.value.jobs
                .count { it.state == NarrationJobState.QUEUED }
        val title = if (progress == null) "Preparing to generate audio" else "Generating audio · ${progress.storyTitle}"
        val text =
            if (progress == null) {
                "$queued ${if (queued == 1) "chapter" else "chapters"} queued"
            } else {
                buildString {
                    append(progress.chapterTitle)
                    if (progress.preparing) {
                        append(" · starting")
                    } else {
                        append(" · ${(progress.fraction * PERCENT).toInt()}%")
                        progress.speed?.let { append(String.format(java.util.Locale.US, " · %.1f×", it)) }
                        // The computer is holding back for its memory; generation resumes by itself.
                        if (progress.waitingFor != null) append(" · waiting for ${progress.generatingOn ?: "the computer"}")
                    }
                    if (progress.queuedAfter > 0) append(" · ${progress.queuedAfter} more queued")
                    progress.etaMs?.let { append(" · ~${NarrationPlanning.formatDuration(it)} left") }
                }
            }
        val pause =
            PendingIntent.getService(
                this,
                PAUSE_REQUEST_CODE,
                Intent(this, NarrationForegroundService::class.java).setAction(ACTION_PAUSE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat
            .Builder(this, AppNotificationCategory.NARRATION.channelId)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppPendingIntent(OPEN_REQUEST_CODE, AppRoute.Narration))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(PERCENT, progress?.let { (it.fraction * PERCENT).toInt() } ?: 0, progress == null || progress.preparing)
            .addAction(R.drawable.wna_pause, "Pause", pause)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1005
        private const val OPEN_REQUEST_CODE = 23
        private const val PAUSE_REQUEST_CODE = 24
        private const val PERCENT = 100
        private const val WAKE_LOCK_LEASE_MS = 60L * 60L * 1000L
        const val ACTION_START = "com.vinicius741.webnovelarchiver.narration.START"
        const val ACTION_PAUSE = "com.vinicius741.webnovelarchiver.narration.PAUSE"

        /**
         * Starts (or refreshes) the service. Android 12+ may refuse a start from the background unless
         * the app is exempt from battery optimization; the jobs then stay queued and start the next
         * time the app is opened.
         */
        fun start(context: Context): Boolean =
            runCatching {
                val intent = Intent(context, NarrationForegroundService::class.java).setAction(ACTION_START)
                ContextCompat.startForegroundService(context, intent)
            }.onFailure { Timber.w(it, "Could not start the narration service") }.isSuccess
    }
}
