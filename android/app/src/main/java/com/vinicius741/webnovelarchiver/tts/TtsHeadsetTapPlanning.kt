package com.vinicius741.webnovelarchiver.tts

/**
 * Semantics for the shared headset/Bluetooth PLAY/PAUSE button: 1 tap toggles playback, 2 taps go
 * forward and 3+ go back. By default those are short skips, because catching something just missed
 * is what a listener usually wants and a whole chapter is costly to lose by accident.
 */
enum class TtsHeadsetTapAction {
    TogglePlayPause,
    SkipForward,
    SkipBack,
    NextChapter,
    PreviousChapter,
}

object TtsHeadsetTapPlanning {
    /** Window within which further taps join the same burst; matches common player defaults. */
    const val MULTI_TAP_WINDOW_MS = 300L
    const val SKIP_BACK_MS = 15_000L
    const val SKIP_FORWARD_MS = 30_000L

    /** Counts the initial key-down but rejects repeats emitted while the button stays held. */
    fun shouldCountKeyDown(repeatCount: Int): Boolean = repeatCount == 0

    /** Maps a completed burst of taps to its action. */
    fun actionForTapCount(
        tapCount: Int,
        chapterSkipButtons: Boolean = false,
    ): TtsHeadsetTapAction =
        when {
            tapCount <= 1 -> TtsHeadsetTapAction.TogglePlayPause
            tapCount == 2 -> forwardAction(chapterSkipButtons)
            else -> backAction(chapterSkipButtons)
        }

    fun forwardAction(chapterSkipButtons: Boolean): TtsHeadsetTapAction =
        if (chapterSkipButtons) TtsHeadsetTapAction.NextChapter else TtsHeadsetTapAction.SkipForward

    fun backAction(chapterSkipButtons: Boolean): TtsHeadsetTapAction =
        if (chapterSkipButtons) TtsHeadsetTapAction.PreviousChapter else TtsHeadsetTapAction.SkipBack
}
