package com.vinicius741.webnovelarchiver.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi

class TtsAudioFocusManager(
    context: Context,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        /**
         * Focus came back after a transient loss. [afterCall] is true when a phone call (ringing
         * included, answered or not) happened during the interruption.
         */
        fun onFocusGained(afterCall: Boolean)

        fun onTransientFocusLoss()

        fun onPermanentFocusLoss()
    }

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Whether audio focus is currently held (i.e. playback is allowed). */
    private var hasFocus = false

    /**
     * Whether a focus request is registered with the system. Tracked separately from [hasFocus]
     * because a transient focus loss clears [hasFocus] but leaves the request registered (the
     * system promises to send AUDIOFOCUS_GAIN back). [abandon] must release the request whenever it
     * is registered, even during a transient loss — otherwise the app hogs focus while paused.
     */
    private var requestRegistered = false

    /** A call was seen during the current transient loss. */
    private var callDuringInterruption = false
    private var watchingForCall = false

    private val playbackCallback =
        object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                noteCallIf(
                    TtsInterruptionPlanning.isCallAudioMode(audioManager.mode) ||
                        configs.any { TtsInterruptionPlanning.isCallAudioUsage(it.audioAttributes.usage) },
                )
            }
        }

    private val modeListener: Any? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioManager.OnModeChangedListener { mode -> noteCallIf(TtsInterruptionPlanning.isCallAudioMode(mode)) }
        } else {
            null
        }

    private val focusListener =
        AudioManager.OnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    hasFocus = true
                    // Re-check: the call may have been the last thing to change.
                    noteCallIf(TtsInterruptionPlanning.isCallAudioMode(audioManager.mode))
                    val afterCall = callDuringInterruption
                    stopWatchingForCall()
                    callbacks.onFocusGained(afterCall)
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                -> {
                    // Request stays registered; the system will send AUDIOFOCUS_GAIN when the
                    // interrupting app finishes.
                    hasFocus = false
                    startWatchingForCall()
                    callbacks.onTransientFocusLoss()
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    // Permanent loss — the system has already dropped our request.
                    hasFocus = false
                    requestRegistered = false
                    stopWatchingForCall()
                    callbacks.onPermanentFocusLoss()
                }
            }
        }
    private val focusRequest =
        AudioFocusRequest
            .Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            ).setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(focusListener)
            .build()

    fun request(): Boolean {
        if (hasFocus) return true
        val result = audioManager.requestAudioFocus(focusRequest)
        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        requestRegistered = hasFocus
        return hasFocus
    }

    fun abandon() {
        stopWatchingForCall()
        // Release whenever a request is registered, even if focus is currently in a transient loss
        // (hasFocus == false). Releasing here is what stops the app from holding focus while paused.
        if (!requestRegistered) return
        audioManager.abandonAudioFocusRequest(focusRequest)
        requestRegistered = false
        hasFocus = false
    }

    private fun noteCallIf(isCall: Boolean) {
        if (isCall && watchingForCall) callDuringInterruption = true
    }

    /**
     * The ringer can take focus a moment before the audio mode switches, so the check runs at the
     * loss and again whenever the mode or the set of playing streams changes until focus returns.
     */
    private fun startWatchingForCall() {
        if (!watchingForCall) {
            watchingForCall = true
            callDuringInterruption = false
            audioManager.registerAudioPlaybackCallback(playbackCallback, mainHandler)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) addModeListener()
        }
        noteCallIf(
            TtsInterruptionPlanning.isCallAudioMode(audioManager.mode) ||
                audioManager.activePlaybackConfigurations.any {
                    TtsInterruptionPlanning.isCallAudioUsage(it.audioAttributes.usage)
                },
        )
    }

    private fun stopWatchingForCall() {
        if (!watchingForCall) return
        watchingForCall = false
        callDuringInterruption = false
        audioManager.unregisterAudioPlaybackCallback(playbackCallback)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) removeModeListener()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun addModeListener() {
        audioManager.addOnModeChangedListener(mainHandler::post, modeListener as AudioManager.OnModeChangedListener)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun removeModeListener() {
        audioManager.removeOnModeChangedListener(modeListener as AudioManager.OnModeChangedListener)
    }
}
