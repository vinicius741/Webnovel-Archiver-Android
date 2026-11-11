package com.vinicius741.webnovelarchiver.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Owns the Android [TextToSpeech] instance: creating it lazily for the chosen engine, waiting for it,
 * applying a voice.
 */
internal class TtsSpeechEngineHost(
    private val context: Context,
    private val scope: CoroutineScope,
    private val stateMutex: Mutex,
    private val listeners: TtsEventListeners,
    private val onReady: () -> Unit,
    private val onFailed: () -> Unit,
) {
    var engine: TextToSpeech? = null
        private set
    var initialized = false
        private set

    /** The engine package asked for; null follows the phone's preferred engine. */
    private var requestedPackage: String? = null

    /** The package [engine] was created for; null when it is the phone's preferred engine. */
    var enginePackage: String? = null
        private set

    /** Tells a replaced engine's late init callback apart from the current one's. */
    private var generation = 0L

    /** Bounded initialization wait; cancelled on successful init and every terminal path. */
    private var initWatchdog: Job? = null

    /**
     * Speaks with [packageName] from the next [ensureEngine] on. A different engine than the current
     * one shuts the current one down; returns whether that happened.
     */
    fun selectEngine(packageName: String?): Boolean {
        if (packageName == requestedPackage) return false
        requestedPackage = packageName
        val replaced = engine != null
        if (replaced) shutdown()
        return replaced
    }

    fun ensureEngine(): TextToSpeech {
        engine?.let { return it }
        initialized = false
        val packageName =
            requestedPackage?.let { requested ->
                TtsEnginePlanning.resolve(requested, TtsEngineCatalog.installedPackages(context)).also {
                    if (it == null) Timber.w("TTS engine %s is not installed; using the phone's engine", requested)
                }
            }
        enginePackage = packageName
        val created = ++generation
        return TextToSpeech(context, { status -> onInit(created, status) }, packageName).also { engine = it }
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
                    listeners.dispatchVoices(availableVoices())
                    // Discard the dead engine: a kept instance never re-fires onInit, so the next
                    // play would wait out the init watchdog against it instead of retrying.
                    engine?.shutdown()
                    engine = null
                    return@withLock
                }
                initialized = true
                cancelInitWatchdog()
                listeners.dispatchVoices(availableVoices())
                onReady()
            }
        }
    }

    /** R16: a callback that never arrives surfaces as a recoverable error, not silent pending playback. */
    fun watchInit() {
        initWatchdog?.cancel()
        initWatchdog =
            scope.launch {
                delay(INIT_WATCHDOG_TIMEOUT_MS)
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
    fun availableVoices(): List<VoiceInfo> = TtsVoicePlanning.toVoiceInfo(ensureEngine().voices)

    fun applySettings(
        settings: TtsSettings,
        synthesisRate: Float,
        onError: (TtsPlaybackError) -> Unit,
    ): Boolean {
        val current = ensureEngine()
        if (!initialized) return false
        return TtsSettingsApplier.apply(current, settings, synthesisRate, onError)
    }

    fun shutdown() {
        cancelInitWatchdog()
        generation++
        engine?.stop()
        engine?.shutdown()
        engine = null
        initialized = false
    }

    private companion object {
        /** Upper bound for the engine's init callback before a pending play surfaces as an error. */
        const val INIT_WATCHDOG_TIMEOUT_MS = 15_000L
    }
}
