package com.vinicius741.webnovelarchiver.tts.kokoro

import com.vinicius741.webnovelarchiver.tts.TtsSpeechSynthesizer
import com.vinicius741.webnovelarchiver.tts.TtsWavWriter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/**
 * Renders with a built-in model (Kokoro or Phonon), always at 1x like the Android engine path, since speed is
 * applied at playback. Text the model cannot speak yields an empty clip, not a failure, so the
 * renderer's "only silence" guard decides whether the voice is broken.
 */
internal class KokoroSynthesizer(
    /** The runtime of a voice's own model, so a voice never reaches another model's runtime. */
    private val runtime: (BuiltInModel) -> KokoroRuntime,
    private val voice: () -> KokoroVoice,
) : TtsSpeechSynthesizer {
    override suspend fun synthesize(
        text: String,
        output: File,
    ): Boolean {
        currentCoroutineContext().ensureActive()
        val speaker = voice()
        val audio = runtime(speaker.model).generate(speaker.model.spokenText(text), speaker) ?: return false
        // A voice change stops rendering before it changes the speaker, so audio that finished after
        // the stop may be in the new voice: never let it reach the cache under the old voice's key.
        currentCoroutineContext().ensureActive()
        output.writeBytes(TtsWavWriter.mono16(audio.samples, audio.sampleRate))
        return true
    }
}
