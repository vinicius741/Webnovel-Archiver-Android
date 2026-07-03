package com.vinicius741.webnovelarchiver.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.vinicius741.webnovelarchiver.cleanup.TextCleanup
import com.vinicius741.webnovelarchiver.data.storage.AppStorage
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.Locale

/**
 * Text-to-speech playback engine (Maintainability M1: split out of Engines.kt). See the class doc in
 * Engines.kt for the R8 thread-safety model (scope + Mutex around all state mutations).
 */
class TtsEngine(
    private val context: Context,
    private val storage: AppStorage,
    /**
     * Coroutine scope that owns all TTS state mutations (R8). UtteranceProgressListener callbacks
     * and the public control methods (play/pause/next/...) are routed through [stateMutex] on this
     * scope's dispatcher, so storage reads/writes and playback continuation never race regardless of
     * which thread the Android TTS engine invokes the listener from.
     */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ttsInitialized = false
    private var pendingSpeakOnInit = false
    private var chunks: List<String> = emptyList()
    private var index = 0
    private var session: TtsSession? = null
    private var playbackActive = false
    private val stateMutex = Mutex()
    private var currentUtteranceId: String? = null
    private var utteranceSequence = 0L
    private var stallWatchdogJob: Job? = null
    private var watchdogChunkIndex = -1
    private var watchdogRetryCount = 0

    /**
     * Multicast state-change hook. The foreground service (MediaSession + notification, parity gaps 1
     * & 2) and the reader transport + highlight (parity gaps 3 & 4) each [addStateListener] /
     * [removeStateListener] independently. After every playback state change each registered listener
     * is invoked with a fresh [TtsPlaybackSnapshot] (or `null` when playback stops), so observers
     * never have to poll storage. Listeners are always invoked on the engine's main dispatcher.
     *
     * Multicast (rather than a single callback) because the engine is a process-wide singleton shared
     * by the activity and the service, and both may need to observe the same playback simultaneously.
     */
    private val stateListeners = mutableListOf<(TtsPlaybackSnapshot?) -> Unit>()
    private val errorListeners = mutableListOf<(TtsPlaybackError) -> Unit>()
    private val voiceAvailabilityListeners = mutableListOf<(List<VoiceInfo>) -> Unit>()

    /** Registers an observer invoked on every TTS state change. Idempotent: re-adding the same
     *  listener instance is a no-op, so a component may call it defensively on each lifecycle entry. */
    fun addStateListener(listener: (TtsPlaybackSnapshot?) -> Unit) {
        if (stateListeners.none { it === listener }) stateListeners.add(listener)
    }

    /** Detaches a previously-registered observer; safe to call with an unregistered listener. */
    fun removeStateListener(listener: (TtsPlaybackSnapshot?) -> Unit) {
        stateListeners.removeAll { it === listener }
    }

    fun addErrorListener(listener: (TtsPlaybackError) -> Unit) {
        if (errorListeners.none { it === listener }) errorListeners.add(listener)
    }

    fun removeErrorListener(listener: (TtsPlaybackError) -> Unit) {
        errorListeners.removeAll { it === listener }
    }

    fun addVoiceAvailabilityListener(listener: (List<VoiceInfo>) -> Unit) {
        if (voiceAvailabilityListeners.none { it === listener }) voiceAvailabilityListeners.add(listener)
        if (ttsInitialized) listener(availableVoices())
    }

    fun removeVoiceAvailabilityListener(listener: (List<VoiceInfo>) -> Unit) {
        voiceAvailabilityListeners.removeAll { it === listener }
    }

    /** Notifies every registered listener. Defensive copy so listeners may [removeStateListener] mid-dispatch. */
    private fun notifyStateListeners(snapshot: TtsPlaybackSnapshot?) {
        stateListeners.toList().forEach { runCatching { it(snapshot) } }
    }

    private fun notifyErrorListeners(error: TtsPlaybackError) {
        Timber.w(TtsErrorPlanning.logMessage(error))
        errorListeners.toList().forEach { runCatching { it(error) } }
    }

    private fun notifyVoiceAvailabilityListeners() {
        val voices = availableVoices()
        voiceAvailabilityListeners.toList().forEach { runCatching { it(voices) } }
    }

    override fun onInit(status: Int) {
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
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            routeUtteranceError(utteranceId, TextToSpeech.ERROR)
                        }

                        override fun onError(
                            utteranceId: String?,
                            errorCode: Int,
                        ) {
                            routeUtteranceError(utteranceId, errorCode)
                        }

                        override fun onDone(utteranceId: String?) {
                            routeUtteranceDone(utteranceId)
                        }
                    },
                )
                val settingsApplied = applySettingsLocked(storage.getTtsSettings())
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
        scope.launch { stateMutex.withLock { playLocked(story, chapter) } }
    }

    private fun playLocked(
        story: Story,
        chapter: Chapter,
    ) {
        val settings = storage.getTtsSettings()
        ensureEngineLocked()
        if (!applySettingsLocked(settings) && ttsInitialized) return
        val html = storage.readChapter(chapter) ?: chapter.content ?: ""
        chunks = TextCleanup.prepareTtsChunks(html, storage.getRegexRules(), settings.chunkSize)
        if (chunks.isEmpty()) return
        startSession(story, chapter, settings, 0)
        playbackActive = true
        speakCurrentLocked()
    }

    /**
     * Begin narration at a specific chunk index — used by the reader's tap-to-start-from-paragraph
     * (parity gap 3). [chunkIndex] maps 1:1 to a `data-tts-group` in the annotated reader HTML, and
     * the chunk list produced here is byte-for-byte aligned with [TextCleanup.prepareTtsAnnotatedHtml]
     * (both reuse the same grouping logic). Out-of-range indices are clamped.
     */
    fun playFromChunk(
        story: Story,
        chapter: Chapter,
        chunkIndex: Int,
    ) {
        scope.launch { stateMutex.withLock { playFromChunkLocked(story, chapter, chunkIndex) } }
    }

    private fun playFromChunkLocked(
        story: Story,
        chapter: Chapter,
        chunkIndex: Int,
    ) {
        val settings = storage.getTtsSettings()
        ensureEngineLocked()
        if (!applySettingsLocked(settings) && ttsInitialized) return
        val html = storage.readChapter(chapter) ?: chapter.content ?: ""
        chunks = TextCleanup.prepareTtsChunks(html, storage.getRegexRules(), settings.chunkSize)
        if (chunks.isEmpty()) return
        val clamped = chunkIndex.coerceIn(0, chunks.lastIndex)
        startSession(story, chapter, settings, clamped)
        playbackActive = true
        speakCurrentLocked()
    }

    fun resumePersistedSession() {
        scope.launch { stateMutex.withLock { resumePersistedSessionLocked() } }
    }

    private fun resumePersistedSessionLocked(): Boolean {
        val persisted = storage.getTtsSession() ?: return false
        if (!TtsSessionPlanning.isResumeEligible(persisted)) return false
        val story = storage.getStory(persisted.storyId) ?: return false
        val chapter = story.chapters.firstOrNull { it.id == persisted.chapterId } ?: return false
        val settings = storage.getTtsSettings()
        ensureEngineLocked()
        if (!applySettingsLocked(settings) && ttsInitialized) return false
        val html = storage.readChapter(chapter) ?: chapter.content ?: return false
        chunks = TextCleanup.prepareTtsChunks(html, storage.getRegexRules(), TtsSessionPlanning.restoredChunkSize(settings))
        if (chunks.isEmpty()) return false
        val startIndex = TtsSessionPlanning.boundedChunkIndex(persisted, chunks.size)
        startSession(story, chapter, settings, startIndex)
        playbackActive = true
        speakCurrentLocked()
        return true
    }

    fun next() {
        scope.launch { stateMutex.withLock { nextLocked() } }
    }

    private fun nextLocked() {
        if (chunks.isEmpty()) return
        playbackActive = false
        tts?.stop()
        playbackActive = true
        index = TtsSessionPlanning.nextChunkRequestIndex(index, chunks.size)
        speakCurrentLocked()
    }

    fun previous() {
        scope.launch { stateMutex.withLock { previousLocked() } }
    }

    private fun previousLocked() {
        if (chunks.isEmpty()) return
        playbackActive = false
        tts?.stop()
        playbackActive = true
        index = TtsSessionPlanning.previousChunkRequestIndex(index, chunks.size)
        speakCurrentLocked()
    }

    fun pause() {
        scope.launch { stateMutex.withLock { pauseLocked() } }
    }

    private fun pauseLocked() {
        playbackActive = false
        pendingSpeakOnInit = false
        cancelStallWatchdog()
        tts?.stop()
        session?.let {
            it.isPaused = true
            it.wasPlaying = false
            it.currentChunkIndex = (index - 1).coerceAtLeast(0)
            storage.saveTtsSession(it)
        }
        emitState(isPlaying = false)
    }

    fun stop() {
        scope.launch { stateMutex.withLock { stopLocked() } }
    }

    private fun stopLocked() {
        playbackActive = false
        pendingSpeakOnInit = false
        currentUtteranceId = null
        cancelStallWatchdog()
        tts?.stop()
        storage.clearTtsSession()
        // Playback has ended — signal observers to clear MediaSession state + hide the transport.
        notifyStateListeners(null)
    }

    /** Live chunk count of the currently loaded chapter (0 when nothing is loaded). */
    fun currentChunkCount(): Int = chunks.size

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
        index = startIndex
        watchdogChunkIndex = -1
        watchdogRetryCount = 0
        session =
            TtsSession(
                storyId = story.id,
                chapterId = chapter.id,
                chapterTitle = chapter.title,
                currentChunkIndex = startIndex,
                isPaused = false,
                wasPlaying = true,
                chunkSize = settings.chunkSize,
                voiceIdentifier = settings.voiceIdentifier,
                rate = settings.rate,
                pitch = settings.pitch,
            )
    }

    fun availableVoices(): List<VoiceInfo> =
        (tts ?: TextToSpeech(context, this).also { tts = it })
            ?.voices
            ?.filter { !it.isNetworkConnectionRequired }
            ?.sortedWith(compareBy<Voice> { it.locale.toLanguageTag() }.thenBy { it.name })
            ?.map {
                VoiceInfo(
                    identifier = it.name,
                    name = it.name,
                    language = it.locale.toLanguageTag(),
                    quality = it.quality,
                    latency = it.latency,
                )
            }
            ?: emptyList()

    private fun applySettingsLocked(settings: TtsSettings): Boolean {
        val engine = ensureEngineLocked() ?: return false
        if (!ttsInitialized) return false
        val selectedVoice = settings.voiceIdentifier?.let { id -> engine.voices?.firstOrNull { it.name == id } }
        if (selectedVoice != null) {
            if (engine.setVoice(selectedVoice) == TextToSpeech.ERROR) {
                handlePlaybackErrorLocked(
                    TtsPlaybackError(
                        kind = TtsPlaybackErrorKind.VoiceRejected,
                        detail = selectedVoice.name,
                    ),
                )
                return false
            }
        } else if (settings.voiceIdentifier != null) {
            handlePlaybackErrorLocked(
                TtsPlaybackError(
                    kind = TtsPlaybackErrorKind.VoiceUnavailable,
                    detail = settings.voiceIdentifier,
                ),
            )
            return false
        } else {
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
            chunks.getOrNull(index) ?: run {
                finishPlaybackLocked()
                return
            }
        session?.let {
            val updated =
                it.copy(
                    currentChunkIndex = index,
                    isPaused = false,
                    wasPlaying = true,
                    updatedAt = System.currentTimeMillis(),
                )
            session = updated
            storage.saveTtsSession(updated)
        }
        val utteranceId = "chapter_chunk_${index}_${utteranceSequence++}"
        if (index != watchdogChunkIndex) {
            watchdogChunkIndex = index
            watchdogRetryCount = 0
        }
        val speakResult = engine.speak(current, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (speakResult == TextToSpeech.ERROR) {
            handlePlaybackErrorLocked(TtsPlaybackError(TtsPlaybackErrorKind.SpeakFailed))
            return
        }
        currentUtteranceId = utteranceId
        scheduleStallWatchdog(utteranceId, current)
        // Snapshot reflects the chunk now being spoken (`index`), before the post-increment.
        emitState(isPlaying = true)
        index += 1
    }

    /**
     * Builds a [TtsPlaybackSnapshot] from the live in-memory session + chunk list and fans it out to
     * every registered state listener (see [addStateListener]). `isPlaying` distinguishes "actively
     * speaking" from "paused mid-chapter".
     */
    private fun emitState(isPlaying: Boolean) {
        val snapshot =
            TtsPlaybackState.snapshotForSession(
                session = session,
                totalChunks = chunks.size,
                isPlaying = isPlaying,
            )
        notifyStateListeners(snapshot)
    }

    private fun routeUtteranceDone(utteranceId: String?) {
        scope.launch {
            stateMutex.withLock {
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
        val rate = session?.rate ?: storage.getTtsSettings().rate
        val timeoutMs = TtsWatchdogPlanning.timeoutMs(text.length, rate)
        stallWatchdogJob =
            scope.launch {
                delay(timeoutMs)
                stateMutex.withLock {
                    if (!playbackActive || currentUtteranceId != utteranceId) return@withLock
                    val retryIndex = session?.currentChunkIndex ?: (index - 1).coerceAtLeast(0)
                    if (watchdogRetryCount == 0) {
                        watchdogRetryCount += 1
                        Timber.w("TTS stalled for utterance %s; retrying chunk %s", utteranceId, retryIndex)
                        tts?.stop()
                        currentUtteranceId = null
                        stallWatchdogJob = null
                        index = retryIndex.coerceIn(0, chunks.lastIndex)
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
                    currentChunkIndex = (index - 1).coerceAtLeast(0),
                    isPaused = true,
                    wasPlaying = false,
                    updatedAt = System.currentTimeMillis(),
                )
            session = updated
            storage.saveTtsSession(updated)
            emitState(isPlaying = false)
        } ?: notifyStateListeners(null)
        notifyErrorListeners(error)
    }

    private fun handleChunkDone() {
        if (!playbackActive) return
        if (index < chunks.size) {
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
        val story =
            storage.getStory(currentSession.storyId) ?: run {
                finishPlaybackLocked()
                return
            }
        story.lastReadChapterId = currentSession.chapterId
        storage.addOrUpdateStory(story)

        val nextIndex =
            TtsSessionPlanning.nextChapterIndex(story, currentSession.chapterId) ?: run {
                finishPlaybackLocked()
                return
            }
        val nextChapter = story.chapters[nextIndex]
        val settings = storage.getTtsSettings()
        if (!applySettingsLocked(settings) && ttsInitialized) {
            return
        }
        val html = storage.readChapter(nextChapter) ?: nextChapter.content
        val nextChunks = html?.let { TextCleanup.prepareTtsChunks(it, storage.getRegexRules(), settings.chunkSize) }.orEmpty()
        if (nextChunks.isEmpty()) {
            finishPlaybackLocked()
            return
        }
        chunks = nextChunks
        startSession(story, nextChapter, settings, 0)
        playbackActive = true
        speakCurrentLocked()
    }

    private fun finishPlaybackLocked() {
        playbackActive = false
        pendingSpeakOnInit = false
        currentUtteranceId = null
        cancelStallWatchdog()
        storage.clearTtsSession()
        notifyStateListeners(null)
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

    /** Alias for [shutdown]; provided so the engine exposes the conventional close() lifecycle. */
    fun close() = shutdown()
}

data class VoiceInfo(
    val identifier: String,
    val name: String,
    val language: String,
    val quality: Int,
    val latency: Int,
)
