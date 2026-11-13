package com.vinicius741.webnovelarchiver.tts

import android.media.AudioAttributes
import android.media.AudioManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsInterruptionPlanningTest {
    @Test
    fun ringingAndCallModesCountAsACall() {
        listOf(
            AudioManager.MODE_RINGTONE,
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_IN_COMMUNICATION,
            4, // MODE_CALL_SCREENING
            5, // MODE_CALL_REDIRECT
            6, // MODE_COMMUNICATION_REDIRECT
        ).forEach { assertTrue("mode $it", TtsInterruptionPlanning.isCallAudioMode(it)) }
    }

    @Test
    fun normalModeIsNotACall() {
        assertFalse(TtsInterruptionPlanning.isCallAudioMode(AudioManager.MODE_NORMAL))
    }

    @Test
    fun ringtoneAndVoipAudioCountAsACall() {
        assertTrue(TtsInterruptionPlanning.isCallAudioUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE))
        assertTrue(TtsInterruptionPlanning.isCallAudioUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION))
    }

    @Test
    fun navigationAndAssistantAudioAreNotACall() {
        assertFalse(TtsInterruptionPlanning.isCallAudioUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE))
        assertFalse(TtsInterruptionPlanning.isCallAudioUsage(AudioAttributes.USAGE_ASSISTANT))
        assertFalse(TtsInterruptionPlanning.isCallAudioUsage(AudioAttributes.USAGE_MEDIA))
        assertFalse(TtsInterruptionPlanning.isCallAudioUsage(AudioAttributes.USAGE_NOTIFICATION))
    }
}
