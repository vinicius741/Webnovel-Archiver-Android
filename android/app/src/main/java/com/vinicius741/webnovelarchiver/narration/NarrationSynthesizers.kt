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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
        val reached = reachServer(server) { client -> compatibleInfo(client, server) }
        // From here the synthesizer owns the client; it finds the computer again when a connection drops.
        return RemoteKokoroNarrationSynthesizer(
            reached.client,
            voice,
            speaker.speakerId,
            server,
            NarrationServerPlanning.inFlight(reached.value),
            reconnect = { reachServer(server) { client -> compatibleInfo(client, server) }.client },
        )
    }

    private suspend fun compatibleInfo(
        client: NarrationServerClient,
        server: NarrationServer,
    ): NarrationServerInfo =
        client.info().also { info ->
            NarrationServerPlanning.incompatibility(info, server.name)?.let { throw NarrationServerProblem(it) }
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
    initialClient: NarrationServerClient,
    override val voice: NarrationVoice,
    private val speakerId: Int,
    server: NarrationServer,
    private val maxInFlight: Int,
    /** Connects to the computer again, local network first, then Tailscale. */
    private val reconnect: suspend () -> NarrationServerClient,
    private val now: () -> Long = System::currentTimeMillis,
) : NarrationSynthesizer {
    @Volatile
    private var client: NarrationServerClient = initialClient
    private val rerouting = Mutex()

    override val voiceKey: String = KokoroVoices.voiceKey(speakerId)
    override val location: String = server.locationKey
    override val locationName: String = server.name
    private val waiting = MutableStateFlow<String?>(null)
    override val waitingFor: StateFlow<String?> = waiting

    /** Sentences waiting out a stopped server, and since when the server has been stopped. */
    private val stoppedWaiters = AtomicInteger()
    private val stoppedSince = AtomicLong(0L)

    /**
     * Follows the server: one sentence more than the slots it allows right now (so a slot never idles
     * while the next request travels), never more than at the start.
     */
    override val parallelism: Int
        get() = client.serverSlots?.let { (it + 1).coerceIn(1, maxInFlight) } ?: maxInFlight

    /** One sentence's part in [stoppedWaiters]. */
    private class StopWait {
        var counted = false
    }

    override suspend fun synthesize(
        text: String,
        output: File,
    ): TtsPcmClip? {
        val spoken = KokoroTextPlanning.spokenText(text)
        // Nothing left to say (only marks): the same empty clip the phone's Kokoro gives.
        if (spoken.isBlank()) return TtsPcmClip(REMOTE_SAMPLE_RATE, ByteArray(0))
        val stop = StopWait()
        try {
            return generate(spoken, stop)
        } finally {
            if (stop.counted && stoppedWaiters.decrementAndGet() == 0) waiting.value = null
        }
    }

    private suspend fun generate(
        spoken: String,
        stop: StopWait,
    ): TtsPcmClip? {
        var retriedTransient = false
        while (true) {
            val current = client
            try {
                return current.synthesize(spoken, speakerId).also { stoppedSince.set(0L) }
            } catch (busy: NarrationServerBusy) {
                waitOut(busy, stop)
            } catch (problem: NarrationServerProblem) {
                // A dropped connection or a Wi-Fi hiccup gets one more try before generation pauses,
                // on whichever address answers now: the phone may have left the home network.
                if (!problem.transient || retriedTransient) throw problem
                retriedTransient = true
                delay(TRANSIENT_RETRY_MS)
                reroute(current)
            }
        }
    }

    /**
     * Replaces [failed] with a fresh connection on the first address that answers. Sentences in flight
     * fail together, so only the first reconnects; the rest retry on its client. Throws the
     * [NarrationServerProblem] that pauses generation when no address answers.
     */
    private suspend fun reroute(failed: NarrationServerClient) {
        rerouting.withLock {
            if (client !== failed) return
            client = reconnect()
        }
        failed.close()
    }

    /**
     * The server protects its machine's memory: wait as long as it asks, as often as it asks. Only a
     * stop is shown; fewer slots just make sentences take turns. After [MAX_STOPPED_WAIT_MS] of a
     * stop, generation pauses instead, so hours of waiting do not keep the phone awake and polling.
     */
    private suspend fun waitOut(
        busy: NarrationServerBusy,
        stop: StopWait,
    ) {
        if (busy.stopped) {
            if (!stop.counted) {
                stop.counted = true
                stoppedWaiters.incrementAndGet()
            }
            waiting.value = busy.message
            stoppedSince.compareAndSet(0L, now())
            if (now() - stoppedSince.get() >= MAX_STOPPED_WAIT_MS) {
                throw NarrationServerProblem("${busy.message}. Resume when it has memory again", cause = busy)
            }
        }
        delay(busy.retryAfterMs)
    }

    override fun close() = client.close()

    private companion object {
        const val REMOTE_SAMPLE_RATE = 24_000
        const val TRANSIENT_RETRY_MS = 2_000L
        const val MAX_STOPPED_WAIT_MS = 15 * 60_000L
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
