package com.vinicius741.webnovelarchiver.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import timber.log.Timber
import java.io.File

/** What a rendered sentence's audio depends on besides its text. */
internal fun ttsVoiceKey(
    engine: TextToSpeech,
    settings: TtsSettings,
): String = "${engine.defaultEngine}|${engine.voice?.name}|${settings.pitch}"

/** Chooses what narration plays through: rendered audio until a device proves it cannot do that. */
internal class TtsOutputs(
    private val context: Context,
    private val host: TtsSpeechEngineHost,
    private val scope: CoroutineScope,
    private val listener: TtsOutput.Listener,
    private val dispatch: (() -> Unit) -> Unit,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val renderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val synthesizer = TtsFileSynthesizer { host.engine }
    private var rendered: RenderedTtsOutput? = null
    private var live: LiveTtsOutput? = null

    var useLive = false
        private set

    /** Whether the current chapter has been handed to the output. */
    var loaded = false

    /** The output with a chapter loaded, if any. */
    val active: TtsOutput?
        get() =
            if (!loaded) {
                null
            } else if (useLive) {
                live
            } else {
                rendered
            }

    /** Readies the output for a new chapter, pointing the engine's callbacks at it. */
    fun prepare(): TtsOutput {
        val engine = host.engine
        if (useLive) {
            val output = live ?: LiveTtsOutput({ host.engine }, scope, listener, dispatch).also { live = it }
            engine?.setOnUtteranceProgressListener(output.progressListener)
            return output
        }
        val output =
            rendered
                ?: RenderedTtsOutput(
                    cache = TtsAudioCache(File(context.cacheDir, AUDIO_CACHE_DIR)),
                    synthesizer = synthesizer,
                    scope = renderScope,
                    listener = listener,
                    dispatch = dispatch,
                ).also { rendered = it }
        engine?.setOnUtteranceProgressListener(synthesizer.progressListener)
        return output
    }

    fun stopActive() {
        active?.stop()
        loaded = false
    }

    fun switchToLive() {
        Timber.w("TTS rendered audio unavailable; continuing with live speech")
        rendered?.release()
        rendered = null
        useLive = true
        loaded = false
    }

    fun release() {
        renderScope.cancel()
        rendered?.release()
        live?.release()
    }

    private companion object {
        const val AUDIO_CACHE_DIR = "tts_audio"
    }
}
