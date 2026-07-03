package com.vinicius741.webnovelarchiver.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

class TtsAudioFocusManager(
    context: Context,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onFocusGained()

        fun onTransientFocusLoss()

        fun onPermanentFocusLoss()
    }

    private val audioManager = context.getSystemService(AudioManager::class.java)

    /** Whether audio focus is currently held (i.e. playback is allowed). */
    private var hasFocus = false

    /**
     * Whether a focus request is registered with the system. Tracked separately from [hasFocus]
     * because a transient focus loss clears [hasFocus] but leaves the request registered (the
     * system promises to send AUDIOFOCUS_GAIN back). [abandon] must release the request whenever it
     * is registered, even during a transient loss — otherwise the app hogs focus while paused.
     */
    private var requestRegistered = false

    private val focusListener =
        AudioManager.OnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    hasFocus = true
                    callbacks.onFocusGained()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                -> {
                    // Request stays registered; the system will send AUDIOFOCUS_GAIN when the
                    // interrupting app finishes.
                    hasFocus = false
                    callbacks.onTransientFocusLoss()
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    // Permanent loss — the system has already dropped our request.
                    hasFocus = false
                    requestRegistered = false
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
        // Release whenever a request is registered, even if focus is currently in a transient loss
        // (hasFocus == false). Releasing here is what stops the app from holding focus while paused.
        if (!requestRegistered) return
        audioManager.abandonAudioFocusRequest(focusRequest)
        requestRegistered = false
        hasFocus = false
    }
}
