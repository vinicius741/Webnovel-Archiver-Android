package com.vinicius741.webnovelarchiver.ai

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.app.openAppPendingIntent
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.notification.AppNotificationCategory

internal fun Context.aiJobOngoingNotification(
    message: String,
    storyId: String?,
): Notification =
    aiJobNotification(
        getString(R.string.ai_cover_notif_active),
        message,
        requestCode = 22,
        ongoing = true,
        route = storyId?.let(AppRoute::AiControls),
    )

/** Notification presentation only. Each service retains its IDs, permission checks and lifecycle. */
internal fun Context.aiJobNotification(
    title: String,
    text: String,
    requestCode: Int,
    ongoing: Boolean,
    route: AppRoute? = null,
): Notification {
    val openIntent = openAppPendingIntent(requestCode, route)
    return NotificationCompat
        .Builder(this, AppNotificationCategory.AI_GENERATION.channelId)
        .setSmallIcon(R.drawable.ic_stat_notification)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setContentIntent(openIntent)
        .setOnlyAlertOnce(true)
        .setOngoing(ongoing)
        .setAutoCancel(!ongoing)
        .apply { if (ongoing) setProgress(0, 0, true) }
        .build()
}
