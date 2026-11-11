package com.vinicius741.webnovelarchiver.tts.kokoro

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
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

/**
 * The loaded Kokoro model, shared by everything that speaks with it (playback and the settings
 * sample), since each copy holds several hundred MB; `AppContainer` owns the one instance. It loads
 * on the first [acquire] and is freed when the last user releases it. All access is serialized: the
 * native engine is not re-entrant, and freeing it mid-sentence would crash.
 */
class KokoroRuntime(
    /** Whether the model is downloaded, answered from memory (see `KokoroModelInstaller.isInstalled`). */
    private val modelInstalled: () -> Boolean = { false },
) {
    internal val isModelInstalled: Boolean get() = modelInstalled()

    /** Freeing waits for a sentence in progress, so it never runs on the caller's (main) thread. */
    private val releaser = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "kokoro-release") }
    private val lock = Any()
    private var tts: OfflineTts? = null
    private var users = 0

    /** Loads the model if needed (seconds; call off the main thread). False if it cannot load. */
    internal fun acquire(files: KokoroModelFiles): Boolean =
        synchronized(lock) {
            if (tts == null) {
                tts = load(files) ?: return false
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
                }
            }
        }
    }

    /** Speaks [text] as [speakerId] at 1x; null if the model is not loaded or the engine failed. */
    internal fun generate(
        text: String,
        speakerId: Int,
    ): KokoroAudio? =
        synchronized(lock) {
            val engine = tts ?: return null
            runCatching { engine.generate(text, speakerId, 1.0f) }
                .onFailure { Timber.w(it, "Kokoro generation failed") }
                .getOrNull()
                ?.let { KokoroAudio(it.samples, it.sampleRate) }
        }

    /** Whether narration or the settings sample currently holds the model in memory. */
    internal val isLoaded: Boolean get() = synchronized(lock) { tts != null }

    private fun load(files: KokoroModelFiles): OfflineTts? = loadKokoroTts(files, NUM_THREADS)

    internal companion object {
        /** Leaves cores for playback and the UI while still generating well ahead of real time. */
        const val NUM_THREADS = 4
    }
}

/**
 * Loads a Kokoro engine that computes on [numThreads] threads (seconds; off the main thread); null
 * if it cannot load. An UnsatisfiedLinkError, on a device without the native code, must surface as
 * "cannot load", not a crash.
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
