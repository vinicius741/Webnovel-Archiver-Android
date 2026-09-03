package com.vinicius741.webnovelarchiver.tts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface TtsSleepTimerMode {
    data object Off : TtsSleepTimerMode

    data class Duration(
        val minutes: Int,
        val targetEpochMs: Long,
    ) : TtsSleepTimerMode

    data object EndOfChapter : TtsSleepTimerMode
}

/**
 * Manages the sleep timer countdown or end-of-chapter trigger for TTS playback.
 */
internal class TtsSleepTimer(
    private val scope: CoroutineScope,
    private val onTimerExpired: () -> Unit,
) {
    var mode: TtsSleepTimerMode = TtsSleepTimerMode.Off
        private set

    private var timerJob: Job? = null

    fun setOff() {
        timerJob?.cancel()
        timerJob = null
        mode = TtsSleepTimerMode.Off
    }

    fun setDuration(minutes: Int) {
        timerJob?.cancel()
        if (minutes <= 0) {
            setOff()
            return
        }
        val targetEpochMs = System.currentTimeMillis() + minutes * 60_000L
        mode = TtsSleepTimerMode.Duration(minutes, targetEpochMs)
        timerJob =
            scope.launch {
                delay(minutes * 60_000L)
                mode = TtsSleepTimerMode.Off
                onTimerExpired()
            }
    }

    fun setEndOfChapter() {
        timerJob?.cancel()
        timerJob = null
        mode = TtsSleepTimerMode.EndOfChapter
    }

    /** Called on chapter transition or chapter completion. Returns true if timer fired. */
    fun onChapterCompleted(): Boolean {
        if (mode is TtsSleepTimerMode.EndOfChapter) {
            mode = TtsSleepTimerMode.Off
            onTimerExpired()
            return true
        }
        return false
    }
}
