package com.vinicius741.webnovelarchiver.tts.kokoro

import android.system.Os
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.vinicius741.webnovelarchiver.tts.phonon.PhononTTS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One generated clip: mono float samples in -1..1. */
internal class KokoroAudio(
    val samples: FloatArray,
    val sampleRate: Int,
)

/** A loaded built-in model's native engine: sherpa-onnx's Kokoro, or Phonon's runtime. */
internal interface BuiltInEngine {
    /** Speaks [text] as [voice] at 1x. */
    fun generate(
        text: String,
        voice: KokoroVoice,
    ): KokoroAudio?

    fun release()
}

/**
 * A loaded built-in model ([builtIn]), shared by everything that speaks with it (playback, narration
 * and the settings sample), since each copy holds up to several hundred MB; `AppContainer` owns one
 * instance per model. It loads on the first [acquire] and is freed when the last user releases it.
 * All access is serialized: the native engines are not re-entrant, and freeing one mid-sentence
 * would crash.
 */
class KokoroRuntime internal constructor(
    /** Whether the model is downloaded, answered from memory (see `KokoroModelInstaller.isInstalled`). */
    private val modelInstalled: () -> Boolean,
    internal val builtIn: BuiltInModel,
) {
    constructor(modelInstalled: () -> Boolean = { false }) : this(modelInstalled, BuiltInModel.KOKORO_V019)

    internal val isModelInstalled: Boolean get() = modelInstalled()

    /** Freeing waits for a sentence in progress, so it never runs on the caller's (main) thread. */
    private val releaser = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "kokoro-release") }
    private val lock = Any()
    private var tts: BuiltInEngine? = null
    private var users = 0
    private var loadedFiles: KokoroModelFiles? = null

    /** Threads the loaded model computes on; null while it is not loaded. */
    @Volatile
    internal var loadedThreads: Int? = null
        private set

    /** Loads the model if needed (seconds; call off the main thread). False if it cannot load. */
    internal fun acquire(files: KokoroModelFiles): Boolean =
        synchronized(lock) {
            if (tts == null) {
                // Phonon's runtime sizes its pool once per process: always the same count, so the
                // number reported is the one it really uses.
                val count = if (builtIn.runtime == BuiltInRuntime.PHONON) PHONON_THREADS else KokoroThreads.forNow()
                tts = load(files, count) ?: return false
                loadedFiles = files
                loadedThreads = count
            }
            users++
            true
        }

    /**
     * [acquire] on the IO dispatcher for a caller that may be cancelled or replaced meanwhile. The load
     * cannot be interrupted, so its result is kept: if the caller is cancelled, or [stillWanted] (read
     * back on the caller's thread) says no, the reference is released instead of leaked.
     */
    internal suspend fun acquireFor(
        files: KokoroModelFiles,
        stillWanted: () -> Boolean,
    ): Boolean {
        val loaded = AtomicBoolean(false)
        try {
            withContext(Dispatchers.IO) { loaded.set(acquire(files)) }
        } finally {
            if (loaded.get() && !(currentCoroutineContext().isActive && stillWanted())) {
                loaded.set(false)
                releaseLater()
            }
        }
        return loaded.get()
    }

    internal fun releaseLater() {
        releaser.execute {
            synchronized(lock) {
                if (users == 0) return@synchronized
                users--
                if (users == 0) {
                    tts?.release()
                    tts = null
                    loadedThreads = null
                }
            }
        }
    }

    /** Speaks [text] as [voice] at 1x; null if the model is not loaded or the engine failed. */
    internal fun generate(
        text: String,
        voice: KokoroVoice,
    ): KokoroAudio? =
        synchronized(lock) {
            val engine = tts ?: return null
            runCatching { engine.generate(text, voice) }
                .onFailure { Timber.w(it, "%s generation failed", builtIn.dirName) }
                .getOrNull()
        }

    /** Whether narration or the settings sample currently holds the model in memory. */
    internal val isLoaded: Boolean get() = synchronized(lock) { tts != null }

    /**
     * Reloads the model when its thread count no longer fits the cores Android lets the app use
     * (seconds; off the main thread). In the background Android can confine the app to fewer cores,
     * and more compute threads than cores makes every step wait for a thread that is not running.
     * Returns whether it reloaded.
     */
    internal fun refitThreads(): Boolean =
        synchronized(lock) {
            val engine = tts ?: return false
            val files = loadedFiles ?: return false
            val previous = loadedThreads ?: return false
            val wanted = KokoroThreads.forNow()
            // Phonon's runtime sizes its thread pool once per process, so a reload would change nothing.
            if (wanted == previous || builtIn.runtime == BuiltInRuntime.PHONON) return false
            // One copy at a time: two would need over a gigabyte at once.
            engine.release()
            val reloaded = load(files, wanted)
            tts = reloaded ?: load(files, previous)
            loadedThreads = if (reloaded != null) wanted else previous.takeIf { tts != null }
            Timber.i("Kokoro reloaded with %d threads (was %d)", loadedThreads ?: 0, previous)
            reloaded != null
        }

    private fun load(
        files: KokoroModelFiles,
        count: Int,
    ): BuiltInEngine? = loadBuiltInEngine(files, count)

    internal companion object {
        /**
         * One per fast core (see [KokoroThreads]). Playback barely uses the CPU, and its thread runs at
         * audio priority, so it is not starved by generation on every core. The model loads with
         * [KokoroThreads.forNow], which also caps this at the cores the app may use at that moment.
         */
        val threads: Int get() = KokoroThreads.forThisPhone
    }
}

/**
 * Loads [files]' model on [numThreads] threads (seconds; off the main thread); null if it cannot
 * load. An UnsatisfiedLinkError, on a device without the native code, must surface as "cannot load",
 * not a crash.
 */
@Suppress("TooGenericExceptionCaught")
internal fun loadBuiltInEngine(
    files: KokoroModelFiles,
    numThreads: Int,
): BuiltInEngine? =
    when (files.builtIn.runtime) {
        BuiltInRuntime.SHERPA_KOKORO -> loadKokoroTts(files, numThreads)?.let(::SherpaKokoroEngine)
        BuiltInRuntime.PHONON ->
            try {
                // A CPU without the library's instructions would crash the whole process.
                if (!KokoroModelFiles.isDeviceSupported(BuiltInModel.PHONON)) return null
                // Read once, when the runtime first builds its thread pool; later loads keep that size,
                // so it is always set to one per fast core (the speed test's other counts are ignored).
                Os.setenv("RAYON_NUM_THREADS", PHONON_THREADS.toString(), true)
                PhononEngine(PhononTTS(files.dir.absolutePath, "en"))
            } catch (error: Throwable) {
                Timber.e(error, "Phonon model failed to load")
                null
            }
    }

private class SherpaKokoroEngine(
    private val tts: OfflineTts,
) : BuiltInEngine {
    override fun generate(
        text: String,
        voice: KokoroVoice,
    ): KokoroAudio {
        val audio = tts.generate(text, voice.speakerId, 1.0f)
        return KokoroAudio(audio.samples, audio.sampleRate)
    }

    override fun release() = tts.release()
}

/** Phonon through its C library. Its voice is part of the loaded handle, so it is switched only on change. */
private class PhononEngine(
    private val tts: PhononTTS,
) : BuiltInEngine {
    private var voice: String? = null

    override fun generate(
        text: String,
        voice: KokoroVoice,
    ): KokoroAudio {
        // Nothing to say (only marks): the same empty clip a server gives.
        if (text.isBlank()) return KokoroAudio(FloatArray(0), PHONON_SAMPLE_RATE)
        if (this.voice != voice.code) {
            tts.setVoice(voice.code)
            this.voice = voice.code
        }
        val chunks = mutableListOf<FloatArray>()
        tts.speak(text) { pcm ->
            chunks += pcm
            true
        }
        val samples = FloatArray(chunks.sumOf { it.size })
        var at = 0
        chunks.forEach { chunk ->
            chunk.copyInto(samples, at)
            at += chunk.size
        }
        return KokoroAudio(samples, PHONON_SAMPLE_RATE)
    }

    override fun release() = tts.close()

    private companion object {
        /** Phonon always speaks at 24 kHz (ptts.h). */
        const val PHONON_SAMPLE_RATE = 24_000
    }
}

/**
 * Loads a sherpa-onnx Kokoro engine that computes on [numThreads] threads (seconds; off the main
 * thread); null if it cannot load. Kokoro v1.0 reads English through its US lexicon first.
 */
@Suppress("TooGenericExceptionCaught")
internal fun loadKokoroTts(
    files: KokoroModelFiles,
    numThreads: Int,
): OfflineTts? =
    try {
        OfflineTts(
            assetManager = null,
            config =
                OfflineTtsConfig(
                    model =
                        OfflineTtsModelConfig(
                            kokoro =
                                OfflineTtsKokoroModelConfig(
                                    model = files.model.absolutePath,
                                    voices = files.voices.absolutePath,
                                    tokens = files.tokens.absolutePath,
                                    dataDir = files.dataDir.absolutePath,
                                    lexicon = if (files.builtIn == BuiltInModel.KOKORO_V1) files.lexicon.absolutePath else "",
                                ),
                            numThreads = numThreads,
                            debug = false,
                            provider = "cpu",
                        ),
                ),
        )
    } catch (error: Throwable) {
        Timber.e(error, "Kokoro model failed to load")
        null
    }

/**
 * Phonon's compute threads: one per fast core of the phone. Its runtime reads this once per process
 * (`RAYON_NUM_THREADS`), so it is fixed rather than refitted to the cores Android allows at the moment.
 */
internal val PHONON_THREADS: Int by lazy { KokoroThreads.forThisPhone }
