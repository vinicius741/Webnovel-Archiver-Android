package com.vinicius741.webnovelarchiver.tts

import java.util.Locale

/** Listening-time labels shared by the player screens and the notification. */
internal object TtsTimeFormat {
    private const val MS_PER_SECOND = 1_000L
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3_600L

    fun clock(ms: Long): String {
        val total = (ms.coerceAtLeast(0L) / MS_PER_SECOND)
        val hours = total / SECONDS_PER_HOUR
        val minutes = (total % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        val seconds = total % SECONDS_PER_MINUTE
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }
    }

    /** Real time left at the current speed, in the words a listener would use. */
    fun timeLeft(
        positionMs: Long,
        durationMs: Long,
        rate: Float,
    ): String {
        val leftMs = remainingAtRate(positionMs, durationMs, rate)
        val minutes = (leftMs + SECONDS_PER_MINUTE * MS_PER_SECOND - 1) / (SECONDS_PER_MINUTE * MS_PER_SECOND)
        return when {
            minutes < SECONDS_PER_MINUTE -> "$minutes min left"
            minutes % SECONDS_PER_MINUTE == 0L -> "${minutes / SECONDS_PER_MINUTE} h left"
            else -> "${minutes / SECONDS_PER_MINUTE} h ${minutes % SECONDS_PER_MINUTE} min left"
        }
    }

    fun remainingAtRate(
        positionMs: Long,
        durationMs: Long,
        rate: Float,
    ): Long = ((durationMs - positionMs).coerceAtLeast(0L) / rate.coerceAtLeast(MIN_RATE)).toLong()

    private const val MIN_RATE = 0.1f
}
