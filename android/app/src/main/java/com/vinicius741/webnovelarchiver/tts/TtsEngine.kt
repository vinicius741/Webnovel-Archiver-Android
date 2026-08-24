package com.vinicius741.webnovelarchiver.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.TtsSession
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Text-to-speech playback engine. All state mutations are serialized through the owner scope and
 * mutex so callbacks cannot race durable session updates.
 */
class TtsEngine(
    private val context: Context,
    private val repository: AppRepository,
    private val awaitRepositoryReady: suspend () -> Unit = {},
    /**
     * Coroutine scope that owns all TTS state mutations (R8). UtteranceProgressListener callbacks
     * and the public control methods (play/pause/next/...) are routed through [stateMutex] on this
     * scope's dispatcher, so storage reads/writes and playback continuation never race regardless of
     * which thread the Android TTS engine invokes the listener from.
     */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : TextToSpeech.OnInitListener {
    private val preparer = TtsPlaybackPreparer(repository)

    // The store MUST share the same owner as the preparer's read path. When a repository is present
    // the preparer reads the in-memory session cache, so writes route through the repository too
    // (disk + cache in one call) — otherwise a pause updates only disk and resume reads a stale
    // cache, silently no-op'ing play-after-pause.
    private val sessionStore = TtsSessionStore(repository)
    private var tts: TextToSpeech? = null
    private var ttsInitialized = false
    private var pendingSpeakOnInit = false
    private var chunks: List<String> = emptyList()
    private var currentChunkIndex = 0
    private var session: TtsSession? = null
    private var playbackActive = false
    private val stateMutex = Mutex()
    private var currentUtteranceId: String? = null
    private var utteranceSequence = 0L
    private var stallWatchdogJob: Job? = null
    private var watchdogChunkIndex = -1
    private var watchdogRetryCount = 0
    private var activeSettings: TtsSettings? = null
    private val commandVersion = AtomicLong(0L)
    private val listeners = TtsEventListeners()
    private val playbackPublisher = TtsPlaybackPublisher()

    /** Canonical observable playback state; authoritative `null` means playback explicitly stopped. */
    val playbackState: StateFlow<TtsPlaybackUpdate> get() = playbackPublisher.playbackState

    fun addErrorListener(listener: (TtsPlaybackError) -> Unit) = listeners.addError(listener)

    fun removeErrorListener(listener: (TtsPlaybackError) -> Unit) = listeners.removeError(listener)

    fun addVoiceAvailabilityListener(listener: (List<VoiceInfo>) -> Unit) {
        listeners.addVoices(listener)
        if (ttsInitialized) listener(availableVoices())
    }

    fun removeVoiceAvailabilityListener(listener: (List<VoiceInfo>) -> Unit) = listeners.removeVoices(listener)

    private fun notifyErrorListeners(error: TtsPlaybackError) {
        Timber.w(TtsErrorPlanning.logMessage(error))
        listeners.dispatchError(error)
    }

    private fun notifyVoiceAvailabilityListeners() {
        val voices = availableVoices()
        listeners.dispatchVoices(voices)
    }

    override fun onInit(status: Int) {
        TtsEngineLogging.engineInit(status, pendingSpeakOnInit, playbackActive)
        scope.launch {
            stateMutex.withLock {
                if (status != TextToSpeech.SUCCESS) {
                    ttsInitialized = false
                    pendingSpeakOnInit = false
                    handlePlaybackErrorLocked(TtsPlaybackError(TtsPlaybackErrorKind.InitFailed))
                    notifyVoiceAvailabilityListeners()
                    return@withLock
                }
                ttsInitialized = true
                tts?.setOnUtteranceProgressListener(
                    TtsUtteranceProgressListener(
                        onUtteranceError = ::routeUtteranceError,
                        onUtteranceDone = ::routeUtteranceDone,
                    ),
                )
                val settingsApplied = activeSettings?.let(::applySettingsLocked) ?: true
                notifyVoiceAvailabilityListeners()
                if (settingsApplied && pendingSpeakOnInit && playbackActive) {
                    pendingSpeakOnInit = false
                    speakCurrentLocked()
                }
            }
        }
    }

    fun play(
        story: Story,
        chapter: Chapter,
    ) {
        play(story.id, chapter.id)
    }

    fun play(
        storyId: String,
        chapterId: String,
        chunkIndex: Int = 0,
    ) {
        val request = commandVersion.incrementAndGet()
        scope.launch {
            val prepared =
                runCatching {
                    awaitRepositoryReady()
                    preparer.prepare(storyId, chapterId, chunkIndex)
                }.onFailure { Timber.e(it, "TTS playback preparation failed") }
                    .getOrNull() ?: return@launch
            stateMutex.withLock {
                if (request != commandVersion.get()) return@withLock
                startPreparedPlaybackLocked(prepared)
            }
        }
    }

    /**
     * Begin narration at a specific chunk index — used by the reader's tap-to-start-from-paragraph
     * (parity gap 3). [chunkIndex] maps 1:1 to a `data-tts-group` in the annotated reader HTML, and
     * the chunk list produced here is byte-for-byte aligned with the Reader annotation preparation
     * path (both reuse the same grouping logic). Out-of-range indices are clamped.
     */
    fun playFromChunk(
        story: Story,
        chapter: Chapter,
        chunkIndex: Int,
    ) {
        play(story.id, chapter.id, chunkIndex)
    }

    fun resumePersistedSession() {
        val request = commandVersion.incrementAndGet()
        scope.launch {
            val prepared =
                runCatching {
                    awaitRepositoryReady()
                    preparer.resume()
                }.onFailure { Timber.e(it, "TTS session restore failed") }
                    .getOrNull() ?: run {
                    // Logging (not erroring) here: an empty/stale persisted session is a valid state
                    // (fresh install, or the user stopped playback). But it is also the symptom of a
                    // read/write divergence in session persistence, so make the no-op visible rather
                    // than failing silently as it did before.
                    Timber.w("TTS resume skipped: no resumable persisted session")
                    return@launch
                }
            stateMutex.withLock {
                if (request != commandVersion.get()) return@withLock
                startPreparedPlaybackLocked(prepared)
            }
        }
    }

    private fun startPreparedPlaybackLocked(prepared: PreparedTtsPlayback) {
        TtsEngineLogging.sessionStart(prepared.story.id, prepared.chapter.id, prepared.chunks.size, prepared.startIndex, ttsInitialized)
        activeSettings = prepared.settings
        ensureEngineLocked()
        if (!applySettingsLocked(prepared.settings) && ttsInitialized) return
        chunks = prepared.chunks
        startSession(prepared.story, prepared.chapter, prepared.settings, prepared.startIndex)
        playbackActive = true
        speakCurrentLocked()
    }

    fun next() {
        commandVersion.incrementAndGet()
        scope.launch { stateMutex.withLock { nextLocked() } }
    }

    private fun nextLocked() {
        if (chunks.isEmpty()) return
        playbackActive = false
        tts?.stop()
        playbackActive = true
        currentChunkIndex = TtsSessionPlanning.nextChunkIndex(currentChunkIndex, chunks.size)
        speakCurrentLocked()
    }

    fun previous() {
        commandVersion.incrementAndGet()
        scope.launch { stateMutex.withLock { previousLocked() } }
    }

    private fun previousLocked() {
        if (chunks.isEmpty()) return
        playbackActive = false
        tts?.stop()
        playbackActive = true
        currentChunkIndex = TtsSessionPlanning.previousChunkIndex(currentChunkIndex, chunks.size)
        speakCurrentLocked()
    }

    fun pause() {
        commandVersion.incrementAndGet()
        scope.launch {
            val paused = stateMutex.withLock { pauseLocked() }
            paused?.let { session ->
                runCatching { sessionStore.flush(session) }
                    .onFailure { Timber.e(it, "TTS pause flush failed") }
            }
        }
    }

    private fun pauseLocked(): TtsSession? {
        playbackActive = false
        pendingSpeakOnInit = false
        cancelStallWatchdog()
        tts?.stop()
        val paused =
            session?.copy(
                isPaused = true,
                wasPlaying = false,
                currentChunkIndex = currentChunkIndex.coerceAtLeast(0),
                updatedAt = System.currentTimeMillis(),
            )
        session = paused
        emitState(isPlaying = false)
        return paused
    }

    fun stop() {
        commandVersion.incrementAndGet()
        scope.launch {
            stateMutex.withLock { stopLocked() }
            runCatching { sessionStore.clear() }.onFailure { Timber.e(it, "TTS stop clear failed") }
        }
    }

    private fun stopLocked() {
        playbackActive = false
        pendingSpeakOnInit = false
        currentUtteranceId = null
        cancelStallWatchdog()
        tts?.stop()
        session = null
        chunks = emptyList()
        // Playback has ended — signal observers to clear MediaSession state + hide the transport.
        playbackPublisher.stop()
    }

    /** In-memory snapshot for notification refreshes; never decodes the session JSON on main. */
    fun currentSnapshot(isPlaying: Boolean): TtsPlaybackSnapshot? =
        TtsPlaybackState.snapshotForSession(session, chunks.size, isPlaying && session?.isPaused != true)

    private fun ensureEngineLocked(): TextToSpeech? {
        if (tts == null) {
            ttsInitialized = false
            tts = TextToSpeech(context, this)
        }
        return tts
    }

    private fun startSession(
        story: Story,
        chapter: Chapter,
        settings: TtsSettings,
        startIndex: Int,
    ) {
        currentChunkIndex = startIndex.coerceIn(0, chunks.lastIndex)
        watchdogChunkIndex = -1
        watchdogRetryCount = 0
        session =
            TtsSession(
                storyId = story.id,
                chapterId = chapter.id,
                chapterTitle = chapter.title,
                currentChunkIndex = currentChunkIndex,
                isPaused = false,
                wasPlaying = true,
                voiceIdentifier = settings.voiceIdentifier,
                rate = settings.rate,
                pitch = settings.pitch,
            )
    }

    fun availableVoices(): List<VoiceInfo> {
        // Lazy engine construction stays here (side-effect); the sort/filter/map lives in
        // TtsVoicePlanning so it can be unit-tested without a real TextToSpeech instance.
        val engine = tts ?: TextToSpeech(context, this).also { tts = it } ?: return emptyList()
        return TtsVoicePlanning.toVoiceInfo(engine.voices)
    }

    private fun applySettingsLocked(settings: TtsSettings): Boolean {
        val engine = ensureEngineLocked() ?: return false
        if (!ttsInitialized) return false
        // The voice/language decision is pure (TtsVoicePlanning.resolveVoice); the engine owns the
        // side-effects — setVoice/setLanguage return values and the handlePlaybackErrorLocked
        // routing — so the LANG_MISSING_DATA / LANG_NOT_SUPPORTED / ERROR branching stays exactly
        // where it was before the extraction.
        when (val result = TtsVoicePlanning.resolveVoice(engine.voices, settings)) {
            is VoiceSelectionResult.VoiceResolved -> {
                if (engine.setVoice(result.voice) == TextToSpeech.ERROR) {
                    handlePlaybackErrorLocked(
                        TtsPlaybackError(
                            kind = TtsPlaybackErrorKind.VoiceRejected,
                            detail = result.voice.name,
                        ),
                    )
                    return false
                }
            }
            is VoiceSelectionResult.VoiceMissing -> {
                handlePlaybackErrorLocked(
                    TtsPlaybackError(
                        kind = TtsPlaybackErrorKind.VoiceUnavailable,
                        detail = result.identifier,
                    ),
                )
                return false
            }
            VoiceSelectionResult.UseDefaultLanguage -> {
                when (engine.setLanguage(Locale.getDefault())) {
                    TextToSpeech.LANG_MISSING_DATA -> {
                        handlePlaybackErrorLocked(TtsPlaybackError(TtsPlaybackErrorKind.LanguageMissingData))
                        return false
                    }
                    TextToSpeech.LANG_NOT_SUPPORTED -> {
                        handlePlaybackErrorLocked(TtsPlaybackError(TtsPlaybackErrorKind.LanguageNotSupported))
                        return false
                    }
                }
            }
        }
        if (engine.setPitch(settings.pitch) == TextToSpeech.ERROR) {
            Timber.w("TTS setPitch rejected value %s", settings.pitch)
        }
        if (engine.setSpeechRate(settings.rate) == TextToSpeech.ERROR) {
            Timber.w("TTS setSpeechRate rejected value %s", settings.rate)
        }
        return true
    }

    private fun speakCurrentLocked() {
        val engine = ensureEngineLocked()
        if (engine == null) {
            handlePlaybackErrorLocked(TtsPlaybackError(TtsPlaybackErrorKind.InitFailed))
            return
        }
        if (!ttsInitialized) {
            pendingSpeakOnInit = true
            emitState(isPlaying = false)
            return
        }
        val current =
            chunks.getOrNull(currentChunkIndex) ?: run {
                finishPlaybackLocked()
                return
            }
        session?.let {
            val updated =
                it.copy(
                    currentChunkIndex = currentChunkIndex,
                    isPaused = false,
                    wasPlaying = true,
                    updatedAt = System.currentTimeMillis(),
                )
            session = updated
            sessionStore.schedule(updated)
        }
        val utteranceId = "chapter_chunk_${currentChunkIndex}_${utteranceSequence++}"
        if (currentChunkIndex != watchdogChunkIndex) {
            watchdogChunkIndex = currentChunkIndex
            watchdogRetryCount = 0
        }
        val speakResult = engine.speak(current, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        TtsEngineLogging.speak(currentChunkIndex, chunks.size, utteranceId, current, speakResult)
        if (speakResult == TextToSpeech.ERROR) {
            handlePlaybackErrorLocked(TtsPlaybackError(TtsPlaybackErrorKind.SpeakFailed))
            return
        }
        currentUtteranceId = utteranceId
        scheduleStallWatchdog(utteranceId, current)
        // Snapshot reflects the chunk now being spoken.
        emitState(isPlaying = true)
    }

    /** Publishes the live session; `isPlaying` distinguishes speaking from paused mid-chapter. */
    private fun emitState(isPlaying: Boolean) = playbackPublisher.publish(session, chunks.size, isPlaying)

    private fun routeUtteranceDone(utteranceId: String?) {
        scope.launch {
            stateMutex.withLock {
                TtsEngineLogging.utteranceDone(utteranceId, currentUtteranceId)
                if (utteranceId != currentUtteranceId) return@withLock
                currentUtteranceId = null
                cancelStallWatchdog()
                handleChunkDone()
            }
        }
    }

    private fun routeUtteranceError(
        utteranceId: String?,
        errorCode: Int,
    ) {
        scope.launch {
            stateMutex.withLock {
                if (utteranceId != currentUtteranceId) return@withLock
                currentUtteranceId = null
                cancelStallWatchdog()
                handlePlaybackErrorLocked(
                    TtsPlaybackError(
                        kind = TtsPlaybackErrorKind.SynthesisFailed,
                        code = errorCode,
                    ),
                )
            }
        }
    }

    private fun scheduleStallWatchdog(
        utteranceId: String,
        text: String,
    ) {
        cancelStallWatchdog()
        val rate = session?.rate ?: 1f
        val timeoutMs = TtsWatchdogPlanning.timeoutMs(text.length, rate)
        stallWatchdogJob =
            scope.launch {
                delay(timeoutMs)
                stateMutex.withLock {
                    if (!playbackActive || currentUtteranceId != utteranceId) return@withLock
                    val retryIndex = session?.currentChunkIndex ?: currentChunkIndex.coerceAtLeast(0)
                    if (watchdogRetryCount == 0) {
                        watchdogRetryCount += 1
                        Timber.w("TTS stalled for utterance %s; retrying chunk %s", utteranceId, retryIndex)
                        tts?.stop()
                        currentUtteranceId = null
                        stallWatchdogJob = null
                        currentChunkIndex = retryIndex.coerceIn(0, chunks.lastIndex)
                        speakCurrentLocked()
                    } else {
                        handlePlaybackErrorLocked(TtsPlaybackError(TtsPlaybackErrorKind.Stalled))
                    }
                }
            }
    }

    private fun cancelStallWatchdog() {
        stallWatchdogJob?.cancel()
        stallWatchdogJob = null
    }

    private fun handlePlaybackErrorLocked(error: TtsPlaybackError) {
        playbackActive = false
        pendingSpeakOnInit = false
        currentUtteranceId = null
        cancelStallWatchdog()
        session?.let {
            val updated =
                it.copy(
                    currentChunkIndex = currentChunkIndex.coerceAtLeast(0),
                    isPaused = true,
                    wasPlaying = false,
                    updatedAt = System.currentTimeMillis(),
                )
            session = updated
            sessionStore.schedule(updated)
            emitState(isPlaying = false)
        } ?: playbackPublisher.stop()
        notifyErrorListeners(error)
    }

    private fun handleChunkDone() {
        if (!playbackActive) return
        if (currentChunkIndex < chunks.lastIndex) {
            currentChunkIndex += 1
            speakCurrentLocked()
            return
        }
        handleChapterFinished()
    }

    private fun handleChapterFinished() {
        val currentSession =
            session ?: run {
                finishPlaybackLocked()
                return
            }
        playbackActive = false
        val request = commandVersion.incrementAndGet()
        scope.launch {
            val prepared =
                runCatching { preparer.nextChapter(currentSession) }
                    .onFailure { Timber.e(it, "TTS next-chapter preparation failed") }
                    .getOrNull()
            stateMutex.withLock {
                if (request != commandVersion.get()) return@withLock
                if (prepared == null) {
                    finishPlaybackLocked()
                } else {
                    startPreparedPlaybackLocked(prepared)
                }
            }
        }
    }

    private fun finishPlaybackLocked() {
        playbackActive = false
        pendingSpeakOnInit = false
        currentUtteranceId = null
        cancelStallWatchdog()
        session = null
        chunks = emptyList()
        scope.launch {
            runCatching { sessionStore.clear() }
                .onFailure { Timber.e(it, "TTS completion clear failed") }
        }
        playbackPublisher.stop()
    }

    fun shutdown() {
        // R8: stop playback, release the TTS engine, and cancel the engine scope so no lingering
        // callback continuation can mutate storage after the service is torn down.
        scope.cancel()
        cancelStallWatchdog()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsInitialized = false
        playbackActive = false
    }
}
