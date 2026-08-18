package com.vinicius741.webnovelarchiver.ai

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.app.MainActivity
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.data.storage.AiCoverDraftRecord
import com.vinicius741.webnovelarchiver.notification.AppNotificationCategory
import com.vinicius741.webnovelarchiver.notification.AppNotificationChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Keeps the process alive while an AI cover job runs on the application scope, so minimizing or
 * leaving the app mid-generation no longer lets the system kill the in-flight (billable) image
 * call. The service owns no job state: it mirrors [AiCoverJobCoordinator.jobs] in an ongoing
 * notification and stops itself when the coordinator goes idle. Terminal outcomes arrive through
 * [AiCoverJobCoordinator.events] and are posted as tappable result notifications, because the
 * user may have left the app entirely by the time the cover is ready.
 */
class AiCoverForegroundService : Service() {
    private var foregroundStarted = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val coordinator get() = appContainer.aiCoverJobCoordinator

    override fun onCreate() {
        super.onCreate()
        AppNotificationChannels.ensureCreated(this)
        serviceScope.launch {
            coordinator.jobs.collect { jobs ->
                when {
                    jobs.isEmpty() -> if (foregroundStarted) stopAfterFinish()
                    else -> updateOngoingNotification(jobs.values.first())
                }
            }
        }
        serviceScope.launch {
            coordinator.events.collect { event -> showOutcomeNotification(event) }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> {
                val job =
                    coordinator.jobs.value.values
                        .firstOrNull()
                if (job == null) {
                    // startForegroundService demands startForeground even on an immediate stop.
                    startForeground(ONGOING_NOTIFICATION_ID, buildOngoingNotification("Generating AI cover..."))
                    foregroundStarted = true
                    stopAfterFinish()
                } else {
                    startForegroundIfNeeded(job)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Android 15+ caps data-sync foreground services. A cover job runs for minutes, never hours,
     * so this is defensive only: relinquish foreground state and let the coordinator's application
     * scope finish the call without the service.
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        Timber.w("AI cover foreground service timed out (startId=%s, type=%s)", startId, fgsType)
        stopAfterFinish()
    }

    private fun startForegroundIfNeeded(job: AiCoverJobState) {
        if (foregroundStarted) return
        startForeground(ONGOING_NOTIFICATION_ID, buildOngoingNotification(job.message))
        foregroundStarted = true
    }

    private fun updateOngoingNotification(job: AiCoverJobState) {
        if (!foregroundStarted) {
            startForegroundIfNeeded(job)
            return
        }
        // Inlined like DownloadForegroundService so lint sees the permission guard.
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                NotificationManagerCompat.from(this).notify(ONGOING_NOTIFICATION_ID, buildOngoingNotification(job.message))
            }
        }
    }

    private fun stopAfterFinish() {
        foregroundStarted = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun showOutcomeNotification(event: AiCoverJobEvent) {
        // Inlined like DownloadForegroundService so lint sees the permission guard.
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val storyTitle = runCatching { appContainer.repository.story(event.storyId)?.title }.getOrNull()
        val title =
            when (event) {
                is AiCoverJobEvent.Succeeded ->
                    if (event.record is AiCoverDraftRecord.Image) "AI cover ready" else "Image prompt ready"
                is AiCoverJobEvent.Failed -> "AI cover failed"
            }
        val body =
            when (event) {
                is AiCoverJobEvent.Succeeded ->
                    if (event.record is AiCoverDraftRecord.Image) {
                        "Preview it under More options → AI Controls."
                    } else {
                        "Edit it under More options → AI Controls."
                    }
                is AiCoverJobEvent.Failed -> event.message
            }
        val text = storyTitle?.let { "$it — $body" } ?: body
        val openIntent =
            PendingIntent.getActivity(
                this,
                1,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            NotificationCompat
                .Builder(this, AppNotificationCategory.AI_GENERATION.channelId)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openIntent)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
        runCatching {
            NotificationManagerCompat.from(this).notify(OUTCOME_NOTIFICATION_ID, notification)
        }.onFailure { Timber.w(it, "Could not post AI cover outcome notification") }
    }

    private fun buildOngoingNotification(message: String): Notification {
        val openIntent =
            PendingIntent.getActivity(
                this,
                2,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat
            .Builder(this, AppNotificationCategory.AI_GENERATION.channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.ai_cover_notif_active))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()
    }

    companion object {
        private const val ONGOING_NOTIFICATION_ID = 1003
        private const val OUTCOME_NOTIFICATION_ID = 1004
        const val ACTION_START = "com.vinicius741.webnovelarchiver.ai.COVER_START"

        /**
         * Called from the UI right before a job starts, so the service is always started while the
         * app is in the foreground (Android 12+ restricts background foreground-service starts).
         * A rejected start must not block generation: the job runs on the application scope either
         * way, only without the keep-alive notification.
         */
        fun start(context: Context) {
            val intent = Intent(context, AiCoverForegroundService::class.java).setAction(ACTION_START)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Timber.w(it, "Could not start AI cover foreground service") }
        }
    }
}
