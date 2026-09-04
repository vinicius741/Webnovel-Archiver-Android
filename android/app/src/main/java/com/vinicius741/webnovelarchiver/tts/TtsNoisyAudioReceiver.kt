package com.vinicius741.webnovelarchiver.tts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat

/**
 * Standard-player pause on ACTION_AUDIO_BECOMING_NOISY: wired/Bluetooth output disconnected while
 * speaking → pause and keep the session. Registered exactly while playback is active.
 */
internal class TtsNoisyAudioReceiver(
    private val context: Context,
    private val onPause: () -> Unit,
) {
    private var registered = false
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                onPause()
            }
        }

    fun setActive(active: Boolean) {
        if (active == registered) return
        if (active) {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            registered = true
        } else {
            unregister()
        }
    }

    fun unregister() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }
}
