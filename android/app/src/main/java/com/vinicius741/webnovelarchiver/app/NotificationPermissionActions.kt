package com.vinicius741.webnovelarchiver.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.lifecycle.Lifecycle
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.notification.AppNotificationChannels
import com.vinicius741.webnovelarchiver.notification.NotificationPermissionAction
import com.vinicius741.webnovelarchiver.notification.NotificationSettingsPlanning

/**
 * Notification-permission actions, split out of [MainActivity] so the activity keeps only lifecycle
 * wiring. These run against the [ScreenHost.app] activity (for the launcher, SharedPreferences, and
 * lifecycle state) — they read no private activity fields beyond the launcher exposed on
 * [ScreenHost]. Mirrors the existing `importBackupLauncher` exposure pattern.
 */
private const val NOTIFICATION_PERMISSION_PREFERENCES = "notification_permission"
private const val KEY_AUTOMATIC_NOTIFICATION_PROMPT_SHOWN = "automatic_prompt_shown"

internal fun ScreenHost.notificationPermissionActionLabel(): String =
    when (notificationPermissionAction()) {
        NotificationPermissionAction.REQUEST_PERMISSION -> "Allow notifications"
        NotificationPermissionAction.OPEN_APP_SETTINGS -> "Open app notification settings"
    }

internal fun ScreenHost.performNotificationPermissionAction() {
    when (notificationPermissionAction()) {
        NotificationPermissionAction.REQUEST_PERMISSION -> {
            app.markAutomaticNotificationPromptShown()
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        NotificationPermissionAction.OPEN_APP_SETTINGS ->
            app.startActivity(AppNotificationChannels.appSettingsIntent(app))
    }
}

internal fun ScreenHost.requestNotificationPermissionForDownload() {
    val activity = app
    val shouldRequest =
        NotificationSettingsPlanning.shouldRequestAutomatically(
            runtimePermissionRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
            permissionGranted = AppNotificationChannels.hasPostNotificationsPermission(activity),
            automaticPromptShown = activity.automaticNotificationPromptShown(),
        )
    if (!shouldRequest) return
    // The automatic prompt is reachable from lifecycleScope-backed sync coroutines that
    // survive the app being backgrounded (UpdateSyncOrchestrator/in-place sync call queueDownload
    // after network IO). launch() on a STOPPED activity won't show a usable dialog yet would
    // still consume the one-time prompt, so defer it to the next foregrounded download instead.
    if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || activity.isFinishing || activity.isDestroyed) return
    activity.markAutomaticNotificationPromptShown()
    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
}

private fun ScreenHost.notificationPermissionAction(): NotificationPermissionAction =
    NotificationSettingsPlanning.settingsAction(
        runtimePermissionRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
        permissionGranted = AppNotificationChannels.hasPostNotificationsPermission(app),
        automaticPromptShown = app.automaticNotificationPromptShown(),
    )

private fun Activity.automaticNotificationPromptShown(): Boolean =
    getSharedPreferences(NOTIFICATION_PERMISSION_PREFERENCES, Context.MODE_PRIVATE)
        .getBoolean(KEY_AUTOMATIC_NOTIFICATION_PROMPT_SHOWN, false)

private fun Activity.markAutomaticNotificationPromptShown() {
    getSharedPreferences(NOTIFICATION_PERMISSION_PREFERENCES, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_AUTOMATIC_NOTIFICATION_PROMPT_SHOWN, true)
        .apply()
}
