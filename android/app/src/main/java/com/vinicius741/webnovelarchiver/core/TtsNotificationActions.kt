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
