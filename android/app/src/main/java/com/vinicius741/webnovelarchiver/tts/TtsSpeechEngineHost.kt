package com.vinicius741.webnovelarchiver.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroModelFiles
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroRuntime
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroVoices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Owns the speech engine: an Android [TextToSpeech] instance for the chosen TTS app, or the
 * built-in Kokoro model. It starts the engine lazily, waits for it, and applies a voice. Call
 * everything on [scope]'s (main) thread; [isBuiltin] and [kokoroSpeaker] are also read by the
 * render thread.
 */
internal class TtsSpeechEngineHost(
    private val context: Context,
    private val scope: CoroutineScope,
    private val stateMutex: Mutex,
    private val listeners: TtsEventListeners,
    val kokoro: KokoroRuntime,
    private val onReady: () -> Unit,
    private val onFailed: () -> Unit,
) {
    /** The Android engine; null while the built-in model speaks or nothing has started. */
    var engine: TextToSpeech? = null
        private set
    var initialized = false
        private set

    /** The engine package asked for; null follows the phone's preferred engine. */
    private var requestedPackage: String? = null

    /** The engine in use: a TTS app package, [KokoroVoices.ENGINE_ID], or null for the phone's engine. */
    var enginePackage: String? = null
        private set

    @Volatile
    var isBuiltin = false
        private set

    /** The Kokoro speaker the last [applySettings] chose. */
    @Volatile
    var kokoroSpeaker = KokoroVoices.default.speakerId
        private set

    /** An engine has been started (and not shut down) for the current [generation]. */
    private var started = false

    /** This host holds one of [kokoro]'s users and must release it. */
    private var holdsKokoro = false

    /** Tells a replaced engine's late init callback apart from the current one's. */
    private var generation = 0L

    /** Bounded initialization wait; cancelled on successful init and every terminal path. */
    private var initWatchdog: Job? = null

    /**
     * Speaks with [packageName] from the next [ensureEngine] on. A different engine than the current
     * one shuts the current one down, and so does the same choice once it can speak instead of the
     * stand-in used while it could not (Kokoro finished downloading). Returns whether that happened.
     */
    fun selectEngine(packageName: String?): Boolean {
        if (!needsRestart(packageName)) {
            requestedPackage = packageName
            return false
        }
        requestedPackage = packageName
        val replaced = started
        if (replaced) shutdown()
        return replaced
    }

    /** Whether speaking with [packageName] means replacing the running engine. */
    fun needsRestart(packageName: String?): Boolean = packageName != requestedPackage || (started && resolve(packageName) != enginePackage)

    private fun resolve(requested: String?): String? =
        requested?.let { TtsEnginePlanning.resolve(it, TtsEngineCatalog.usablePackages(context, kokoro.isModelInstalled)) }

    /** Starts the chosen engine unless one is running; [onReady] follows once it can speak. */
    fun ensureEngine() {
        if (started) return
        started = true
        initialized = false
        val packageName =
            resolve(requestedPackage).also {
                if (it == null &&
                    requestedPackage != null
                ) {
                    Timber.w("TTS engine %s is not available; using the phone's engine", requestedPackage)
                }
            }
        enginePackage = packageName
        isBuiltin = KokoroVoices.isEngine(packageName)
        val created = ++generation
        if (isBuiltin) {
            startKokoro(created)
        } else {
            engine = TextToSpeech(context, { status -> onInit(created, status) }, packageName)
        }
    }

    /** Loading the model takes seconds, so it happens off the main thread. */
    private fun startKokoro(created: Long) {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { kokoro.acquire(KokoroModelFiles(context)) }
            if (loaded) {
                // An engine replaced while it loaded must not keep the model in memory.
                if (created != generation) {
                    kokoro.releaseLater()
                    return@launch
                }
                holdsKokoro = true
            }
            onInit(created, if (loaded) TextToSpeech.SUCCESS else TextToSpeech.ERROR)
        }
    }

    private fun onInit(
        created: Long,
        status: Int,
    ) {
        scope.launch {
            stateMutex.withLock {
                if (created != generation) return@withLock
                if (status != TextToSpeech.SUCCESS) {
                    initialized = false
                    onFailed()
                    listeners.dispatchVoices(voicesNow())
                    // Discard the dead engine: a kept instance never re-fires onInit, so the next
                    // play would wait out the init watchdog against it instead of retrying.
                    engine?.shutdown()
                    engine = null
                    started = false
                    return@withLock
                }
                initialized = true
                cancelInitWatchdog()
                listeners.dispatchVoices(voicesNow())
                onReady()
            }
        }
    }

    /** R16: a callback that never arrives surfaces as a recoverable error, not silent pending playback. */
    fun watchInit() {
        initWatchdog?.cancel()
        val timeout = if (isBuiltin) MODEL_LOAD_TIMEOUT_MS else INIT_WATCHDOG_TIMEOUT_MS
        initWatchdog =
            scope.launch {
                delay(timeout)
                stateMutex.withLock {
                    if (!initialized) {
                        Timber.w("TTS engine initialization never completed; failing pending playback")
                        onFailed()
                    }
                }
            }
    }

    fun cancelInitWatchdog() {
        initWatchdog?.cancel()
        initWatchdog = null
    }

    /** Lazy engine construction stays here; the sort/filter/map lives in [TtsVoicePlanning]. */
    fun availableVoices(): List<VoiceInfo> {
        ensureEngine()
        return voicesNow()
    }

    private fun voicesNow(): List<VoiceInfo> = if (isBuiltin) KokoroVoices.voiceInfos() else TtsVoicePlanning.toVoiceInfo(engine?.voices)

    fun applySettings(
        settings: TtsSettings,
        synthesisRate: Float,
        onError: (TtsPlaybackError) -> Unit,
    ): Boolean {
        ensureEngine()
        if (!initialized) return false
        if (isBuiltin) {
            val id = settings.voiceIdentifier
            val voice = if (id == null) KokoroVoices.default else KokoroVoices.forIdentifier(id)
            if (voice == null) {
                onError(TtsPlaybackError(kind = TtsPlaybackErrorKind.VoiceUnavailable, detail = id))
                return false
            }
            kokoroSpeaker = voice.speakerId
            return true
        }
        val current = engine ?: return false
        // A Kokoro voice means nothing to an Android engine (the model was removed): use its default.
        val forEngine =
            if (KokoroVoices.isKokoroIdentifier(settings.voiceIdentifier)) settings.copy(voiceIdentifier = null) else settings
        return TtsSettingsApplier.apply(current, forEngine, synthesisRate, onError)
    }

    /** What rendered audio depends on besides its text; call after a successful [applySettings]. */
    fun voiceKey(settings: TtsSettings): String =
        if (isBuiltin) {
            // Kokoro has no pitch control, so pitch does not split its cache.
            "${KokoroVoices.ENGINE_ID}|speaker=$kokoroSpeaker"
        } else {
            engine?.let { ttsVoiceKey(it, enginePackage, settings) }.orEmpty()
        }

    fun shutdown() {
        cancelInitWatchdog()
        generation++
        engine?.stop()
        engine?.shutdown()
        engine = null
        if (holdsKokoro) {
            holdsKokoro = false
            kokoro.releaseLater()
        }
        initialized = false
        started = false
    }

    private companion object {
        /** Upper bound for the engine's init callback before a pending play surfaces as an error. */
        const val INIT_WATCHDOG_TIMEOUT_MS = 15_000L

        /** Loading the Kokoro model reads several hundred MB; allow a slow phone time to do it. */
        const val MODEL_LOAD_TIMEOUT_MS = 90_000L
    }
}
