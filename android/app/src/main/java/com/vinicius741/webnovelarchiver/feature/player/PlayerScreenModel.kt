package com.vinicius741.webnovelarchiver.feature.player

import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.TtsHistoryEntry
import com.vinicius741.webnovelarchiver.navigation.ScreenModel
import com.vinicius741.webnovelarchiver.tts.TtsContinueListening
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackSnapshot
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The saved place a "Continue listening" offer resumes: the target chapter, and its sentence when the place is in it. */
data class ContinuePlace(
    val target: TtsContinueListening.Target,
    /** "sentence 12" when the place is inside the target chapter (not caught up), else null. */
    val sentence: String?,
)

/** What the player screens read: playback, the playing story and its audio, and the saved listening places. */
interface PlayerData {
    val playback: StateFlow<TtsPlaybackUpdate>

    /** A copy of the story the caller may hold on to. */
    fun story(id: String): Story?

    /** The voice label of the chapter's generated audio, or null when it has none. */
    fun packVoice(
        storyId: String,
        chapterId: String,
    ): String?

    /** The chapter's spoken sentences, numbered exactly as the engine numbers its chunks; empty when unreadable. */
    suspend fun chapterSentences(
        storyId: String,
        chapterId: String,
    ): List<String>

    /** The most recently heard story that still has something to play, or null. */
    suspend fun continuePlace(): ContinuePlace?
}

/** Now Playing's state: the live playback snapshot and what the screen shows around it. */
data class PlayerUiState(
    val snapshot: TtsPlaybackSnapshot?,
    /** Playback was stopped for good (an authoritative stop), so an open player closes. */
    val stopped: Boolean = false,
    /** The playing story, or null when nothing plays or the story left the library. */
    val story: Story? = null,
    /** The voice of the playing chapter's audio. */
    val voice: String? = null,
    /** The playing chapter's sentences, aligned with [TtsPlaybackSnapshot.chunkIndex]; empty until loaded. */
    val sentences: List<String> = emptyList(),
    /** With nothing playing, the saved place the screen offers to continue. */
    val continuePlace: ContinuePlace? = null,
) {
    /** The chapter on screen; Now Playing rebuilds when it changes. */
    val chapterKey: Pair<String, String>? get() = snapshot?.let { it.storyId to it.chapterId }
}

/**
 * Now Playing (`AppRoute.Player`). It follows [PlayerData.playback] while a renderer collects
 * [state], loads the playing chapter's sentences for the "Current sentence" card, finds the saved
 * place to offer when nothing plays, and sends the transport, speed, sleep timer and history
 * commands through [PlaybackControls].
 */
class PlayerScreenModel(
    private val data: PlayerData,
    private val controls: PlaybackControls,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ScreenModel(scope) {
    private var story: Story? = null
    private var sentences: Pair<Pair<String, String>, List<String>>? = null
    private var continuePlace: ContinuePlace? = null
    private val mutableState = MutableStateFlow(compute())

    val state: StateFlow<PlayerUiState> = mutableState.asStateFlow()

    init {
        this.scope.launch {
            mutableState.subscriptionCount
                .map { it > 0 }
                .distinctUntilChanged()
                .collectLatest { observed -> if (observed) observe() }
        }
    }

    /** Recomputes the state from its sources now and returns it. */
    fun refresh(): PlayerUiState = compute().also { mutableState.value = it }

    fun playPause() = controls.playPause()

    fun stop() = controls.stop()

    fun skipBack() = controls.skipBack()

    fun skipForward() = controls.skipForward()

    fun previousChapter() = controls.previousChapter()

    fun nextChapter() = controls.nextChapter()

    fun seekTo(positionMs: Long) = controls.seekTo(positionMs)

    /** Moves to the next speed preset after the live rate. */
    fun cycleRate() {
        val rate =
            data.playback.value.snapshot
                ?.rate ?: state.value.snapshot?.rate ?: return
        controls.setRate(nextRatePreset(rate))
    }

    fun setSleepTimer(minutes: Int) = controls.setSleepTimer(minutes)

    fun jumpToHistory(entry: TtsHistoryEntry) = controls.jumpToHistory(entry)

    /** Resumes the offered saved place. */
    fun continueListening() {
        val target = state.value.continuePlace?.target ?: return
        controls.resume(target.story.id, target.chapter.id)
    }

    private suspend fun observe() =
        coroutineScope {
            launch { data.playback.collect { refresh() } }
            launch {
                data.playback
                    .map { update -> update.snapshot?.let { it.storyId to it.chapterId } }
                    .distinctUntilChanged()
                    .collectLatest { key ->
                        // A lookup cut short by a newer chapter must not be kept as an empty result.
                        if (key == null) {
                            continuePlace = runCatching { data.continuePlace() }.onFailure(::rethrowCancellation).getOrNull()
                        } else if (sentences?.first != key) {
                            val loaded = runCatching { data.chapterSentences(key.first, key.second) }.onFailure(::rethrowCancellation)
                            sentences = key to loaded.getOrDefault(emptyList())
                        }
                        refresh()
                    }
            }
        }

    private fun rethrowCancellation(error: Throwable) {
        if (error is CancellationException) throw error
    }

    private fun compute(): PlayerUiState {
        val update = data.playback.value
        val snapshot = update.snapshot ?: return PlayerUiState(null, stopped = update.isAuthoritative, continuePlace = continuePlace)
        if (story?.id != snapshot.storyId) story = data.story(snapshot.storyId)
        val key = snapshot.storyId to snapshot.chapterId
        return PlayerUiState(
            snapshot = snapshot,
            story = story,
            voice = data.packVoice(snapshot.storyId, snapshot.chapterId),
            sentences = sentences?.takeIf { it.first == key }?.second.orEmpty(),
        )
    }
}
