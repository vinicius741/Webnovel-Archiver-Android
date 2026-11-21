package com.vinicius741.webnovelarchiver.feature.reader

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.TtsStoryPosition
import com.vinicius741.webnovelarchiver.feature.player.nextRatePreset
import com.vinicius741.webnovelarchiver.feature.player.rateLabel
import com.vinicius741.webnovelarchiver.navigation.ScreenModel
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackSnapshot
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackState
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackUpdate
import com.vinicius741.webnovelarchiver.tts.TtsSessionPlanning
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** What the reader reads and changes: the chapter document, the story, playback, and the reader's settings. */
internal interface ReaderData {
    val playback: StateFlow<TtsPlaybackUpdate>

    /** The story as the library holds it now. */
    fun story(id: String): Story?

    /** Reads, sanitizes and renders the chapter off the main thread. */
    suspend fun prepare(
        storyId: String,
        chapterId: String,
    ): ReaderPreparation

    /** Toggles the reading bookmark on the chapter; the updated story, or null when it is gone. */
    suspend fun toggleBookmark(
        storyId: String,
        chapterId: String,
    ): Story?

    /** Stores the reader's text size and "Pure black" over the latest saved display preferences. */
    suspend fun saveDisplay(display: ReaderDisplay)

    suspend fun listenPosition(storyId: String): TtsStoryPosition?

    /** The current narration voice's label, or null when it cannot be read. */
    fun voiceLabel(): String?

    /** The listening speed in the TTS settings. */
    fun savedRate(): Float
}

/** The reader's read-aloud commands, as the playback service and the listen starts run them. */
internal interface ReaderPlayback {
    /** Plays [chapterId] from the story's saved place in it, generating its audio first when it has none. */
    fun playFromPlace(
        storyId: String,
        chapterId: String,
    )

    /**
     * "Continue listening": generates [savedChapterId]'s audio if needed, starts the session as the
     * reader on [readerChapterId] asks, and opens [savedChapterId] if that reader is still on screen.
     */
    fun continueFrom(
        storyId: String,
        savedChapterId: String,
        readerChapterId: String,
    )

    /** Plays [chapterId] from its first sentence. */
    fun playFromBeginning(
        storyId: String,
        chapterId: String,
    )

    fun resumeSession()

    fun pause()

    fun skipBack()

    fun setRate(rate: Float)

    fun stop()
}

/** The reader's display settings: text size and "Pure black" (the stored dark-reader preference). */
internal data class ReaderDisplay(
    val fontScale: Float,
    val pureBlack: Boolean,
)

/** The reader page's content. */
internal sealed interface ReaderPage {
    data object Loading : ReaderPage

    /**
     * A prepared chapter. [generation] changes only when the chapter is loaded again (opening it,
     * "Pure black"), which rebuilds the page; a text-size change only refreshes [document]'s HTML.
     */
    data class Ready(
        val document: ReaderDocument,
        val generation: Int,
    ) : ReaderPage

    /** The story or chapter is no longer in the library. */
    data object Missing : ReaderPage

    /** The chapter could not be read. */
    data object Failed : ReaderPage
}

internal data class ReaderUiState(
    val page: ReaderPage = ReaderPage.Loading,
    /** This chapter holds the story's reading bookmark. */
    val bookmarked: Boolean = false,
    /** This chapter's read-aloud playback, or null when another chapter (or nothing) is loaded. */
    val playing: TtsPlaybackSnapshot? = null,
    val display: ReaderDisplay = ReaderDisplay(fontScale = 1f, pureBlack = false),
) {
    /** The docked player replaces chapter navigation while this chapter is read aloud. */
    val dockVisible: Boolean get() = playing != null

    val document: ReaderDocument? get() = (page as? ReaderPage.Ready)?.document
}

/** "Continue" and "listen from here" differ: the saved place is in another chapter, so the listener chooses. */
internal data class ReadAloudChoice(
    val savedChapterId: String,
    val savedChapterTitle: String,
    /** The saved sentence, counted from 1. */
    val savedSentence: Int,
)

/**
 * One reader route (`AppRoute.Reader`): the prepared chapter, its bookmark, this chapter's slice of
 * read-aloud playback (for the docked player), and the reader's display settings. The page's
 * highlight, following and chapter auto-follow stay in [ReaderReadAlong], bound to the WebView.
 *
 * Playback is followed only while a renderer collects [state]. Toasts come out of [messages] and
 * the read-aloud choice sheet out of [readAloudChoices].
 */
internal class ReaderScreenModel(
    private val storyId: String,
    private val chapterId: String,
    private val data: ReaderData,
    private val playback: ReaderPlayback,
    private val computation: CoroutineDispatcher = Dispatchers.Default,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ScreenModel(scope) {
    private val mutableState = MutableStateFlow(ReaderUiState())
    private val messageChannel = Channel<String>(BUFFER, BufferOverflow.DROP_OLDEST)
    private val choiceChannel = Channel<ReadAloudChoice>(1, BufferOverflow.DROP_OLDEST)
    private var loadJob: Job? = null
    private var fontJob: Job? = null
    private var generation = 0

    // A held stepper changes the size many times a second: writes run one after another and
    // only the latest display is stored.
    private var writeTicket = 0
    private var writes: Job? = null

    val state: StateFlow<ReaderUiState> = mutableState.asStateFlow()

    /** One-off messages for the user, buffered until a renderer shows them. */
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    /** Asks the renderer to let the listener pick between the saved place and this chapter. */
    val readAloudChoices: Flow<ReadAloudChoice> = choiceChannel.receiveAsFlow()

    init {
        this.scope.launch {
            mutableState.subscriptionCount
                .map { it > 0 }
                .distinctUntilChanged()
                .collectLatest { observed ->
                    if (observed) data.playback.collect { update -> mutate { copy(playing = playingAfter(playing, update)) } }
                }
        }
    }

    /**
     * Loads the chapter when the reader is opened ([reload]), or when a re-render (a fold) finds
     * nothing loaded or loading; a re-render otherwise keeps the prepared chapter.
     */
    fun open(reload: Boolean) {
        val page = state.value.page
        val kept = page is ReaderPage.Ready || (page == ReaderPage.Loading && loadJob?.isActive == true)
        if (reload || !kept) load()
    }

    fun toggleBookmark() {
        scope.launch {
            val updated = data.toggleBookmark(storyId, chapterId) ?: return@launch
            val active = updated.lastReadChapterId == chapterId
            mutate { copy(bookmarked = active) }
            messageChannel.trySend(if (active) "Bookmarked" else "Bookmark cleared")
        }
    }

    /**
     * The chapter Previous ([delta] -1) or Next (+1) opens: the nearest downloaded one that way in
     * the story as it is now, or null when there is none.
     */
    fun chapterInDirection(delta: Int): String? {
        val chapters = (data.story(storyId) ?: state.value.document?.story)?.chapters ?: return null
        return readableChapter(chapters, chapterId, delta)
    }

    /** Stores [next] and applies it: a new size refreshes the page HTML, "Pure black" reloads the chapter. */
    fun updateDisplay(next: ReaderDisplay) {
        val before = state.value.display
        if (next == before) return
        mutate { copy(display = next) }
        saveDisplay(next)
        if (next.fontScale != before.fontScale) refreshFontScale(next.fontScale)
        // The reloaded page reads the stored preference, so it waits for the write.
        if (next.pureBlack != before.pureBlack) {
            scope.launch {
                awaitWrites()
                load()
            }
        }
    }

    /** "Voice · speed" for the reader settings sheet. */
    fun voiceSummary(): String {
        val rate =
            data.playback.value.snapshot
                ?.rate ?: data.savedRate()
        return listOfNotNull(data.voiceLabel(), rateLabel(rate)).joinToString(" · ")
    }

    /** Reads this chapter aloud, or asks first when the story's saved place is in another chapter. */
    fun startReadAloud() {
        val shown = state.value.document?.story ?: return
        scope.launch {
            val story = data.story(storyId) ?: shown
            val saved = TtsSessionPlanning.resolveStartPosition(story, chapterId, data.listenPosition(storyId))
            if (saved.chapterId == chapterId) {
                playback.playFromPlace(storyId, chapterId)
                return@launch
            }
            val title =
                story.chapters
                    .firstOrNull { it.id == saved.chapterId }
                    ?.title
                    .orEmpty()
            choiceChannel.trySend(ReadAloudChoice(saved.chapterId, title, saved.chunkIndex + 1))
        }
    }

    fun continueListening(choice: ReadAloudChoice) = playback.continueFrom(storyId, choice.savedChapterId, chapterId)

    fun listenFromThisChapter() = playback.playFromBeginning(storyId, chapterId)

    /**
     * The docked play/pause. Cold start seeds the dock from the persisted session, so a "playing"
     * seed with no live engine session would show a phantom Pause icon: resume then.
     */
    fun dockPlayPause() {
        val live = data.playback.value.snapshot
        if (live == null || state.value.playing?.isPaused != false) playback.resumeSession() else playback.pause()
    }

    fun skipBack() = playback.skipBack()

    fun cycleRate() {
        val rate =
            data.playback.value.snapshot
                ?.rate ?: state.value.playing?.rate ?: 1f
        playback.setRate(nextRatePreset(rate))
    }

    fun stopPlayback() = playback.stop()

    private fun load() {
        loadJob?.cancel()
        fontJob?.cancel()
        mutate { copy(page = ReaderPage.Loading) }
        loadJob =
            scope.launch {
                when (val preparation = data.prepare(storyId, chapterId)) {
                    is ReaderPreparation.Ready -> {
                        val document = preparation.document
                        val seed = currentReaderSnapshot(document.persistedSession, storyId, chapterId, document.annotated.chunks.size)
                        mutate {
                            copy(
                                page = ReaderPage.Ready(document, ++generation),
                                bookmarked = document.story.lastReadChapterId == chapterId,
                                playing = playingAfter(seed, data.playback.value),
                                display = ReaderDisplay(document.display.readerFontScale, document.display.readerDark),
                            )
                        }
                    }
                    ReaderPreparation.Missing -> mutate { copy(page = ReaderPage.Missing) }
                    is ReaderPreparation.Failed -> {
                        // IOException messages can embed absolute file paths; the detail stays in logs only.
                        Timber.w(preparation.cause, "Reader preparation failed")
                        mutate { copy(page = ReaderPage.Failed) }
                    }
                }
            }
    }

    /** Re-renders the prepared chapter's HTML at [scale], so a later rebuild (a fold) shows the new size. */
    private fun refreshFontScale(scale: Float) {
        val ready = state.value.page as? ReaderPage.Ready ?: return
        fontJob?.cancel()
        fontJob =
            scope.launch {
                val updated = withContext(computation) { ready.document.withFontScale(scale) }
                val current = state.value.page as? ReaderPage.Ready
                if (current?.generation == ready.generation) mutate { copy(page = ReaderPage.Ready(updated, ready.generation)) }
            }
    }

    private fun saveDisplay(display: ReaderDisplay) {
        val ticket = ++writeTicket
        val previous = writes
        writes =
            scope.launch {
                previous?.join()
                if (ticket != writeTicket) return@launch
                runCatching { data.saveDisplay(display) }.onFailure {
                    Timber.e(it, "Reader settings save failed")
                    messageChannel.trySend("Could not save reader settings")
                }
            }
    }

    private suspend fun awaitWrites() {
        while (true) {
            val write = writes ?: return
            write.join()
            if (write === writes) return
        }
    }

    private fun playingAfter(
        current: TtsPlaybackSnapshot?,
        update: TtsPlaybackUpdate,
    ): TtsPlaybackSnapshot? = TtsPlaybackState.snapshotAfterUpdate(current, update, storyId, chapterId)

    private inline fun mutate(change: ReaderUiState.() -> ReaderUiState) {
        mutableState.value = mutableState.value.change()
    }

    private companion object {
        const val BUFFER = 8
    }
}

/**
 * The nearest downloaded chapter from [chapterId] toward [delta], skipping chapters that are not
 * downloaded (they have nothing to read); null when there is none that way or [chapterId] is gone.
 */
internal fun readableChapter(
    chapters: List<Chapter>,
    chapterId: String,
    delta: Int,
): String? {
    val start = chapters.indexOfFirst { it.id == chapterId }
    if (start < 0) return null
    var index = start + delta
    while (index in chapters.indices) {
        if (chapters[index].downloaded) return chapters[index].id
        index += delta
    }
    return null
}
