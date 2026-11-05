package com.vinicius741.webnovelarchiver.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsHeadsetTapPlanningTest {
    @Test
    fun onlyInitialKeyDownCountsAsATap() {
        assertEquals(true, TtsHeadsetTapPlanning.shouldCountKeyDown(0))
        assertEquals(false, TtsHeadsetTapPlanning.shouldCountKeyDown(1))
        assertEquals(false, TtsHeadsetTapPlanning.shouldCountKeyDown(4))
    }

    @Test
    fun singleTapTogglesPlayback() {
        assertEquals(TtsHeadsetTapAction.TogglePlayPause, TtsHeadsetTapPlanning.actionForTapCount(1))
    }

    @Test
    fun doubleTapSkipsForwardABit() {
        assertEquals(TtsHeadsetTapAction.SkipForward, TtsHeadsetTapPlanning.actionForTapCount(2))
    }

    @Test
    fun tripleTapGoesBackABit() {
        assertEquals(TtsHeadsetTapAction.SkipBack, TtsHeadsetTapPlanning.actionForTapCount(3))
    }

    @Test
    fun chapterButtonSettingMakesTapsMoveWholeChapters() {
        assertEquals(TtsHeadsetTapAction.NextChapter, TtsHeadsetTapPlanning.actionForTapCount(2, chapterSkipButtons = true))
        assertEquals(TtsHeadsetTapAction.PreviousChapter, TtsHeadsetTapPlanning.actionForTapCount(3, chapterSkipButtons = true))
        assertEquals(TtsHeadsetTapAction.TogglePlayPause, TtsHeadsetTapPlanning.actionForTapCount(1, chapterSkipButtons = true))
    }

    @Test
    fun degenerateCountsFallBackToToggleOrPrevious() {
        assertEquals(TtsHeadsetTapAction.TogglePlayPause, TtsHeadsetTapPlanning.actionForTapCount(0))
        assertEquals(TtsHeadsetTapAction.SkipBack, TtsHeadsetTapPlanning.actionForTapCount(4))
    }
}
