package com.vinicius741.webnovelarchiver.feature.player

/** Speed presets cycled by the player's speed chip. */
internal val RATE_PRESETS = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 1.8f, 2.0f)

internal fun nextRatePreset(current: Float): Float = RATE_PRESETS.firstOrNull { it > current + 0.01f } ?: RATE_PRESETS.first()

internal fun rateLabel(rate: Float): String {
    val text = if (rate == rate.toInt().toFloat()) rate.toInt().toString() else rate.toString()
    return "${text}x"
}

internal fun sleepTimerLabel(
    targetEpochMs: Long?,
    endOfChapter: Boolean,
): String =
    when {
        targetEpochMs != null -> {
            val remMin = maxOf(1, ((targetEpochMs - System.currentTimeMillis() + 59_999) / 60_000).toInt())
            "Timer: ${remMin}m"
        }
        endOfChapter -> "Timer: Ch. End"
        else -> "Timer: Off"
    }
