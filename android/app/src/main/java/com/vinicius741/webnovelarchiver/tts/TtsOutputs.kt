package com.vinicius741.webnovelarchiver.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroSynthesizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import timber.log.Timber
import java.io.File

/**
 * What a rendered sentence's audio depends on besides its text. [enginePackage] is the engine the
 * app chose, null when it follows the phone's; that case keeps the key audio was cached under before.
 */
internal fun ttsVoiceKey(
    engine: TextToSpeech,
    enginePackage: String?,
    settings: TtsSettings,
): String = "${enginePackage ?: engine.defaultEngine}|${engine.voice?.name}|${settings.pitch}"

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
    private val fileSynthesizer = TtsFileSynthesizer { host.engine }
    private val kokoroSynthesizer = KokoroSynthesizer(host.kokoro) { host.kokoroSpeaker }
    private val synthesizer =
        TtsSpeechSynthesizer { text, output ->
            if (host.isBuiltin) kokoroSynthesizer.synthesize(text, output) else fileSynthesizer.synthesize(text, output)
        }
    private var rendered: RenderedTtsOutput? = null
    private var live: LiveTtsOutput? = null
    private var liveRequested = false

    /** Live speech, once rendering proved impossible; the built-in model has no live mode. */
    val useLive: Boolean get() = liveRequested && !host.isBuiltin

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
        engine?.setOnUtteranceProgressListener(fileSynthesizer.progressListener)
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
        liveRequested = true
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
