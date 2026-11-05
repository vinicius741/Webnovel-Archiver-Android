package com.vinicius741.webnovelarchiver.tts

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.app.openAppPendingIntent
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.notification.AppNotificationCategory

/**
 * Builds foreground media notifications for [TtsForegroundService], including compact transport
 * actions and MediaStyle session attachment.
 */
internal class TtsNotificationManager(
    private val context: Context,
) {
    @Suppress("SpreadOperator")
    fun buildNotification(
        snapshot: TtsPlaybackSnapshot?,
        lastErrorText: String?,
        mediaSessionToken: MediaSessionCompat.Token?,
        chapterSkipButtons: Boolean,
        cover: Bitmap?,
    ): Notification {
        val openIntent = context.openAppPendingIntent(11, AppRoute.Player)

        fun serviceAction(
            requestCode: Int,
            action: String,
        ): PendingIntent =
            PendingIntent.getService(
                context,
                requestCode,
                Intent(context, TtsForegroundService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        val isPaused = snapshot?.isPaused == true
        val title = snapshot?.title ?: context.getString(R.string.tts_notif_title)
        val body =
            lastErrorText
                ?: snapshot?.let { timeLeftText(it) }
                ?: context.getString(R.string.tts_notif_paused)

        val builder =
            NotificationCompat
                .Builder(context, AppNotificationCategory.TEXT_TO_SPEECH.channelId)
                .setSmallIcon(R.drawable.ic_stat_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(openIntent)
                .setOngoing(snapshot != null)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (cover != null) builder.setLargeIcon(cover)

        TtsNotificationActions.actions(isPaused, chapterSkipButtons).forEachIndexed { index, action ->
            builder.addAction(action.iconResId, context.getString(action.labelResId), serviceAction(12 + index, action.action))
        }

        builder.setStyle(
            MediaStyle()
                .setMediaSession(mediaSessionToken)
                .setShowActionsInCompactView(*TtsNotificationActions.COMPACT_ACTION_INDICES),
        )
        return builder.build()
    }

    private fun timeLeftText(snapshot: TtsPlaybackSnapshot): String =
        if (snapshot.durationMs <= 0L) {
            context.getString(R.string.tts_notif_buffering)
        } else {
            TtsTimeFormat.timeLeft(snapshot.positionAt(SystemClock.elapsedRealtime()), snapshot.durationMs, snapshot.rate)
        }
}
