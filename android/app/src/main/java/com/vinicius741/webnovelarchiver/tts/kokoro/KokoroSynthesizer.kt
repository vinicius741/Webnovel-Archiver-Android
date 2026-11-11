package com.vinicius741.webnovelarchiver.tts.kokoro

import com.vinicius741.webnovelarchiver.tts.TtsSpeechSynthesizer
import com.vinicius741.webnovelarchiver.tts.TtsWavWriter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/**
 * Renders with the built-in Kokoro model, always at 1x like the Android engine path, since speed is
 * applied at playback. Text the model cannot speak yields an empty clip, not a failure, so the
 * renderer's "only silence" guard decides whether the voice is broken.
 */
internal class KokoroSynthesizer(
    private val runtime: KokoroRuntime,
    private val speakerId: () -> Int,
) : TtsSpeechSynthesizer {
    override suspend fun synthesize(
        text: String,
        output: File,
    ): Boolean {
        currentCoroutineContext().ensureActive()
        val audio = runtime.generate(KokoroTextPlanning.spokenText(text), speakerId()) ?: return false
        // A voice change stops rendering before it changes the speaker, so audio that finished after
        // the stop may be in the new voice: never let it reach the cache under the old voice's key.
        currentCoroutineContext().ensureActive()
        output.writeBytes(TtsWavWriter.mono16(audio.samples, audio.sampleRate))
        return true
    }
}
