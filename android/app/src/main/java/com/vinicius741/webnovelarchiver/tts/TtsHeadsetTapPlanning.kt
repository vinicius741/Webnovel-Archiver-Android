package com.vinicius741.webnovelarchiver.tts

/**
 * Standard music-player semantics for the shared headset/Bluetooth PLAY/PAUSE button:
 * 1 tap toggles playback, 2 taps skip to the next chapter, 3+ go back one chapter.
 */
enum class TtsHeadsetTapAction {
    TogglePlayPause,
    NextChapter,
    PreviousChapter,
}

object TtsHeadsetTapPlanning {
    /** Window within which further taps join the same burst; matches common player defaults. */
    const val MULTI_TAP_WINDOW_MS = 300L

    /** Counts the initial key-down but rejects repeats emitted while the button stays held. */
    fun shouldCountKeyDown(repeatCount: Int): Boolean = repeatCount == 0

    /** Maps a completed burst of taps to its action. */
    fun actionForTapCount(tapCount: Int): TtsHeadsetTapAction =
        when {
            tapCount <= 1 -> TtsHeadsetTapAction.TogglePlayPause
            tapCount == 2 -> TtsHeadsetTapAction.NextChapter
            else -> TtsHeadsetTapAction.PreviousChapter
        }
}
