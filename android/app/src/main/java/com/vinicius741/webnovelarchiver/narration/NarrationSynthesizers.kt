package com.vinicius741.webnovelarchiver.narration

import android.content.Context
import android.speech.tts.TextToSpeech
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import com.vinicius741.webnovelarchiver.tts.TtsEngineCatalog
import com.vinicius741.webnovelarchiver.tts.TtsErrorPlanning
import com.vinicius741.webnovelarchiver.tts.TtsFileSynthesizer
import com.vinicius741.webnovelarchiver.tts.TtsPcmClip
import com.vinicius741.webnovelarchiver.tts.TtsPcmPlanning
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackError
import com.vinicius741.webnovelarchiver.tts.TtsSettingsApplier
import com.vinicius741.webnovelarchiver.tts.TtsWavWriter
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroModelFiles
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroRuntime
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroTextPlanning
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroVoices
import com.vinicius741.webnovelarchiver.tts.ttsVoiceKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** Produces mono 16-bit PCM in one fixed voice. Android engines need a temporary WAV file. */
internal interface NarrationSynthesizer {
    val voice: NarrationVoice

    /** What the audio depends on besides its text; equal keys mean interchangeable clips. */
    val voiceKey: String

    /** Native inference threads, when the app controls the engine's parallelism. */
    val inferenceThreads: Int? get() = null

    /** Where it generates: null on this phone, else the server's [NarrationServer.locationKey]. */
    val location: String? get() = null

    /** Sentences it can generate at once; the generator keeps this many in flight. */
    val parallelism: Int get() = 1

    /** The computer's name when it generates on one, for the screens. */
    val locationName: String? get() = null

    /** Re-fits the engine to the device between sentences (Kokoro: its thread count to the cores Android allows now). */
    suspend fun fitToDevice() = Unit

    suspend fun synthesize(
        text: String,
        output: File,
    ): TtsPcmClip?

    fun close()
}

/** Why a voice cannot generate; shown on the failed job. */
internal class NarrationVoiceUnavailable(
    message: String,
) : Exception(message)

private fun unavailable(message: String): Nothing = throw NarrationVoiceUnavailable(message)

/** Opens synthesizers for generation. Unlike playback, it never substitutes another engine. */
internal fun interface NarrationSynthesizerFactory {
    /**
     * Throws [NarrationVoiceUnavailable] when [voice] cannot speak on this device right now, and
     * [NarrationServerProblem] when the chosen server cannot be used.
     */
    suspend fun open(voice: NarrationVoice): NarrationSynthesizer

    /** Where [voice] generates now (see [NarrationSynthesizer.location]); a change reopens the synthesizer. */
    fun location(voice: NarrationVoice): String? = null
}

/** Identifies a server's current settings, so editing its address or token reconnects. */
internal val NarrationServer.locationKey: String get() = "$id|$address|$token"

internal class AndroidNarrationSynthesizers(
    private val context: Context,
    private val kokoro: KokoroRuntime,
    /** The server Kokoro chapters generate on, or null for this phone. */
    private val server: () -> NarrationServer? = { null },
) : NarrationSynthesizerFactory {
    override suspend fun open(voice: NarrationVoice): NarrationSynthesizer {
        if (!KokoroVoices.isEngine(voice.engine)) return openAndroid(voice)
        return server()?.let { openRemote(voice, it) } ?: openKokoro(voice)
    }

    // Only Kokoro can run on a computer; other voices are Android apps on this phone.
    override fun location(voice: NarrationVoice): String? = if (KokoroVoices.isEngine(voice.engine)) server()?.locationKey else null

    private suspend fun openRemote(
        voice: NarrationVoice,
        server: NarrationServer,
    ): NarrationSynthesizer {
        val speaker = kokoroSpeaker(voice)
        val endpoint =
            NarrationServerEndpoint.parse(server.address)
                ?: throw NarrationServerProblem("${server.name}'s address \"${server.address}\" is not valid")
        val client = NarrationServerClient(endpoint, server.token, server.name)
        val info = client.info()
        NarrationServerPlanning.incompatibility(info, server.name)?.let { throw NarrationServerProblem(it) }
        return RemoteKokoroNarrationSynthesizer(client, voice, speaker.speakerId, server, NarrationServerPlanning.inFlight(info))
    }

    private fun kokoroSpeaker(voice: NarrationVoice) =
        (if (voice.voiceId == null) KokoroVoices.default else KokoroVoices.forIdentifier(voice.voiceId))
            ?: unavailable("Kokoro has no voice ${voice.voiceId}")

    private suspend fun openKokoro(voice: NarrationVoice): NarrationSynthesizer {
        if (!kokoro.isModelInstalled) unavailable("The Kokoro model is not installed")
        val speaker = kokoroSpeaker(voice)
        if (!kokoro.acquireFor(KokoroModelFiles(context)) { true }) {
            unavailable("The Kokoro model could not load")
        }
        return KokoroNarrationSynthesizer(kokoro, voice, speaker.speakerId)
    }

    private suspend fun openAndroid(voice: NarrationVoice): NarrationSynthesizer {
        val usable = TtsEngineCatalog.usableEngine(context, voice.engine, kokoro.isModelInstalled)
        if (usable != voice.engine) unavailable("The speech engine ${voice.engine} is not installed")
        val synthesizer = AndroidEngineSynthesizer(voice)
        val engine =
            withContext(Dispatchers.Main) {
                val ready = CompletableDeferred<Int>()
                TextToSpeech(context.applicationContext, { status -> ready.complete(status) }, voice.engine) to ready
            }
        val status = withTimeoutOrNull(ENGINE_START_TIMEOUT_MS) { engine.second.await() }
        val tts = engine.first
        if (status != TextToSpeech.SUCCESS) {
            tts.shutdown()
            unavailable("The speech engine ${voice.engine} did not start")
        }
        val settings = TtsSettings(pitch = voice.pitch, voiceIdentifier = voice.voiceId, enginePackage = voice.engine)
        var failure: TtsPlaybackError? = null
        val applied = withContext(Dispatchers.Main) { TtsSettingsApplier.apply(tts, settings, 1f) { failure = it } }
        if (!applied) {
            tts.shutdown()
            unavailable(failure?.let(TtsErrorPlanning::logMessage) ?: "The voice could not be set")
        }
        synthesizer.attach(tts, ttsVoiceKey(tts, voice.engine, settings))
        return synthesizer
    }

    private companion object {
        const val ENGINE_START_TIMEOUT_MS = 30_000L
    }
}

private class KokoroNarrationSynthesizer(
    private val runtime: KokoroRuntime,
    override val voice: NarrationVoice,
    private val speakerId: Int,
) : NarrationSynthesizer {
    override val voiceKey: String = KokoroVoices.voiceKey(speakerId)
    override val inferenceThreads: Int? get() = runtime.loadedThreads

    override suspend fun fitToDevice() {
        withContext(Dispatchers.IO) { runtime.refitThreads() }
    }

    private var released = false

    override suspend fun synthesize(
        text: String,
        output: File,
    ): TtsPcmClip? {
        currentCoroutineContext().ensureActive()
        val audio = runtime.generate(KokoroTextPlanning.spokenText(text), speakerId) ?: return null
        currentCoroutineContext().ensureActive()
        return TtsWavWriter.pcm16(audio.samples, audio.sampleRate)
    }

    override fun close() {
        if (released) return
        released = true
        runtime.releaseLater()
    }
}

/**
 * Kokoro on one of the user's computers. The server runs the same model, so its clips share the
 * phone's [voiceKey] and the two can be mixed within a chapter. The text is prepared here, exactly as
 * for the phone, so the server only speaks it.
 */
private class RemoteKokoroNarrationSynthesizer(
    private val client: NarrationServerClient,
    override val voice: NarrationVoice,
    private val speakerId: Int,
    server: NarrationServer,
    override val parallelism: Int,
) : NarrationSynthesizer {
    override val voiceKey: String = KokoroVoices.voiceKey(speakerId)
    override val location: String = server.locationKey
    override val locationName: String = server.name

    override suspend fun synthesize(
        text: String,
        output: File,
    ): TtsPcmClip? {
        val spoken = KokoroTextPlanning.spokenText(text)
        // Nothing left to say (only marks): the same empty clip the phone's Kokoro gives.
        if (spoken.isBlank()) return TtsPcmClip(REMOTE_SAMPLE_RATE, ByteArray(0))
        return try {
            client.synthesize(spoken, speakerId)
        } catch (problem: NarrationServerProblem) {
            if (!problem.transient) throw problem
            // A dropped connection or a Wi-Fi hiccup gets one more try before generation pauses.
            delay(TRANSIENT_RETRY_MS)
            client.synthesize(spoken, speakerId)
        }
    }

    override fun close() = Unit

    private companion object {
        const val REMOTE_SAMPLE_RATE = 24_000
        const val TRANSIENT_RETRY_MS = 2_000L
    }
}

private class AndroidEngineSynthesizer(
    override val voice: NarrationVoice,
) : NarrationSynthesizer {
    private var tts: TextToSpeech? = null
    private val files = TtsFileSynthesizer { tts }
    override var voiceKey: String = ""
        private set

    fun attach(
        engine: TextToSpeech,
        key: String,
    ) {
        tts = engine
        voiceKey = key
        engine.setOnUtteranceProgressListener(files.progressListener)
    }

    override suspend fun synthesize(
        text: String,
        output: File,
    ): TtsPcmClip? {
        if (!files.synthesize(text, output)) return null
        return output.takeIf(File::isFile)?.readBytes()?.let(TtsPcmPlanning::parseWav)
    }

    override fun close() {
        tts?.shutdown()
        tts = null
    }
}
