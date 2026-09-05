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

    /** Bounded initialization wait (R16); cancelled on successful init and every terminal path. */
    private var initWatchdog: kotlinx.coroutines.Job? = null
    private var chunks: List<String> = emptyList()
    private var currentChunkIndex = 0
    private var session: TtsSession? = null
    private var playbackActive = false
    private val stateMutex = Mutex()
    private var currentUtteranceId: String? = null
    private var utteranceSequence = 0L
    private val watchdog =
        TtsWatchdog(
            scope = scope,
            onRetry = { utteranceId, retryIndex ->
                scope.launch {
                    stateMutex.withLock {
                        // Identity guard: a fire left over from a superseded utterance must not rewind playback.
                        if (!playbackActive || utteranceId != currentUtteranceId) return@withLock
                        tts?.stop()
                        currentUtteranceId = null
                        currentChunkIndex = retryIndex.coerceIn(0, chunks.lastIndex)
                        speakCurrentLocked()
                    }
                }
            },
            onStalled = { utteranceId ->
                scope.launch {
                    stateMutex.withLock {
                        if (!playbackActive || utteranceId != currentUtteranceId) return@withLock
                        handlePlaybackErrorLocked(TtsPlaybackError(TtsPlaybackErrorKind.Stalled))
                    }
                }
            },
        )
    private var activeSettings: TtsSettings? = null
    private val commandVersion = AtomicLong(0L)
    private val listeners = TtsEventListeners()
    private val playbackPublisher = TtsPlaybackPublisher()
    private val sleepTimer = TtsSleepTimer(scope, ::pause)

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

    private fun notifyVoiceAvailabilityListeners() = listeners.dispatchVoices(availableVoices())

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
                cancelInitWatchdogLocked()
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
    ) = play(story.id, chapter.id)

    /**
     * Begin narration. [chunkIndex] null resumes wherever this story last stopped (podcast behavior,
     * possibly a different chapter); a value pins the start. Out-of-range indices are clamped.
     */
    fun play(
        storyId: String,
        chapterId: String,
        chunkIndex: Int? = null,
    ) {
        val request = commandVersion.incrementAndGet()
        scope.launch {
            val prepared =
                runCatching {
                    awaitRepositoryReady()
                    preparer.prepare(storyId, chapterId, chunkIndex)
                }.onFailure { Timber.e(it, "TTS playback preparation failed") }
                    .getOrNull() ?: run {
                    publishIdleIfNoNewerCommand(request)
                    return@launch
                }
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
    ) = play(story.id, chapter.id, chunkIndex)

    /** Skip to the adjacent chapter ([delta] is -1 or +1); no-op at the story's edge. */
    fun skipChapter(delta: Int) {
        val request = commandVersion.incrementAndGet()
        scope.launch {
            val currentSession =
                stateMutex.withLock { session?.copy() } ?: run {
                    publishIdleIfNoNewerCommand(request)
                    return@launch
                }
            val startPaused = currentSession.isPaused
            val prepared =
                runCatching { preparer.chapterAt(currentSession, delta) }
                    .onFailure { Timber.e(it, "TTS chapter skip preparation failed") }
                    .getOrNull() ?: return@launch
            stateMutex.withLock {
                if (request != commandVersion.get()) return@withLock
                startPreparedPlaybackLocked(prepared, startPaused = startPaused)
            }
        }
    }

    /** Live playback-rate change: persists the setting and re-speaks the current chunk at the new rate. */
    fun setRate(rate: Float) {
        val request = commandVersion.incrementAndGet()
        scope.launch {
            awaitRepositoryReady()
            val settings = repository.getTtsSettings().copy(rate = rate.coerceIn(0.5f, 3.0f))
            runCatching { repository.saveTtsSettings(settings) }
                .onFailure { Timber.e(it, "TTS rate persist failed") }
            stateMutex.withLock {
                activeSettings = settings
                val current =
                    session ?: run {
                        publishIdleIfNoNewerCommand(request)
                        return@withLock
                    }
                val updated = current.copy(rate = settings.rate, updatedAt = System.currentTimeMillis())
                session = updated
                if (current.isPaused || !applySettingsLocked(settings)) {
                    sessionStore.schedule(updated)
                    emitState(isPlaying = false)
                    return@withLock
                }
                // Interrupt + re-speak so the new rate is audible immediately.
                playbackActive = false
                tts?.stop()
                playbackActive = true
                speakCurrentLocked()
            }
        }
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
                    publishIdleIfNoNewerCommand(request)
                    return@launch
                }
            stateMutex.withLock {
                if (request != commandVersion.get()) return@withLock
                startPreparedPlaybackLocked(prepared)
            }
        }
    }

    /** A no-op command must still confirm the idle state: that emission is a freshly started service's stop signal. */
    private fun publishIdleIfNoNewerCommand(request: Long) {
        if (request == commandVersion.get()) playbackPublisher.stop()
    }

    private fun startPreparedPlaybackLocked(
        prepared: PreparedTtsPlayback,
        startPaused: Boolean = false,
    ) {
        TtsEngineLogging.sessionStart(prepared.story.id, prepared.chapter.id, prepared.chunks.size, prepared.startIndex, ttsInitialized)
        activeSettings = prepared.settings
        ensureEngineLocked()
        if (!applySettingsLocked(prepared.settings) && ttsInitialized) return
        chunks = prepared.chunks
        startSession(prepared.story, prepared.chapter, prepared.settings, prepared.startIndex)
        if (startPaused) {
            // Chapter skip while paused: move the position, stay silent.
            playbackActive = false
            pendingSpeakOnInit = false
            val paused =
                session?.copy(isPaused = true, wasPlaying = false, updatedAt = System.currentTimeMillis())
            session = paused
            paused?.let(sessionStore::schedule)
            emitState(isPlaying = false)
            return
        }
        playbackActive = true
        speakCurrentLocked()
    }

    fun next() {
        val request = commandVersion.incrementAndGet()
        scope.launch {
            stateMutex.withLock {
                if (chunks.isEmpty()) publishIdleIfNoNewerCommand(request) else nextLocked()
            }
        }
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
        val request = commandVersion.incrementAndGet()
        scope.launch {
            stateMutex.withLock {
                if (chunks.isEmpty()) publishIdleIfNoNewerCommand(request) else previousLocked()
            }
        }
    }

    private fun previousLocked() {
        if (chunks.isEmpty()) return
        playbackActive = false
        tts?.stop()
        playbackActive = true
        currentChunkIndex = TtsSessionPlanning.previousChunkIndex(currentChunkIndex, chunks.size)
        speakCurrentLocked()
    }

    /** Interactive seek to a chunk within the loaded chapter. */
    fun seekChunk(targetIndex: Int) {
        val request = commandVersion.incrementAndGet()
        scope.launch {
            stateMutex.withLock {
                if (chunks.isEmpty()) {
                    publishIdleIfNoNewerCommand(request)
                    return@withLock
                }
                val clamped = targetIndex.coerceIn(0, chunks.lastIndex)
                if (clamped == currentChunkIndex) return@withLock
                val wasActive = playbackActive && session?.isPaused != true
                playbackActive = false
                tts?.stop()
                currentChunkIndex = clamped
                val updated =
                    session?.copy(
                        currentChunkIndex = clamped,
                        updatedAt = System.currentTimeMillis(),
                    )
                session = updated
                updated?.let { sessionStore.schedule(it) }
                if (wasActive) {
                    playbackActive = true
                    speakCurrentLocked()
                } else {
                    emitState(isPlaying = false)
                }
            }
        }
    }

    fun setSleepTimerDuration(minutes: Int) {
        sleepTimer.setDuration(minutes)
        emitCurrentStateAsync()
    }

    fun setSleepTimerEndOfChapter() {
        sleepTimer.setEndOfChapter()
        emitCurrentStateAsync()
    }

    fun cancelSleepTimer() {
        sleepTimer.setOff()
        emitCurrentStateAsync()
    }

    private fun emitCurrentStateAsync() {
        scope.launch { stateMutex.withLock { emitState(isPlaying = playbackActive && session?.isPaused != true) } }
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
        watchdog.cancel()
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

    /** [forgetPosition] discards the story's saved position too (variant switch: chunk indices remap). */
    fun stop(
        forgetPosition: Boolean = false,
        fallbackStoryId: String? = null,
    ) {
        commandVersion.incrementAndGet()
        scope.launch {
            val lastSession = stateMutex.withLock { stopLocked() }
            val storyId = if (forgetPosition) lastSession?.storyId ?: fallbackStoryId else null
            runCatching { sessionStore.stop(lastSession, persistPosition = !forgetPosition, forgetStoryId = storyId) }
                .onFailure { Timber.e(it, "TTS stop clear failed") }
        }
    }

    private fun stopLocked(): TtsSession? {
        playbackActive = false
        pendingSpeakOnInit = false
        currentUtteranceId = null
        watchdog.cancel()
        tts?.stop()
        val lastSession = session
        session = null
        chunks = emptyList()
        // Playback has ended — signal observers to clear MediaSession state + hide the transport.
        playbackPublisher.stop()
        return lastSession
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
        watchdog.reset()
        session =
            TtsSession(
                storyId = story.id,
                storyTitle = story.title,
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
        val engine = tts ?: TextToSpeech(context, this).also { tts = it }
        return TtsVoicePlanning.toVoiceInfo(engine.voices)
    }

    private fun applySettingsLocked(settings: TtsSettings): Boolean {
        val engine = ensureEngineLocked() ?: return false
        if (!ttsInitialized) return false
        return TtsSettingsApplier.apply(engine, settings, ::handlePlaybackErrorLocked)
    }

    private fun speakCurrentLocked() {
        val engine = ensureEngineLocked()
        if (engine == null) {
            handlePlaybackErrorLocked(TtsPlaybackError(TtsPlaybackErrorKind.InitFailed))
            return
        }
        if (!ttsInitialized) {
            pendingSpeakOnInit = true
            // R16: bounded initialization watchdog — a callback that never arrives surfaces as a
            // recoverable error instead of a silently pending playback.
            scheduleInitWatchdogLocked()
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
        if (currentChunkIndex != watchdog.chunkIndex) {
            watchdog.chunkIndex = currentChunkIndex
            watchdog.retryCount = 0
        }
        val speakResult = engine.speak(current, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        TtsEngineLogging.speak(currentChunkIndex, chunks.size, utteranceId, current, speakResult)
        if (speakResult == TextToSpeech.ERROR) {
            handlePlaybackErrorLocked(TtsPlaybackError(TtsPlaybackErrorKind.SpeakFailed))
            return
        }
        currentUtteranceId = utteranceId
        watchdog.schedule(utteranceId, current, session?.rate ?: 1f, session?.currentChunkIndex ?: currentChunkIndex.coerceAtLeast(0))
        // Snapshot reflects the chunk now being spoken.
        emitState(isPlaying = true)
    }

    /** Publishes the live session; `isPlaying` distinguishes speaking from paused mid-chapter. */
    private fun emitState(isPlaying: Boolean) {
        val (targetEpochMs, isEndOfChapter) =
            when (val m = sleepTimer.mode) {
                is TtsSleepTimerMode.Duration -> m.targetEpochMs to false
                is TtsSleepTimerMode.EndOfChapter -> null to true
                is TtsSleepTimerMode.Off -> null to false
            }
        playbackPublisher.publish(
            session = session,
            totalChunks = chunks.size,
            isPlaying = isPlaying,
            sleepTimerTargetEpochMs = targetEpochMs,
            sleepTimerEndOfChapter = isEndOfChapter,
        )
    }

    private fun routeUtteranceDone(utteranceId: String?) {
        scope.launch {
            stateMutex.withLock {
                TtsEngineLogging.utteranceDone(utteranceId, currentUtteranceId)
                if (utteranceId != currentUtteranceId) return@withLock
                currentUtteranceId = null
                watchdog.cancel()
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
                watchdog.cancel()
                handlePlaybackErrorLocked(
                    TtsPlaybackError(
                        kind = TtsPlaybackErrorKind.SynthesisFailed,
                        code = errorCode,
                    ),
                )
            }
        }
    }

    private fun handlePlaybackErrorLocked(error: TtsPlaybackError) {
        playbackActive = false
        pendingSpeakOnInit = false
        cancelInitWatchdogLocked()
        currentUtteranceId = null
        watchdog.cancel()
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
        if (sleepTimer.onChapterCompleted()) {
            pause()
            return
        }
        val request = commandVersion.incrementAndGet()
        scope.launch {
            var preparationFailure: Throwable? = null
            val prepared =
                runCatching { preparer.nextChapter(currentSession) }
                    .onFailure {
                        preparationFailure = it
                        Timber.e(it, "TTS next-chapter preparation failed")
                    }.getOrNull()
            stateMutex.withLock {
                if (request != commandVersion.get()) return@withLock
                val failure = preparationFailure
                when {
                    // R16: an I/O/preparation failure keeps the resumable session and position —
                    // only a genuine end of story clears them below.
                    failure != null ->
                        handlePlaybackErrorLocked(
                            TtsPlaybackError(TtsPlaybackErrorKind.PreparationFailed, detail = failure.message),
                        )
                    prepared == null -> finishPlaybackLocked()
                    else -> startPreparedPlaybackLocked(prepared)
                }
            }
        }
    }

    /** Bounded wait for the engine's init callback (R16); fires a recoverable InitFailed error. */
    private fun scheduleInitWatchdogLocked() {
        initWatchdog?.cancel()
        initWatchdog =
            scope.launch {
                delay(INIT_WATCHDOG_TIMEOUT_MS)
                stateMutex.withLock {
                    if (!ttsInitialized && pendingSpeakOnInit) {
                        pendingSpeakOnInit = false
                        Timber.w("TTS engine initialization never completed; failing pending playback")
                        handlePlaybackErrorLocked(TtsPlaybackError(TtsPlaybackErrorKind.InitFailed))
                    }
                }
            }
    }

    private fun cancelInitWatchdogLocked() {
        initWatchdog?.cancel()
        initWatchdog = null
    }

    private fun finishPlaybackLocked() {
        playbackActive = false
        pendingSpeakOnInit = false
        cancelInitWatchdogLocked()
        currentUtteranceId = null
        watchdog.cancel()
        val finishedSession = session
        session = null
        chunks = emptyList()
        scope.launch {
            runCatching { sessionStore.finish(finishedSession) }
                .onFailure { Timber.e(it, "TTS completion clear failed") }
        }
        playbackPublisher.stop()
    }

    fun shutdown() {
        // Stop playback, release the TTS engine, and cancel the engine scope so no lingering
        // callback continuation can mutate storage after the service is torn down.
        scope.cancel()
        watchdog.cancel()
        cancelInitWatchdogLocked()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsInitialized = false
        playbackActive = false
    }

    private companion object {
        /** Upper bound for the engine's init callback before a pending play surfaces as an error (R16). */
        const val INIT_WATCHDOG_TIMEOUT_MS = 15_000L
    }
}
