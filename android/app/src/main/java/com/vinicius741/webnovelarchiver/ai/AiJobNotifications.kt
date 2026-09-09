package com.vinicius741.webnovelarchiver.ai

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.app.MainActivity
import com.vinicius741.webnovelarchiver.notification.AppNotificationCategory

/** Notification presentation only. Each service retains its IDs, permission checks and lifecycle. */
internal fun Context.aiJobNotification(
    title: String,
    text: String,
    requestCode: Int,
    ongoing: Boolean,
): Notification {
    val openIntent =
        PendingIntent.getActivity(
            this,
            requestCode,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    return NotificationCompat
        .Builder(this, AppNotificationCategory.AI_GENERATION.channelId)
        .setSmallIcon(R.mipmap.ic_launcher)
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
