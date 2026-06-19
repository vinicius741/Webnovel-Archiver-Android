package com.vinicius741.webnovelarchiver.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsNotificationActionsTest {
    @Test
    fun actionsShowPauseWhilePlaying() {
        val actions = TtsNotificationActions.actions(isPaused = false)

        assertEquals(listOf("Previous", "Pause", "Next", "Stop"), actions.map { it.label })
        assertEquals(
            listOf(
                TtsNotificationActions.ACTION_PREVIOUS,
                TtsNotificationActions.ACTION_PAUSE,
                TtsNotificationActions.ACTION_NEXT,
                TtsNotificationActions.ACTION_STOP,
            ),
            actions.map { it.action },
        )
    }

    @Test
    fun actionsShowPlayMappedToResumeWhenPaused() {
        val actions = TtsNotificationActions.actions(isPaused = true)

        assertEquals(listOf("Previous", "Play", "Next", "Stop"), actions.map { it.label })
        assertEquals(TtsNotificationActions.ACTION_RESUME_SESSION, actions[1].action)
    }
}
