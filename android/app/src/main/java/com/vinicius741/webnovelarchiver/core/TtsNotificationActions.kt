package com.vinicius741.webnovelarchiver.core

data class TtsNotificationAction(
    val label: String,
    val action: String,
)

object TtsNotificationActions {
    const val ACTION_START = "com.vinicius741.webnovelarchiver.tts.START"
    const val ACTION_RESUME_SESSION = "com.vinicius741.webnovelarchiver.tts.RESUME_SESSION"
    const val ACTION_PAUSE = "com.vinicius741.webnovelarchiver.tts.PAUSE"
    const val ACTION_NEXT = "com.vinicius741.webnovelarchiver.tts.NEXT"
    const val ACTION_PREVIOUS = "com.vinicius741.webnovelarchiver.tts.PREVIOUS"
    const val ACTION_STOP = "com.vinicius741.webnovelarchiver.tts.STOP"

    /** Play-pause toggle surfaced from the MediaSession callback (parity gaps 1 & 2). */
    const val ACTION_PLAY_PAUSE = "com.vinicius741.webnovelarchiver.tts.PLAY_PAUSE"

    /** Compact-view action indexes (Prev / Play-Pause / Next) shown in the lock-screen media card.
     *  Spread into [androidx.media.app.NotificationCompat.MediaStyle.setShowActionsInCompactView]. */
    val COMPACT_ACTION_INDICES: IntArray = intArrayOf(0, 1, 2)

    fun actions(isPaused: Boolean): List<TtsNotificationAction> =
        listOf(
            TtsNotificationAction("Previous", ACTION_PREVIOUS),
            if (isPaused) {
                TtsNotificationAction("Play", ACTION_RESUME_SESSION)
            } else {
                TtsNotificationAction("Pause", ACTION_PAUSE)
            },
            TtsNotificationAction("Next", ACTION_NEXT),
            TtsNotificationAction("Stop", ACTION_STOP),
        )
}
