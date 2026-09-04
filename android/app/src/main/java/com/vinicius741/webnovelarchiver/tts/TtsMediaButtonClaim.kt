package com.vinicius741.webnovelarchiver.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Silent looping [AudioTrack] held while the engine speaks. TextToSpeech audio plays inside the
 * engine's process, so the system's audio-playback monitor attributes playback to the engine's uid
 * — and media-button routing (Bluetooth / wired play-pause) selects the target session by that uid.
 * Without this claim the session is never chosen and headset taps do nothing. A silent in-app track
 * makes OUR uid the active player, routing media buttons to the session like any music player.
 */
internal class TtsMediaButtonClaim {
    private var track: AudioTrack? = null

    /** Held exactly while audible playback is expected; released on pause/stop. */
    fun setSpeaking(speaking: Boolean) {
        if (speaking) start() else stop()
    }

    private fun start() {
        if (track != null) return
        val sampleRate = 8_000
        val frames = sampleRate / 2
        val silence = ByteArray(frames * 2)
        val created =
            runCatching {
                AudioTrack
                    .Builder()
                    .setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    ).setAudioFormat(
                        AudioFormat
                            .Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    ).setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(silence.size)
                    .build()
            }.getOrNull() ?: return
        runCatching {
            created.write(silence, 0, silence.size, AudioTrack.WRITE_BLOCKING)
            created.setLoopPoints(0, frames, -1)
            created.setVolume(0f)
            created.play()
        }.onFailure {
            runCatching { created.release() }
            return
        }
        track = created
    }

    fun stop() {
        track?.let {
            runCatching {
                it.stop()
                it.release()
            }
        }
        track = null
    }
}
