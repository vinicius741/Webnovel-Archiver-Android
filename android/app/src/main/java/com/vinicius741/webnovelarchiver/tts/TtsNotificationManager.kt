package com.vinicius741.webnovelarchiver.tts

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.app.MainActivity
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
    ): Notification {
        val openIntent =
            PendingIntent.getActivity(
                context,
                11,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

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
                ?: snapshot?.let { chunkProgressText(it) }
                ?: context.getString(R.string.tts_notif_paused)

        val builder =
            NotificationCompat
                .Builder(context, AppNotificationCategory.TEXT_TO_SPEECH.channelId)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(openIntent)
                .setOngoing(snapshot != null)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        TtsNotificationActions.actions(isPaused).forEachIndexed { index, action ->
            builder.addAction(0, context.getString(action.labelResId), serviceAction(12 + index, action.action))
        }

        builder.setStyle(
            MediaStyle()
                .setMediaSession(mediaSessionToken)
                .setShowActionsInCompactView(*TtsNotificationActions.COMPACT_ACTION_INDICES),
        )
        return builder.build()
    }

    private fun chunkProgressText(snapshot: TtsPlaybackSnapshot): String =
        if (snapshot.totalChunks <= 0) {
            context.getString(R.string.tts_notif_buffering)
        } else {
            context.getString(
                R.string.tts_notif_chunk_progress,
                (snapshot.chunkIndex + 1).coerceIn(1, snapshot.totalChunks),
                snapshot.totalChunks,
            )
        }
}
