package com.vinicius741.webnovelarchiver.tts

import android.content.Context
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.TtsHistoryEntry
import com.vinicius741.webnovelarchiver.domain.model.TtsSession
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Text-to-speech playback engine. It owns the session, the position and every command, and plays
 * through a [TtsOutput]: rendered audio when the speech engine can write files, live speech if not.
 * All state mutations are serialized through the owner scope and mutex so callbacks cannot race
 * durable session updates. A pause stays a pause: only an explicit play or a resume after a brief
 * interruption starts audio, whatever else happens to the position or the process.
 */
class TtsEngine(
    private val context: Context,
    private val repository: AppRepository,
    private val awaitRepositoryReady: suspend () -> Unit = {},
    /**
     * Coroutine scope that owns all TTS state mutations (R8). Output callbacks and the public
     * control methods (play/pause/next/...) are routed through [stateMutex] on this scope's
     * dispatcher, so storage reads/writes and playback continuation never race regardless of which
     * thread the audio or the Android TTS engine invokes a callback from.
     */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val preparer = TtsPlaybackPreparer(repository)

    // The store MUST share the same owner as the preparer's read path. When a repository is present
    // the preparer reads the in-memory session cache, so writes route through the repository too
    // (disk + cache in one call) — otherwise a pause updates only disk and resume reads a stale
    // cache, silently no-op'ing play-after-pause.
    private val sessionStore = TtsSessionStore(repository)
    private val stateMutex = Mutex()
    private val loaded = TtsLoadedSession()
    private var output = TtsOutputPhase.Silent
    private var activeSettings: TtsSettings? = null
    private val commandVersion = AtomicLong(0L)
    private val listeners = TtsEventListeners()
    private val playbackPublisher = TtsPlaybackPublisher()
    private val sleepTimer =
        TtsSleepTimer(
            scope = scope,
            onFade = { volume -> scope.launch { stateMutex.withLock { outputs.active?.setVolume(volume) } } },
            onTimerExpired = ::onSleepTimerFinished,
        )

    /** Lets the owner of audio focus drop any pending auto-resume when the sleep timer fires. */
    var onSleepTimerExpired: (() -> Unit)? = null

    private var renderAheadStarted = false
    private val speech =
        TtsSpeechEngineHost(
            context = context,
            scope = scope,
            stateMutex = stateMutex,
            listeners = listeners,
            onReady = {
                if (output == TtsOutputPhase.WaitingForEngine) loadOutputLocked()
            },
            onFailed = {
                handlePlaybackErrorLocked(TtsPlaybackError(TtsPlaybackErrorKind.InitFailed))
            },
        )
    private val outputs =
        TtsOutputs(
            context = context,
            host = speech,
            scope = scope,
            listener =
                object : TtsOutput.Listener {
                    override fun onItemStarted(item: Int) = onOutputItemStarted(item)

                    override fun onEnded() {
                        if (output == TtsOutputPhase.Running) handleChapterFinished()
                    }

                    override fun onUnavailable() = switchToLiveOutputLocked()

                    override fun onError(error: TtsPlaybackError) = handlePlaybackErrorLocked(error)

                    override fun onRenderPlanFinished() = renderNextChapterAheadLocked()
                },
            dispatch = { block -> scope.launch { stateMutex.withLock { block() } } },
        )

    /** Canonical observable playback state; authoritative `null` means playback explicitly stopped. */
    val playbackState: StateFlow<TtsPlaybackUpdate> get() = playbackPublisher.playbackState

    fun addErrorListener(listener: (TtsPlaybackError) -> Unit) = listeners.addError(listener)

    fun removeErrorListener(listener: (TtsPlaybackError) -> Unit) = listeners.removeError(listener)

    fun addVoiceAvailabilityListener(listener: (List<VoiceInfo>) -> Unit) {
        listeners.addVoices(listener)
        if (speech.initialized) listener(availableVoices())
    }

    fun removeVoiceAvailabilityListener(listener: (List<VoiceInfo>) -> Unit) = listeners.removeVoices(listener)

    fun availableVoices(): List<VoiceInfo> = speech.availableVoices()

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
                if (chunkIndex != null) recordJumpLocked()
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
                stateMutex.withLock { loaded.session?.copy() } ?: run {
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
                recordJumpLocked()
                startPreparedPlaybackLocked(prepared, startPaused = startPaused)
            }
        }
    }

    /** Returns to a place from the listening history, staying paused if playback was paused. */
    fun jumpToHistory(entry: TtsHistoryEntry) {
        val request = commandVersion.incrementAndGet()
        scope.launch {
            val current = stateMutex.withLock { loaded.session?.copy() } ?: return@launch
            val saved = TtsSavedPoint(entry.chunkIndex, entry.chunkOffsetMs, entry.anchorText, entry.chunkingVersion)
            val prepared =
                runCatching { preparer.prepareAt(current.storyId, entry.chapterId, saved) }
                    .onFailure { Timber.e(it, "TTS history jump preparation failed") }
                    .getOrNull() ?: return@launch
            stateMutex.withLock {
                if (request != commandVersion.get()) return@withLock
                recordJumpLocked()
                startPreparedPlaybackLocked(prepared.copy(spokenTitle = null), startPaused = current.isPaused)
            }
        }
    }

    /** Live speed change: audible immediately, and rendered audio stays usable at any speed. */
    fun setRate(rate: Float) {
        val request = commandVersion.incrementAndGet()
        scope.launch {
            awaitRepositoryReady()
            val settings = repository.ttsSettings.get().copy(rate = rate.coerceIn(0.5f, 3.0f))
            runCatching { repository.ttsSettings.save(settings) }
                .onFailure { Timber.e(it, "TTS rate persist failed") }
            stateMutex.withLock {
                activeSettings = settings
                val current =
                    loaded.session ?: run {
                        publishIdleIfNoNewerCommand(request)
                        return@withLock
                    }
                val updated = current.copy(rate = settings.rate)
                loaded.session = updated
                sessionStore.schedule(updated)
                outputs.active?.setRate(settings.rate)
                emitState(isPlaying = isAudibleLocked())
            }
        }
    }

    /**
     * Saved settings reach the loaded chapter in place. New pause lengths apply from the next pause
     * heard and a new rate at once; a new engine, voice or pitch re-renders from where it is.
     */
    fun applySettings(settings: TtsSettings) {
        scope.launch {
            stateMutex.withLock {
                val session = loaded.session ?: return@withLock
                val previous = activeSettings
                activeSettings = settings
                loaded.applyPacing(settings)
                if (session.rate != settings.rate) {
                    val updated = session.copy(rate = settings.rate)
                    loaded.session = updated
                    sessionStore.schedule(updated)
                    outputs.active?.setRate(settings.rate)
                }
                if (previous != null && loaded.hasChapter && TtsProfilePlanning.soundsDifferent(previous, settings)) {
                    reloadVoiceLocked(settings)
                } else {
                    emitState(isPlaying = isAudibleLocked())
                }
            }
        }
    }

    /** Restarts the output from the current point so what follows is heard in the new voice. */
    private fun reloadVoiceLocked(settings: TtsSettings) {
        val point = pointLocked()
        outputs.stopActive()
        speech.selectEngine(settings.enginePackage)
        loaded.currentItem = point.item
        loaded.currentOffsetMs = point.offsetMs
        if (output.wantsAudio) loadOutputLocked() else emitState(isPlaying = false)
    }

    /** Play after a pause: picks up in place, or from the saved session if nothing is loaded. */
    fun resumePersistedSession() {
        val request = commandVersion.incrementAndGet()
        scope.launch {
            val isLoaded = stateMutex.withLock { loaded.hasChapter }
            if (isLoaded) {
                stateMutex.withLock { if (request == commandVersion.get()) resumeLocked() }
                return@launch
            }
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
                startPreparedPlaybackLocked(withContextReplay(prepared))
            }
        }
    }

    /**
     * The system restarted the service after the process died. Only a session that was actually
     * playing continues; one the listener paused comes back loaded and silent.
     */
    fun restoreAfterServiceRestart(requestAudioFocus: () -> Boolean) {
        val request = commandVersion.incrementAndGet()
        scope.launch {
            val persisted =
                runCatching {
                    awaitRepositoryReady()
                    preparer.persistedSession()
                }.onFailure { Timber.e(it, "TTS session restore failed") }.getOrNull()
            if (persisted == null) {
                publishIdleIfNoNewerCommand(request)
                return@launch
            }
            val shouldPlay = !persisted.isPaused && persisted.wasPlaying && requestAudioFocus()
            val isLoaded = stateMutex.withLock { loaded.hasChapter }
            if (isLoaded) {
                stateMutex.withLock {
                    if (request != commandVersion.get()) return@withLock
                    if (shouldPlay) resumeLocked() else emitState(isPlaying = false)
                }
                return@launch
            }
            val prepared = runCatching { preparer.resume() }.getOrNull()
            stateMutex.withLock {
                if (request != commandVersion.get()) return@withLock
                if (prepared == null) {
                    playbackPublisher.stop()
                } else {
                    startPreparedPlaybackLocked(prepared, startPaused = !shouldPlay, persistPaused = false)
                }
            }
        }
    }

    /**
     * Brings a saved session back as paused when the app opens, so the player is there to continue
     * from. Nothing is written, and any command that arrives first wins.
     */
    fun restorePausedSession() {
        val request = commandVersion.get()
        scope.launch {
            val prepared =
                runCatching {
                    awaitRepositoryReady()
                    preparer.resume()
                }.onFailure { Timber.e(it, "TTS session restore failed") }.getOrNull() ?: return@launch
            stateMutex.withLock {
                if (loaded.session != null || request != commandVersion.get()) return@withLock
                startPreparedPlaybackLocked(prepared, startPaused = true, persistPaused = false)
            }
        }
    }

    /** A no-op command must still confirm the idle state: that emission is a freshly started service's stop signal. */
    private fun publishIdleIfNoNewerCommand(request: Long) {
        if (request == commandVersion.get()) playbackPublisher.stop()
    }

    private fun withContextReplay(prepared: PreparedTtsPlayback): PreparedTtsPlayback {
        val savedAt = prepared.savedAt ?: return prepared
        val target =
            TtsPositionPlanning.resumePoint(
                prepared.narration,
                TtsChunkPoint(prepared.startIndex, prepared.startOffsetMs),
                System.currentTimeMillis() - savedAt,
                prepared.settings.contextReplay,
            )
        return prepared.copy(startIndex = target.chunkIndex, startOffsetMs = target.offsetMs)
    }

    private fun startPreparedPlaybackLocked(
        prepared: PreparedTtsPlayback,
        startPaused: Boolean = false,
        /** False when only reloading a saved session: its record, and its age, stay as they were. */
        persistPaused: Boolean = true,
    ) {
        TtsEngineLogging.sessionStart(prepared.story.id, prepared.chapter.id, prepared.chunks.size, prepared.startIndex, speech.initialized)
        activeSettings = prepared.settings
        outputs.stopActive()
        speech.selectEngine(prepared.settings.enginePackage)
        speech.ensureEngine()
        renderAheadStarted = false
        loaded.load(prepared)
        if (startPaused) {
            // Chapter skip while paused or a restore: move the position, stay silent.
            output = TtsOutputPhase.Silent
            val paused =
                loaded.session?.copy(
                    isPaused = true,
                    wasPlaying = false,
                    updatedAt = if (persistPaused) System.currentTimeMillis() else prepared.savedAt ?: System.currentTimeMillis(),
                )
            loaded.session = paused
            if (persistPaused) paused?.let(sessionStore::schedule)
            emitState(isPlaying = false)
            return
        }
        loadOutputLocked()
    }

    /** Starts the output at the current point; deferred until the speech engine has initialized. */
    private fun loadOutputLocked() {
        val timeline = loaded.timeline ?: return
        val engine = speech.ensureEngine()
        if (!speech.initialized) {
            output = TtsOutputPhase.WaitingForEngine
            speech.watchInit()
            emitState(isPlaying = false)
            return
        }
        // Set before applying settings: a failure there runs the error path, which goes Silent.
        output = TtsOutputPhase.Running
        val settings = activeSettings ?: repository.ttsSettings.get()
        val synthesisRate = if (outputs.useLive) settings.rate else 1f
        if (!speech.applySettings(settings, synthesisRate, ::handlePlaybackErrorLocked)) return
        val target = outputs.prepare()
        target.setVolume(1f)
        target.load(
            TtsOutputLoad(
                timeline = timeline,
                item = loaded.currentItem,
                offsetMs = loaded.currentOffsetMs,
                rate = loaded.session?.rate ?: settings.rate,
                playWhenReady = true,
                voiceKey = ttsVoiceKey(engine, speech.enginePackage, settings),
            ),
        )
        outputs.loaded = true
        writePositionLocked(loaded.savePoint(loaded.currentPoint))
        emitState(isPlaying = true)
    }

    /** Rendering does not work on this device or voice, so speak live from the same place. */
    private fun switchToLiveOutputLocked() {
        if (outputs.useLive) return
        val point = pointLocked()
        outputs.switchToLive()
        loaded.currentItem = point.item
        loaded.currentOffsetMs = point.offsetMs
        if (output.wantsAudio) loadOutputLocked() else emitState(isPlaying = false)
    }

    private fun onOutputItemStarted(item: Int) {
        val items = loaded.items
        if (output != TtsOutputPhase.Running || loaded.session == null || item !in items.indices) return
        loaded.currentItem = item
        loaded.currentOffsetMs = 0L
        if (items[item] !is TtsTimelineItem.Speech) return
        val chunk = items[item].chunkIndex
        if (chunk == loaded.session?.currentChunkIndex) return
        writePositionLocked(TtsChunkPoint(chunk))
        emitState(isPlaying = true)
    }

    private fun renderNextChapterAheadLocked() {
        val current = loaded.session ?: return
        if (renderAheadStarted) return
        renderAheadStarted = true
        scope.launch {
            val texts = runCatching { preparer.upcomingTexts(current) }.getOrDefault(emptyList())
            if (texts.isEmpty()) return@launch
            stateMutex.withLock {
                if (loaded.session?.chapterId == current.chapterId) outputs.active?.prepareAhead(texts)
            }
        }
    }

    private fun pointLocked(): TtsTimelinePoint = outputs.active?.position() ?: loaded.currentPoint

    private fun writePositionLocked(point: TtsChunkPoint) {
        loaded.writePosition(point, output.wantsAudio)?.let(sessionStore::schedule)
    }

    private fun recordJumpLocked() = loaded.recordJump(pointLocked())

    fun next() = moveByChunk(TtsSessionPlanning::nextChunkIndex)

    fun previous() = moveByChunk(TtsSessionPlanning::previousChunkIndex)

    private fun moveByChunk(target: (current: Int, count: Int) -> Int) {
        val request = commandVersion.incrementAndGet()
        scope.launch {
            stateMutex.withLock {
                if (!loaded.hasChapter) {
                    publishIdleIfNoNewerCommand(request)
                    return@withLock
                }
                val chunk = target(loaded.session?.currentChunkIndex ?: 0, loaded.chunks.size)
                moveToLocked(TtsNarrationPlanning.itemIndexForChunk(loaded.items, chunk), 0L)
            }
        }
    }

    /** Interactive seek to a chunk within the loaded chapter. */
    fun seekChunk(targetIndex: Int) {
        val request = commandVersion.incrementAndGet()
        scope.launch {
            stateMutex.withLock {
                if (!loaded.hasChapter) {
                    publishIdleIfNoNewerCommand(request)
                    return@withLock
                }
                val clamped = targetIndex.coerceIn(0, loaded.chunks.lastIndex)
                if (clamped == loaded.session?.currentChunkIndex) return@withLock
                moveToLocked(TtsNarrationPlanning.itemIndexForChunk(loaded.items, clamped), 0L)
            }
        }
    }

    /** Scrubs to a time in the chapter. A long enough jump can be undone from the history. */
    fun seekToMs(positionMs: Long) = seekWithin { positionMs }

    /** Skips by a relative time; running off the end of the chapter moves on to the next one. */
    fun seekBy(deltaMs: Long) = seekWithin { current -> current + deltaMs }

    private fun seekWithin(target: (currentMs: Long) -> Long) {
        val request = commandVersion.incrementAndGet()
        scope.launch {
            stateMutex.withLock {
                val timeline = loaded.timeline
                if (!loaded.hasChapter || timeline == null) {
                    publishIdleIfNoNewerCommand(request)
                    return@withLock
                }
                val point = pointLocked()
                val current = timeline.positionMs(point.item, point.offsetMs)
                val destination = target(current)
                if (abs(destination - current) >= HISTORY_MIN_JUMP_MS) recordJumpLocked()
                val located = timeline.locate(destination)
                moveToLocked(located.item, located.offsetMs)
            }
        }
    }

    /** Moving never changes whether audio is playing. */
    private fun moveToLocked(
        item: Int,
        offsetMs: Long,
    ) {
        val wasPlaying = isAudibleLocked()
        loaded.currentItem = item
        loaded.currentOffsetMs = offsetMs
        outputs.active?.seek(item, offsetMs)
        writePositionLocked(loaded.savePoint(loaded.currentPoint))
        emitState(isPlaying = wasPlaying)
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
        scope.launch { stateMutex.withLock { outputs.active?.setVolume(1f) } }
        emitCurrentStateAsync()
    }

    private fun onSleepTimerFinished() {
        pause()
        onSleepTimerExpired?.invoke()
    }

    private fun emitCurrentStateAsync() {
        scope.launch {
            stateMutex.withLock {
                if (loaded.session != null) emitState(isPlaying = isAudibleLocked())
            }
        }
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
        val alreadyPaused = output == TtsOutputPhase.Silent && loaded.session?.isPaused == true
        val point = pointLocked()
        output = TtsOutputPhase.Silent
        speech.cancelInitWatchdog()
        outputs.active?.pause()
        if (loaded.timeline != null) {
            loaded.currentItem = point.item
            loaded.currentOffsetMs = point.offsetMs
            // Pressing pause again must not reset how long the break has lasted.
            if (!alreadyPaused) writePositionLocked(loaded.savePoint(point))
        }
        val paused = loaded.session?.copy(isPaused = true, wasPlaying = false)
        loaded.session = paused
        if (paused != null) emitState(isPlaying = false)
        return paused
    }

    /** Continues the loaded chapter, replaying some context if the break was long. */
    private fun resumeLocked() {
        val current = loaded.session ?: return
        if (output.wantsAudio && !current.isPaused) {
            emitState(isPlaying = isAudibleLocked())
            return
        }
        val point = pointLocked()
        val saved = loaded.savePoint(point)
        val pausedForMs = System.currentTimeMillis() - current.updatedAt
        val target = TtsPositionPlanning.resumePoint(loaded.narration, saved, pausedForMs, repository.ttsSettings.get().contextReplay)
        val moved = target != saved
        loaded.currentItem = if (moved) TtsNarrationPlanning.itemIndexForChunk(loaded.items, target.chunkIndex) else point.item
        loaded.currentOffsetMs = if (moved) target.offsetMs else point.offsetMs
        val active = outputs.active
        if (active == null) {
            loadOutputLocked()
            return
        }
        output = TtsOutputPhase.Running
        active.setVolume(1f)
        if (moved) active.seek(loaded.currentItem, loaded.currentOffsetMs)
        active.play()
        writePositionLocked(target)
        emitState(isPlaying = true)
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
        if (loaded.timeline != null) loaded.writeFinalPosition(pointLocked())
        output = TtsOutputPhase.Silent
        speech.cancelInitWatchdog()
        outputs.stopActive()
        loaded.clearChapter()
        sleepTimer.setOff()
        val lastSession = loaded.session
        loaded.session = null
        // Playback has ended — signal observers to clear MediaSession state + hide the transport.
        playbackPublisher.stop()
        return lastSession
    }

    /**
     * Audio is actually coming out: the output is running and the listener has not paused. While
     * the speech engine is still starting, playback is wanted but not audible.
     */
    private fun isAudibleLocked(): Boolean = output == TtsOutputPhase.Running && loaded.session?.isPaused != true

    /** In-memory snapshot for notification refreshes; never decodes the session JSON on main. */
    fun currentSnapshot(isPlaying: Boolean): TtsPlaybackSnapshot? = buildSnapshot(isPlaying && loaded.session?.isPaused != true)

    private fun buildSnapshot(isPlaying: Boolean): TtsPlaybackSnapshot? =
        loaded.snapshot(isPlaying, pointLocked(), outputs.useLive, sleepTimer.mode)

    /** Publishes the live session; `isPlaying` distinguishes speaking from paused mid-chapter. */
    private fun emitState(isPlaying: Boolean) {
        val snapshot = buildSnapshot(isPlaying)
        if (snapshot != null) {
            playbackPublisher.publish(snapshot)
        } else if (loaded.session == null) {
            playbackPublisher.stop()
        }
    }

    private fun handlePlaybackErrorLocked(error: TtsPlaybackError) {
        val point = pointLocked()
        output = TtsOutputPhase.Silent
        speech.cancelInitWatchdog()
        outputs.active?.pause()
        loaded.currentItem = point.item
        loaded.currentOffsetMs = point.offsetMs
        if (loaded.session == null) {
            playbackPublisher.stop()
        } else {
            writePositionLocked(loaded.savePoint(point))
            emitState(isPlaying = false)
        }
        Timber.w(TtsErrorPlanning.logMessage(error))
        listeners.dispatchError(error)
    }

    private fun handleChapterFinished() {
        val currentSession =
            loaded.session ?: run {
                finishPlaybackLocked()
                return
            }
        output = TtsOutputPhase.Silent
        // With an end-of-chapter timer, stop at the start of the next chapter rather than replay this one's end.
        val stopHere = sleepTimer.onChapterCompleted()
        val request = commandVersion.incrementAndGet()
        scope.launch {
            val outcome =
                runCatching { preparer.nextChapter(currentSession) }
                    .onFailure { Timber.e(it, "TTS next-chapter preparation failed") }
            stateMutex.withLock {
                if (request != commandVersion.get() && !stopHere) return@withLock
                val failure = outcome.exceptionOrNull()
                val result = outcome.getOrNull()
                when {
                    // R16: every failure — thrown, or a next chapter that exists but cannot be
                    // played yet (e.g. still downloading) — keeps the resumable session and
                    // position; only a genuine end of story clears them below.
                    failure != null ->
                        handlePlaybackErrorLocked(
                            TtsPlaybackError(TtsPlaybackErrorKind.PreparationFailed, detail = failure.message),
                        )
                    result is NextChapterOutcome.Prepared -> startPreparedPlaybackLocked(result.playback, startPaused = stopHere)
                    result is NextChapterOutcome.Unprepared ->
                        handlePlaybackErrorLocked(
                            TtsPlaybackError(TtsPlaybackErrorKind.PreparationFailed, detail = "next chapter exists but is not playable"),
                        )
                    else -> finishPlaybackLocked()
                }
            }
        }
    }

    private fun finishPlaybackLocked() {
        output = TtsOutputPhase.Silent
        speech.cancelInitWatchdog()
        outputs.stopActive()
        loaded.clearChapter()
        sleepTimer.setOff()
        val finishedSession = loaded.session
        loaded.session = null
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
        outputs.release()
        speech.shutdown()
        output = TtsOutputPhase.Silent
    }

    private companion object {
        /** Shorter hops are ordinary listening adjustments, not places worth remembering. */
        const val HISTORY_MIN_JUMP_MS = 60_000L
    }
}
