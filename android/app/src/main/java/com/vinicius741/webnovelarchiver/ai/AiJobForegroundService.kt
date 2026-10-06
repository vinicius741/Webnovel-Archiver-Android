package com.vinicius741.webnovelarchiver.ai

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.data.storage.AiCoverDraftRecord
import com.vinicius741.webnovelarchiver.notification.AppNotificationChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Keeps the process alive while any AI job (cover generation or chapter rewrite) runs on the
 * application scope, so minimizing or leaving the app mid-generation no longer lets the system
 * kill the in-flight (billable) call. The service owns no job state: it renders whichever jobs are
 * active — the chapter-rewrite job (with its queue count) outranks the cover job because its batch
 * is longer — and stops itself when both coordinators go idle. Terminal outcomes arrive through
 * each coordinator's [AiJobCoordinator.events] and are posted as tappable result notifications,
 * because the user may have left the app entirely by the time the result is ready.
 */
class AiJobForegroundService : Service() {
    private var foregroundStarted = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val coverCoordinator get() = appContainer.aiCoverJobCoordinator
    private val rewriteCoordinator get() = appContainer.aiChapterRewriteJobCoordinator

    override fun onCreate() {
        super.onCreate()
        AppNotificationChannels.ensureCreated(this)
        serviceScope.launch {
            combine(
                coverCoordinator.jobs,
                rewriteCoordinator.jobs,
                rewriteCoordinator.queue,
            ) { coverJobs, rewriteJobs, queue -> Triple(coverJobs, rewriteJobs, queue) }
                .collect { (coverJobs, rewriteJobs, queue) ->
                    val rewrite = rewriteJobs.values.firstOrNull()
                    val cover = coverJobs.values.firstOrNull()
                    when {
                        rewrite != null -> updateOngoingNotification(rewrite.message, queue.size)
                        cover != null -> updateOngoingNotification(cover.message, queuedCount = 0)
                        queue.isEmpty() -> if (foregroundStarted) stopAfterFinish()
                        // Rewrite queue holds chapters but no job registered yet: a handoff is in
                        // flight — hold the service until the next job registers.
                    }
                }
        }
        serviceScope.launch {
            coverCoordinator.events.collect { event -> showCoverOutcomeNotification(event) }
        }
        serviceScope.launch {
            rewriteCoordinator.events.collect { event -> showRewriteOutcomeNotification(event) }
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
                val idle =
                    coverCoordinator.jobs.value.isEmpty() &&
                        rewriteCoordinator.jobs.value.isEmpty() &&
                        rewriteCoordinator.queue.value.isEmpty()
                // startForegroundService demands startForeground even on an immediate stop.
                startForeground(
                    ONGOING_NOTIFICATION_ID,
                    buildOngoingNotification(intent?.getStringExtra(EXTRA_INITIAL_MESSAGE) ?: "Working on AI task...", 0),
                )
                foregroundStarted = true
                if (idle) stopAfterFinish()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Android 15+ caps data-sync foreground services. AI jobs run for minutes, never hours, so
     * this is defensive only: cancel the batch drain, relinquish foreground state, and let the
     * coordinators' application scope finish the in-flight call without the service.
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        Timber.w("AI job foreground service timed out (startId=%s, type=%s)", startId, fgsType)
        rewriteCoordinator.cancelAll(reason = "foreground service timeout")
        stopAfterFinish()
    }

    private fun updateOngoingNotification(
        message: String,
        queuedCount: Int,
    ) {
        if (!foregroundStarted) return
        // Inlined like DownloadForegroundService so lint sees the permission guard.
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                NotificationManagerCompat.from(this).notify(ONGOING_NOTIFICATION_ID, buildOngoingNotification(message, queuedCount))
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

    private fun showRewriteOutcomeNotification(event: AiChapterRewriteJobEvent) {
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
        postOutcome(REWRITE_OUTCOME_NOTIFICATION_ID, title, storyTitle?.let { "$it — $body" } ?: body, requestCode = 3)
    }

    companion object {
        private const val ONGOING_NOTIFICATION_ID = 1003
        private const val COVER_OUTCOME_NOTIFICATION_ID = 1004
        private const val REWRITE_OUTCOME_NOTIFICATION_ID = 1006
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

private fun Service.buildOngoingNotification(
    message: String,
    queuedCount: Int,
): Notification {
    val text = if (queuedCount > 0) "$message · $queuedCount queued" else message
    val title =
        if (queuedCount > 0) {
            getString(R.string.ai_chapter_rewrite_notif_active)
        } else {
            getString(R.string.ai_cover_notif_active)
        }
    return aiJobNotification(title, text, requestCode = 2, ongoing = true)
}
