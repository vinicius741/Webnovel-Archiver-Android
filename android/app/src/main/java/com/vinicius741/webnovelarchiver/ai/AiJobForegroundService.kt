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
import com.vinicius741.webnovelarchiver.data.repository.storyTitle
import com.vinicius741.webnovelarchiver.data.storage.AiCoverDraftRecord
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.notification.AppNotificationChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
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
    private var latestStartId = 0
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val coverCoordinator get() = appContainer.aiCoverJobCoordinator
    private val characterCoordinator get() = appContainer.characterJobCoordinator

    /** The running cover job, else the running Characters-tab job. */
    private fun activeJob(): AiJobModel? =
        coverCoordinator.jobs.value.values
            .firstOrNull() ?: characterCoordinator.jobs.value.values
            .firstOrNull()

    override fun onCreate() {
        super.onCreate()
        AppNotificationChannels.ensureCreated(this)
        serviceScope.launch {
            combine(coverCoordinator.jobs, characterCoordinator.jobs) { _, _ -> activeJob() }.collect { active ->
                when {
                    active != null -> updateOngoingNotification(active.message, active.storyId)
                    foregroundStarted -> stopAfterFinish()
                }
            }
        }
        serviceScope.launch {
            coverCoordinator.events.collect { event -> showCoverOutcomeNotification(event) }
        }
        serviceScope.launch {
            characterCoordinator.events.collect { event -> showCharacterOutcomeNotification(event) }
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
        latestStartId = startId
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> {
                val active = activeJob()
                val message = active?.message ?: intent?.getStringExtra(EXTRA_INITIAL_MESSAGE) ?: "Working on AI task..."
                // startForegroundService demands startForeground even on an immediate stop.
                startForeground(ONGOING_NOTIFICATION_ID, aiJobOngoingNotification(message, active?.storyId))
                foregroundStarted = true
                if (active == null) stopAfterFinish()
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

    private fun updateOngoingNotification(
        message: String,
        storyId: String,
    ) {
        if (!foregroundStarted) return
        // Inlined like DownloadForegroundService so lint sees the permission guard.
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                NotificationManagerCompat.from(this).notify(ONGOING_NOTIFICATION_ID, aiJobOngoingNotification(message, storyId))
            }
        }
    }

    private fun stopAfterFinish() {
        foregroundStarted = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Only the latest start: a newer queued startForegroundService must reach its startForeground.
        stopSelf(latestStartId)
    }

    private fun showCoverOutcomeNotification(event: AiCoverJobEvent) {
        val storyTitle = runCatching { appContainer.repository.storyTitle(event.storyId) }.getOrNull()
        val title =
            when (event) {
                is AiCoverJobEvent.Succeeded ->
                    if (event.record is AiCoverDraftRecord.Image) "AI cover ready" else "Image prompt ready"
                is AiCoverJobEvent.Failed -> "AI cover failed"
            }
        val body =
            when (event) {
                is AiCoverJobEvent.Succeeded ->
                    if (event.record is AiCoverDraftRecord.Image) "Tap to compare and choose it." else "Tap to review the prompt."
                is AiCoverJobEvent.Failed -> event.message
            }
        postOutcome(
            COVER_OUTCOME_NOTIFICATION_ID,
            COVER_OUTCOME_REQUEST_CODE,
            title,
            storyTitle?.let {
                "$it — $body"
            } ?: body,
            AppRoute.AiControls(event.storyId),
        )
    }

    private fun showCharacterOutcomeNotification(event: CharacterJobEvent) {
        val storyTitle = runCatching { appContainer.repository.storyTitle(event.storyId) }.getOrNull()
        val title =
            when (event) {
                is CharacterJobEvent.Succeeded ->
                    if (event.kind == CharacterJobKind.FIND) "Characters found" else "Portrait of ${event.characterName} ready"
                is CharacterJobEvent.Failed -> if (event.kind == CharacterJobKind.FIND) "Finding characters failed" else "Portrait failed"
            }
        val body =
            when (event) {
                is CharacterJobEvent.Succeeded -> "Tap to see it."
                is CharacterJobEvent.Failed -> event.message
            }
        postOutcome(
            CHARACTER_OUTCOME_NOTIFICATION_ID,
            CHARACTER_OUTCOME_REQUEST_CODE,
            title,
            storyTitle?.let {
                "$it — $body"
            } ?: body,
            AppRoute.AiControls(event.storyId),
        )
    }

    companion object {
        private const val ONGOING_NOTIFICATION_ID = 1003
        private const val CHARACTER_OUTCOME_NOTIFICATION_ID = 1006

        // Each tappable notification needs its own PendingIntent request code (22 is the ongoing one).
        private const val COVER_OUTCOME_REQUEST_CODE = 21
        private const val CHARACTER_OUTCOME_REQUEST_CODE = 25
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
    requestCode: Int,
    title: String,
    text: String,
    route: AppRoute,
) {
    // Inlined like DownloadForegroundService so lint sees the permission guard.
    if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    val notification = aiJobNotification(title, text, requestCode = requestCode, ongoing = false, route = route)
    runCatching {
        NotificationManagerCompat.from(this).notify(id, notification)
    }.onFailure { Timber.w(it, "Could not post AI job outcome notification") }
}
