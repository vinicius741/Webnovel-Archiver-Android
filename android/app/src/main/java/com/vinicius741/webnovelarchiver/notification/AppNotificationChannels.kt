package com.vinicius741.webnovelarchiver.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.vinicius741.webnovelarchiver.R

internal enum class AppNotificationCategory(
    val channelId: String,
    @StringRes val nameRes: Int,
    @StringRes val descriptionRes: Int,
    val permissionExempt: Boolean,
) {
    DOWNLOADS(
        channelId = "webnovel_downloads",
        nameRes = R.string.download_channel_name,
        descriptionRes = R.string.download_channel_desc,
        permissionExempt = false,
    ),
    TEXT_TO_SPEECH(
        channelId = "webnovel_tts",
        nameRes = R.string.tts_channel_name,
        descriptionRes = R.string.tts_channel_desc,
        permissionExempt = true,
    ),
}

internal enum class NotificationCategoryStatus(
    val label: String,
) {
    ENABLED("Enabled"),
    OFF_FOR_CATEGORY("Off for this category"),
    BLOCKED_BY_APP_PERMISSION("Blocked by app permission"),
}

internal enum class NotificationPermissionAction {
    REQUEST_PERMISSION,
    OPEN_APP_SETTINGS,
}

/** Pure notification decisions kept separate from Android channel and permission APIs. */
internal object NotificationSettingsPlanning {
    fun categoryStatus(
        category: AppNotificationCategory,
        appNotificationsEnabled: Boolean,
        channelEnabled: Boolean,
    ): NotificationCategoryStatus =
        when {
            !channelEnabled -> NotificationCategoryStatus.OFF_FOR_CATEGORY
            !category.permissionExempt && !appNotificationsEnabled ->
                NotificationCategoryStatus.BLOCKED_BY_APP_PERMISSION
            else -> NotificationCategoryStatus.ENABLED
        }

    fun settingsAction(
        runtimePermissionRequired: Boolean,
        permissionGranted: Boolean,
        automaticPromptShown: Boolean,
    ): NotificationPermissionAction =
        if (runtimePermissionRequired && !permissionGranted && !automaticPromptShown) {
            NotificationPermissionAction.REQUEST_PERMISSION
        } else {
            NotificationPermissionAction.OPEN_APP_SETTINGS
        }

    fun shouldRequestAutomatically(
        runtimePermissionRequired: Boolean,
        permissionGranted: Boolean,
        automaticPromptShown: Boolean,
    ): Boolean = runtimePermissionRequired && !permissionGranted && !automaticPromptShown
}

/** Owns the app's stable notification-channel definitions and system-settings entry points. */
internal object AppNotificationChannels {
    fun ensureCreated(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channels =
            AppNotificationCategory.entries.map { category ->
                NotificationChannel(
                    category.channelId,
                    context.getString(category.nameRes),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(category.descriptionRes)
                }
            }
        manager.createNotificationChannels(channels)
    }

    fun status(
        context: Context,
        category: AppNotificationCategory,
    ): NotificationCategoryStatus {
        ensureCreated(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val channelEnabled = manager.getNotificationChannel(category.channelId)?.importance != NotificationManager.IMPORTANCE_NONE
        return NotificationSettingsPlanning.categoryStatus(
            category = category,
            appNotificationsEnabled = areAppNotificationsEnabled(context),
            channelEnabled = channelEnabled,
        )
    }

    fun areAppNotificationsEnabled(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun hasPostNotificationsPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun channelSettingsIntent(
        context: Context,
        category: AppNotificationCategory,
    ): Intent =
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, category.channelId)
        }

    fun appSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
}
