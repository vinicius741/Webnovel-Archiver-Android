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
import com.vinicius741.webnovelarchiver.app.AiChapterRewriteJobEvent
import com.vinicius741.webnovelarchiver.app.AiChapterRewriteJobState
import com.vinicius741.webnovelarchiver.app.MainActivity
import com.vinicius741.webnovelarchiver.app.appContainer
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
 * Keeps the process alive while a chapter rewrite job runs on the application scope, mirroring
 * [AiCoverForegroundService]: the service owns no job state, it reflects the coordinator's jobs in
 * an ongoing notification and posts tappable outcome notifications when a draft completes or fails.
 */
class AiChapterRewriteForegroundService : Service() {
    private var foregroundStarted = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val coordinator get() = appContainer.aiChapterRewriteJobCoordinator

    override fun onCreate() {
        super.onCreate()
        AppNotificationChannels.ensureCreated(this)
        serviceScope.launch {
            combine(coordinator.jobs, coordinator.queue) { jobs, queue -> jobs to queue }
                .collect { (jobs, queue) ->
                    when {
                        jobs.isNotEmpty() -> updateOngoingNotification(jobs.values.first(), queue.size)
                        queue.isEmpty() -> if (foregroundStarted) stopAfterFinish()
                        // Jobs drained but the queue still holds chapters: a handoff is in
                        // flight — hold the service until the next job registers.
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
                if (job == null && coordinator.queue.value.isEmpty()) {
                    // startForegroundService demands startForeground even on an immediate stop.
                    startForeground(ONGOING_NOTIFICATION_ID, buildOngoingNotification("Polishing chapter...", 0))
                    foregroundStarted = true
                    stopAfterFinish()
                } else if (job == null) {
                    startForeground(ONGOING_NOTIFICATION_ID, buildOngoingNotification("Polishing chapter...", coordinator.queue.value.size))
                    foregroundStarted = true
                } else {
                    startForegroundIfNeeded(job, coordinator.queue.value.size)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Android 15+ caps data-sync foreground services; a rewrite runs for minutes, never hours. */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        Timber.w("Chapter rewrite foreground service timed out (startId=%s, type=%s)", startId, fgsType)
        stopAfterFinish()
    }

    private fun startForegroundIfNeeded(
        job: AiChapterRewriteJobState,
        queuedCount: Int,
    ) {
        if (foregroundStarted) return
        startForeground(ONGOING_NOTIFICATION_ID, buildOngoingNotification(job.message, queuedCount))
        foregroundStarted = true
    }

    private fun updateOngoingNotification(
        job: AiChapterRewriteJobState,
        queuedCount: Int,
    ) {
        if (!foregroundStarted) {
            startForegroundIfNeeded(job, queuedCount)
            return
        }
        // Inlined like DownloadForegroundService so lint sees the permission guard.
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                NotificationManagerCompat.from(this).notify(ONGOING_NOTIFICATION_ID, buildOngoingNotification(job.message, queuedCount))
            }
        }
    }

    private fun stopAfterFinish() {
        foregroundStarted = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun showOutcomeNotification(event: AiChapterRewriteJobEvent) {
        // Inlined like DownloadForegroundService so lint sees the permission guard.
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val storyTitle = runCatching { appContainer.repository.story(event.storyId)?.title }.getOrNull()
        val (title, body) =
            when (event) {
                is AiChapterRewriteJobEvent.Succeeded ->
                    when (event.status) {
                        "ready" -> "Polished chapter ready" to "Compare it with the source before applying."
                        "blocked" -> "Polished draft flagged" to "The verifier found blockers — review before applying."
                        else -> "Polished draft unverified" to "The verifier could not be read; review or regenerate."
                    }
                is AiChapterRewriteJobEvent.Failed -> "Chapter polish failed" to event.message
            }
        val text = storyTitle?.let { "$it — $body" } ?: body
        val openIntent =
            PendingIntent.getActivity(
                this,
                3,
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
        }.onFailure { Timber.w(it, "Could not post chapter rewrite outcome notification") }
    }

    private fun buildOngoingNotification(
        message: String,
        queuedCount: Int,
    ): Notification {
        val text = if (queuedCount > 0) "$message · $queuedCount queued" else message
        val openIntent =
            PendingIntent.getActivity(
                this,
                4,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat
            .Builder(this, AppNotificationCategory.AI_GENERATION.channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.ai_chapter_rewrite_notif_active))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()
    }

    companion object {
        private const val ONGOING_NOTIFICATION_ID = 1005
        private const val OUTCOME_NOTIFICATION_ID = 1006
        const val ACTION_START = "com.vinicius741.webnovelarchiver.ai.CHAPTER_REWRITE_START"

        /** Called from the UI while foregrounded, right before a job starts. */
        fun start(context: Context) {
            val intent = Intent(context, AiChapterRewriteForegroundService::class.java).setAction(ACTION_START)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Timber.w(it, "Could not start chapter rewrite foreground service") }
        }
    }
}
