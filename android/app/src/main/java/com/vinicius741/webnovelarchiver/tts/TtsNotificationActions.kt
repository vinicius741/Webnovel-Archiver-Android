package com.vinicius741.webnovelarchiver.tts

import androidx.annotation.StringRes
import com.vinicius741.webnovelarchiver.R

data class TtsNotificationAction(
    @StringRes val labelResId: Int,
    val action: String,
)

object TtsNotificationActions {
    const val ACTION_START = "com.vinicius741.webnovelarchiver.tts.START"
    const val ACTION_RESUME_SESSION = "com.vinicius741.webnovelarchiver.tts.RESUME_SESSION"
    const val ACTION_PAUSE = "com.vinicius741.webnovelarchiver.tts.PAUSE"
    const val ACTION_NEXT = "com.vinicius741.webnovelarchiver.tts.NEXT"
    const val ACTION_PREVIOUS = "com.vinicius741.webnovelarchiver.tts.PREVIOUS"
    const val ACTION_STOP = "com.vinicius741.webnovelarchiver.tts.STOP"

    /** Chapter-level skips: the notification + media buttons use podcast episode semantics. */
    const val ACTION_NEXT_CHAPTER = "com.vinicius741.webnovelarchiver.tts.NEXT_CHAPTER"

    const val ACTION_PREVIOUS_CHAPTER = "com.vinicius741.webnovelarchiver.tts.PREVIOUS_CHAPTER"

    const val ACTION_SET_RATE = "com.vinicius741.webnovelarchiver.tts.SET_RATE"

    const val EXTRA_RATE = "rate"

    /** Stop that also forgets the story's saved position (variant switch remaps chunk indices). */
    const val EXTRA_FORGET_POSITION = "forgetPosition"

    /** Play-pause toggle surfaced from the MediaSession callback (parity gaps 1 & 2). */
    const val ACTION_PLAY_PAUSE = "com.vinicius741.webnovelarchiver.tts.PLAY_PAUSE"

    /** Compact-view action indexes (Prev / Play-Pause / Next) shown in the lock-screen media card.
     *  Spread into [androidx.media.app.NotificationCompat.MediaStyle.setShowActionsInCompactView]. */
    val COMPACT_ACTION_INDICES: IntArray = intArrayOf(0, 1, 2)

    fun actions(isPaused: Boolean): List<TtsNotificationAction> =
        listOf(
            TtsNotificationAction(R.string.tts_action_previous_chapter, ACTION_PREVIOUS_CHAPTER),
            if (isPaused) {
                TtsNotificationAction(R.string.tts_action_play, ACTION_RESUME_SESSION)
            } else {
                TtsNotificationAction(R.string.tts_action_pause, ACTION_PAUSE)
            },
            TtsNotificationAction(R.string.tts_action_next_chapter, ACTION_NEXT_CHAPTER),
            TtsNotificationAction(R.string.tts_action_stop, ACTION_STOP),
        )
}
