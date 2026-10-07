package com.vinicius741.webnovelarchiver.ai

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.data.storage.AiCoverDraftRecord
import com.vinicius741.webnovelarchiver.notification.AppNotificationChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Keeps the process alive while an AI cover job runs on the application scope, so minimizing or
 * leaving the app mid-generation no longer lets the system kill the in-flight (billable) call. The
 * service owns no job state: it renders the active job and stops itself when the coordinator goes
 * idle. Terminal outcomes arrive through [AiJobCoordinator.events] and are posted as tappable result
 * notifications, because the user may have left the app entirely by the time the result is ready.
 */
class AiJobForegroundService : Service() {
    private var foregroundStarted = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val coverCoordinator get() = appContainer.aiCoverJobCoordinator

    override fun onCreate() {
        super.onCreate()
        AppNotificationChannels.ensureCreated(this)
        serviceScope.launch {
            coverCoordinator.jobs.collect { coverJobs ->
                val cover = coverJobs.values.firstOrNull()
                when {
                    cover != null -> updateOngoingNotification(cover.message)
                    foregroundStarted -> stopAfterFinish()
                }
            }
        }
        serviceScope.launch {
            coverCoordinator.events.collect { event -> showCoverOutcomeNotification(event) }
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
                val cover =
                    coverCoordinator.jobs.value.values
                        .firstOrNull()
                val message = cover?.message ?: intent?.getStringExtra(EXTRA_INITIAL_MESSAGE) ?: "Working on AI task..."
                // startForegroundService demands startForeground even on an immediate stop.
                startForeground(ONGOING_NOTIFICATION_ID, aiJobOngoingNotification(message))
                foregroundStarted = true
                if (cover == null) stopAfterFinish()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Android 15+ caps data-sync foreground services. AI jobs run for minutes, never hours, so
     * this is defensive only: relinquish foreground state and let the coordinator's application
     * scope finish the in-flight call without the service.
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        Timber.w("AI job foreground service timed out (startId=%s, type=%s)", startId, fgsType)
        stopAfterFinish()
    }

    private fun updateOngoingNotification(message: String) {
        if (!foregroundStarted) return
        // Inlined like DownloadForegroundService so lint sees the permission guard.
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                NotificationManagerCompat.from(this).notify(ONGOING_NOTIFICATION_ID, aiJobOngoingNotification(message))
            }
        }
    }

    private fun stopAfterFinish() {
        foregroundStarted = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun showCoverOutcomeNotification(event: AiCoverJobEvent) {
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
        postOutcome(COVER_OUTCOME_NOTIFICATION_ID, title, storyTitle?.let { "$it — $body" } ?: body, requestCode = 1)
    }

    companion object {
        private const val ONGOING_NOTIFICATION_ID = 1003
        private const val COVER_OUTCOME_NOTIFICATION_ID = 1004
        const val ACTION_START = "com.vinicius741.webnovelarchiver.ai.JOB_START"
        private const val EXTRA_INITIAL_MESSAGE = "initial_message"

        /**
         * Called from the UI right before a job starts, so the service is always started while the
         * app is in the foreground (Android 12+ restricts background foreground-service starts).
         * A rejected start must not block generation: the job runs on the application scope either
         * way, only without the keep-alive notification.
         */
        fun start(
            context: Context,
            initialMessage: String,
        ): Boolean {
            val intent =
                Intent(context, AiJobForegroundService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_INITIAL_MESSAGE, initialMessage)
            return runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Timber.w(it, "Could not start AI job foreground service") }.isSuccess
        }
    }
}

private fun Service.postOutcome(
    id: Int,
    title: String,
    text: String,
    requestCode: Int,
) {
    // Inlined like DownloadForegroundService so lint sees the permission guard.
    if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    val notification = aiJobNotification(title, text, requestCode = requestCode, ongoing = false)
    runCatching {
        NotificationManagerCompat.from(this).notify(id, notification)
    }.onFailure { Timber.w(it, "Could not post AI job outcome notification") }
}
