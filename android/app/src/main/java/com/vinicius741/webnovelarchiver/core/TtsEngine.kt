package com.vinicius741.webnovelarchiver.core

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var chunks: List<String> = emptyList()
    private var index = 0
    private var session: TtsSession? = null
    private var playbackActive = false
    private val stateMutex = Mutex()

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            scope.launch {
                stateMutex.withLock {
                    val settings = storage.getTtsSettings()
                    applySettings(settings)
                }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onError(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) {
                        // R8: callbacks are not guaranteed to run on the main thread. Route the
                        // continuation onto the engine scope so all state mutation serializes.
                        scope.launch { stateMutex.withLock { handleChunkDone() } }
                    }
                })
            }
        }
    }

    fun play(story: Story, chapter: Chapter) {
        val settings = storage.getTtsSettings()
        applySettings(settings)
        val html = storage.readChapter(chapter) ?: chapter.content ?: ""
        chunks = TextCleanup.prepareTtsChunks(html, storage.getRegexRules(), settings.chunkSize)
        if (chunks.isEmpty()) return
        startSession(story, chapter, settings, 0)
        playbackActive = true
        speakCurrent()
    }

    fun resumePersistedSession(): Boolean {
        val persisted = storage.getTtsSession() ?: return false
        if (!TtsSessionPlanning.isResumeEligible(persisted)) return false
        val story = storage.getStory(persisted.storyId) ?: return false
        val chapter = story.chapters.firstOrNull { it.id == persisted.chapterId } ?: return false
        val settings = storage.getTtsSettings()
        applySettings(settings)
        val html = storage.readChapter(chapter) ?: chapter.content ?: return false
        chunks = TextCleanup.prepareTtsChunks(html, storage.getRegexRules(), TtsSessionPlanning.restoredChunkSize(settings))
        if (chunks.isEmpty()) return false
        val startIndex = TtsSessionPlanning.boundedChunkIndex(persisted, chunks.size)
        startSession(story, chapter, settings, startIndex)
        playbackActive = true
        speakCurrent()
        return true
    }

    fun next() {
        if (chunks.isEmpty()) return
        playbackActive = false
        tts?.stop()
        playbackActive = true
        index = index.coerceAtMost(chunks.lastIndex)
        speakCurrent()
    }

    fun previous() {
        if (chunks.isEmpty()) return
        playbackActive = false
        tts?.stop()
        playbackActive = true
        index = (index - 2).coerceAtLeast(0)
        speakCurrent()
    }

    fun pause() {
        playbackActive = false
        tts?.stop()
        session?.let {
            it.isPaused = true
            it.wasPlaying = false
            it.currentChunkIndex = (index - 1).coerceAtLeast(0)
            storage.saveTtsSession(it)
        }
    }

    fun stop() {
        playbackActive = false
        tts?.stop()
        storage.clearTtsSession()
    }

    private fun startSession(story: Story, chapter: Chapter, settings: TtsSettings, startIndex: Int) {
        index = startIndex
        session = TtsSession(
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

    fun availableVoices(): List<VoiceInfo> {
        return tts?.voices
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
    }

    private fun applySettings(settings: TtsSettings) {
        val engine = tts ?: return
        val selectedVoice = settings.voiceIdentifier?.let { id -> engine.voices?.firstOrNull { it.name == id } }
        if (selectedVoice != null) {
            engine.voice = selectedVoice
        } else {
            engine.language = Locale.getDefault()
        }
        engine.setPitch(settings.pitch)
        engine.setSpeechRate(settings.rate)
    }

    private fun speakCurrent() {
        val current = chunks.getOrNull(index) ?: run {
            playbackActive = false
            storage.clearTtsSession()
            return
        }
        session?.let { storage.saveTtsSession(it.copy(currentChunkIndex = index, isPaused = false, wasPlaying = true, updatedAt = System.currentTimeMillis())) }
        tts?.speak(current, TextToSpeech.QUEUE_FLUSH, null, "chapter_chunk_$index")
        index += 1
    }

    private fun handleChunkDone() {
        if (!playbackActive) return
        if (index < chunks.size) {
            speakCurrent()
            return
        }
        handleChapterFinished()
    }

    private fun handleChapterFinished() {
        val currentSession = session ?: run {
            playbackActive = false
            storage.clearTtsSession()
            return
        }
        val story = storage.getStory(currentSession.storyId) ?: run {
            playbackActive = false
            storage.clearTtsSession()
            return
        }
        story.lastReadChapterId = currentSession.chapterId
        storage.addOrUpdateStory(story)

        val nextIndex = TtsSessionPlanning.nextChapterIndex(story, currentSession.chapterId) ?: run {
            playbackActive = false
            storage.clearTtsSession()
            return
        }
        val nextChapter = story.chapters[nextIndex]
        val settings = storage.getTtsSettings()
        applySettings(settings)
        val html = storage.readChapter(nextChapter) ?: nextChapter.content
        val nextChunks = html?.let { TextCleanup.prepareTtsChunks(it, storage.getRegexRules(), settings.chunkSize) }.orEmpty()
        if (nextChunks.isEmpty()) {
            playbackActive = false
            storage.clearTtsSession()
            return
        }
        chunks = nextChunks
        startSession(story, nextChapter, settings, 0)
        playbackActive = true
        speakCurrent()
    }

    fun shutdown() {
        // R8: stop playback, release the TTS engine, and cancel the engine scope so no lingering
        // callback continuation can mutate storage after the service is torn down.
        scope.cancel()
        tts?.stop()
        tts?.shutdown()
        tts = null
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
