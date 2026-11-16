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
import com.vinicius741.webnovelarchiver.tts.kokoro.BuiltInEngines
import com.vinicius741.webnovelarchiver.tts.kokoro.BuiltInModel
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroModelFiles
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroRuntime
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroVoice
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroVoices
import com.vinicius741.webnovelarchiver.tts.ttsVoiceKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** The computer's name when it generates on one (several joined by " + "), for the screens. */
    val locationName: String? get() = null

    /** Which computers generate now, for speed estimates: a server id, several joined by `+`, or null on this phone. */
    val speedPlace: String? get() = null

    /** Which computers generated this chapter, for its measured speed (see [speedPlace]). */
    val chapterSpeedPlace: String? get() = speedPlace

    /** This chapter's speed belongs to no one place (the phone stood in, or a computer dropped or rejoined): do not record it. */
    val chapterSpeedMixed: Boolean get() = false

    /** Starts counting a new chapter for [chapterSpeedPlace] and [chapterSpeedMixed]. */
    fun beginChapter() = Unit

    /**
     * Why generation is waiting on the engine right now (a computer holding back to protect its
     * memory), or null while it generates normally. Waiting resumes by itself.
     */
    val waitingFor: StateFlow<String?> get() = NOT_WAITING

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

private val NOT_WAITING: StateFlow<String?> = MutableStateFlow(null)

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

/** Identifies a server's current settings, so editing its addresses or token reconnects. */
internal val NarrationServer.locationKey: String get() = "$id|$address|$tailscaleAddress|$token"

internal class AndroidNarrationSynthesizers(
    private val context: Context,
    private val builtIns: BuiltInEngines,
    /** Where built-in chapters generate: the chosen computers, or none for this phone. */
    private val places: () -> NarrationPlaces = { NarrationPlaces() },
) : NarrationSynthesizerFactory {
    override suspend fun open(voice: NarrationVoice): NarrationSynthesizer {
        val model = KokoroVoices.model(voice.engine) ?: return openAndroid(voice)
        val chosen = places()
        return if (chosen.servers.isEmpty()) openKokoro(voice, model) else openRemote(voice, model, chosen)
    }

    // Only the built-in models can run on a computer; other voices are Android apps on this phone.
    override fun location(voice: NarrationVoice): String? = if (KokoroVoices.isEngine(voice.engine)) places().locationKey else null

    /** The chosen computers share each chapter; this phone stands in while none can generate, when allowed. */
    private suspend fun openRemote(
        voice: NarrationVoice,
        model: BuiltInModel,
        chosen: NarrationPlaces,
    ): NarrationSynthesizer {
        val speaker = kokoroSpeaker(voice, model)
        val remotes =
            chosen.servers.map { server ->
                RemoteKokoroPlace(server, speaker) { reachServer(server) { client -> compatibleInfo(client, server, speaker) } }
            }
        return NarrationPlacePool.open(
            voice = voice,
            voiceKey = KokoroVoices.voiceKey(speaker),
            location = checkNotNull(chosen.locationKey),
            places = remotes,
            // Standing in needs the model on this phone; without it, generation waits for a computer.
            phone = if (chosen.phoneFallback && builtIns.isInstalled(model)) ({ openKokoro(voice, model) }) else null,
            scope = CoroutineScope(Dispatchers.IO),
        )
    }

    private suspend fun compatibleInfo(
        client: NarrationServerClient,
        server: NarrationServer,
        speaker: KokoroVoice,
    ): NarrationServerInfo =
        client.info(speaker.model.dirName).also { info ->
            NarrationServerPlanning.incompatibility(info, server.name, speaker)?.let { throw NarrationServerProblem(it) }
        }

    private fun kokoroSpeaker(
        voice: NarrationVoice,
        model: BuiltInModel,
    ): KokoroVoice =
        (if (voice.voiceId == null) model.defaultVoice else KokoroVoices.forIdentifier(voice.voiceId)?.takeIf { it.model == model })
            ?: unavailable("${model.family} has no voice ${voice.voiceId}")

    private suspend fun openKokoro(
        voice: NarrationVoice,
        model: BuiltInModel,
    ): NarrationSynthesizer {
        val runtime = builtIns.runtime(model)
        if (!runtime.isModelInstalled) unavailable("The ${model.family} model is not installed")
        val speaker = kokoroSpeaker(voice, model)
        if (!runtime.acquireFor(KokoroModelFiles(context, model)) { true }) {
            unavailable("The ${model.family} model could not load")
        }
        return KokoroNarrationSynthesizer(runtime, voice, speaker)
    }

    private suspend fun openAndroid(voice: NarrationVoice): NarrationSynthesizer {
        val usable = TtsEngineCatalog.usableEngine(context, voice.engine, builtIns.installedEngines)
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
    private val speaker: KokoroVoice,
) : NarrationSynthesizer {
    override val voiceKey: String = KokoroVoices.voiceKey(speaker)
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
        val audio = runtime.generate(speaker.model.spokenText(text), speaker) ?: return null
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
 * A built-in model on one of the user's computers, as one place of a [NarrationPlacePool]. The server
 * runs the same model, so its clips share the phone's voice key and the two can be mixed within a
 * chapter. The text is prepared here, exactly as for the phone, so the server only speaks it.
 */
private class RemoteKokoroPlace(
    private val server: NarrationServer,
    private val speaker: KokoroVoice,
    /** Connects to the computer, local network first, then Tailscale. */
    private val reach: suspend () -> NarrationServerReached<NarrationServerInfo>,
) : NarrationPlace {
    @Volatile
    private var client: NarrationServerClient? = null

    @Volatile
    private var maxInFlight = NarrationServerPlanning.MAX_IN_FLIGHT
    private val rerouting = Mutex()

    /** A client whose reconnection already failed, so the other sentences on it fail at once instead of each trying again. */
    private var deadClient: NarrationServerClient? = null
    private var deadProblem: NarrationServerProblem? = null

    override val id: String get() = server.id
    override val name: String get() = server.name

    /**
     * Follows the server: one sentence more than the slots it allows right now (so a slot never idles
     * while the next request travels), never more than at the start.
     */
    override val slots: Int
        get() = client?.serverSlots?.let { (it + 1).coerceIn(1, maxInFlight) } ?: maxInFlight

    override suspend fun connect(checkMemory: Boolean) {
        rerouting.withLock {
            val reached = reach()
            if (checkMemory) {
                // A computer stopped for memory answers, but cannot generate yet.
                val stopped =
                    try {
                        NarrationServerPlanning.memoryProblem(reached.client.memoryLevel())
                    } catch (problem: NarrationServerProblem) {
                        reached.client.close()
                        throw problem
                    }
                if (stopped != null) {
                    reached.client.close()
                    throw NarrationServerProblem("${server.name} $stopped", transient = true)
                }
            }
            adopt(reached)
        }
    }

    override suspend fun synthesize(text: String): TtsPcmClip? {
        val spoken = speaker.model.spokenText(text)
        // Nothing left to say (only marks): the same empty clip the phone gives.
        if (spoken.isBlank()) return TtsPcmClip(REMOTE_SAMPLE_RATE, ByteArray(0))
        var retriedTransient = false
        while (true) {
            val current = client ?: throw NarrationServerProblem("Not connected to ${server.name}", transient = true)
            try {
                return current.synthesize(spoken, speaker)
            } catch (busy: NarrationServerBusy) {
                // The pool decides when to ask again, and may send the sentence elsewhere meanwhile.
                throw busy
            } catch (problem: NarrationServerProblem) {
                // A dropped connection or a Wi-Fi hiccup gets one more try, on whichever address answers
                // now: the phone may have left the home network.
                if (!problem.transient || retriedTransient) throw problem
                retriedTransient = true
                delay(TRANSIENT_RETRY_MS)
                reroute(current)
            }
        }
    }

    /**
     * Replaces [failed] with a fresh connection on the first address that answers. Sentences in flight
     * fail together, so only the first reconnects; the rest retry on its client, or fail at once when
     * that reconnection failed.
     */
    private suspend fun reroute(failed: NarrationServerClient) {
        rerouting.withLock {
            if (client !== failed) return
            if (deadClient === failed) throw checkNotNull(deadProblem)
            try {
                adopt(reach())
            } catch (problem: NarrationServerProblem) {
                deadClient = failed
                deadProblem = problem
                throw problem
            }
        }
    }

    private fun adopt(reached: NarrationServerReached<NarrationServerInfo>) {
        val previous = client
        client = reached.client
        maxInFlight = NarrationServerPlanning.inFlight(reached.value)
        deadClient = null
        deadProblem = null
        previous?.close()
    }

    override fun close() {
        client?.close()
    }

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
