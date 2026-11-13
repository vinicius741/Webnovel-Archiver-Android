package com.vinicius741.webnovelarchiver.tts

import android.media.AudioAttributes
import android.media.AudioManager

/**
 * Tells a phone call apart from other brief audio interruptions (a navigation prompt, a voice
 * assistant). Both arrive as the same transient focus loss, so the call is recognized from the
 * device audio mode and from what else is playing. A call leaves narration paused when it ends;
 * other interruptions resume on their own.
 */
object TtsInterruptionPlanning {
    /**
     * Literal values of AudioManager.MODE_CALL_SCREENING (API 30), MODE_CALL_REDIRECT and
     * MODE_COMMUNICATION_REDIRECT (API 33), so the check needs no version guard on minSdk 26.
     */
    private const val MODE_CALL_SCREENING = 4
    private const val MODE_CALL_REDIRECT = 5
    private const val MODE_COMMUNICATION_REDIRECT = 6

    /** Ringing, in a cellular call, in a VoIP call, or the call screener answering. */
    fun isCallAudioMode(mode: Int): Boolean =
        when (mode) {
            AudioManager.MODE_RINGTONE,
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_IN_COMMUNICATION,
            MODE_CALL_SCREENING,
            MODE_CALL_REDIRECT,
            MODE_COMMUNICATION_REDIRECT,
            -> true
            else -> false
        }

    /** Audio that only plays during a call: the ringtone, or VoIP call audio. */
    fun isCallAudioUsage(usage: Int): Boolean =
        usage == AudioAttributes.USAGE_NOTIFICATION_RINGTONE ||
            usage == AudioAttributes.USAGE_VOICE_COMMUNICATION
}
