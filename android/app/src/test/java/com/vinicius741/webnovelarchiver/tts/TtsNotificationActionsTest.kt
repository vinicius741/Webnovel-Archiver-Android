package com.vinicius741.webnovelarchiver.tts

import com.vinicius741.webnovelarchiver.R
import org.junit.Assert.assertEquals
import org.junit.Test

class TtsNotificationActionsTest {
    @Test
    fun actionsShowPauseWhilePlaying() {
        val actions = TtsNotificationActions.actions(isPaused = false)

        assertEquals(
            listOf(R.string.tts_action_previous, R.string.tts_action_pause, R.string.tts_action_next, R.string.tts_action_stop),
            actions.map { it.labelResId },
        )
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

        assertEquals(
            listOf(R.string.tts_action_previous, R.string.tts_action_play, R.string.tts_action_next, R.string.tts_action_stop),
            actions.map { it.labelResId },
        )
        assertEquals(TtsNotificationActions.ACTION_RESUME_SESSION, actions[1].action)
    }
}
