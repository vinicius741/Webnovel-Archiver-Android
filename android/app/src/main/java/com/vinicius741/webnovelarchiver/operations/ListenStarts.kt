package com.vinicius741.webnovelarchiver.operations

import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.data.repository.getTtsStoryPosition
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.TtsStoryPosition
import com.vinicius741.webnovelarchiver.narration.NarrationCoordinator
import com.vinicius741.webnovelarchiver.narration.NarrationJobState
import com.vinicius741.webnovelarchiver.narration.NarrationOrigin
import com.vinicius741.webnovelarchiver.narration.NarrationQueueDocument
import com.vinicius741.webnovelarchiver.tts.TtsContinueListening
import com.vinicius741.webnovelarchiver.tts.TtsEngine
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackUpdate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** What starting to listen reads: chapter audio, the generation queue, playback and listening places. */
interface ListenStartData {
    val packsRevision: Flow<Long>
    val queue: StateFlow<NarrationQueueDocument>
    val playback: StateFlow<TtsPlaybackUpdate>

    suspend fun isReady(
        storyId: String,
        chapterId: String,
    ): Boolean

    /** Queues the chapter at the front of the generation queue in the current voice. */
    suspend fun enqueueFirst(
        storyId: String,
        chapterId: String,
    )

    fun chapterTitle(
        storyId: String,
        chapterId: String,
    ): String

    suspend fun position(storyId: String): TtsStoryPosition?

    fun story(storyId: String): Story?
}

/** The screen side of starting to listen: messages, the permission prompt and the playback service. */
interface ListenStartUi {
    /** The activity's scope: a pending start ends with the activity. */
    val scope: CoroutineScope

    fun toast(text: String)

    fun requestNotificationPermission()

    /** Returns once the app is in the foreground, where the playback service may start. */
    suspend fun awaitForeground()

    /** Plays the chapter from the story's saved place in it. */
    fun start(
        storyId: String,
        chapterId: String,
    )

    fun startFromBeginning(
        storyId: String,
        chapterId: String,
    )

    /** Resumes the loaded session from its exact live point. */
    fun resumeSession()
}

/**
 * Every "listen" request in the app, from any screen. A chapter plays only from its generated audio,
 * so [playIfGenerated] plays it at once, or queues it first and starts it once the audio is ready.
 * One request waits at a time: a newer one replaces it. Main thread only.
 */
class ListenStarts(
    private val data: ListenStartData,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** The chapter waiting for its audio so it can start playing; a newer request replaces it. */
    private var pending: Job? = null

    /**
     * Plays a chapter's generated audio, or, when it has none yet, generates it first (at the front of
     * the queue) and runs [start] once it is ready.
     */
    fun playIfGenerated(
        ui: ListenStartUi,
        storyId: String,
        chapterId: String,
        start: () -> Unit,
    ) {
        pending?.cancel()
        // What was playing when asked: only audio started after the request cancels the wait.
        val playingAtRequest =
            data.playback.value.snapshot
                ?.let { it.storyId to it.chapterId }
        pending =
            ui.scope.launch {
                val ready =
                    runCatching { data.isReady(storyId, chapterId) }
                        .onFailure { Timber.w(it, "Narration status failed") }
                        .getOrDefault(false)
                if (ready) {
                    start()
                    return@launch
                }
                val queued = withContext(io) { runCatching { data.enqueueFirst(storyId, chapterId) } }
                if (queued.isFailure) {
                    Timber.e(queued.exceptionOrNull(), "Narration enqueue failed")
                    ui.toast("Could not generate the audio")
                    return@launch
                }
                ui.toast("Generating \"${data.chapterTitle(storyId, chapterId)}\". It plays when it's ready.")
                ui.requestNotificationPermission()
                if (awaitAudio(storyId, chapterId, playingAtRequest)) {
                    // Starting the playback service needs the app in the foreground: wait for the user to come back.
                    ui.awaitForeground()
                    runCatching(start).onFailure { Timber.w(it, "Pending play could not start") }
                }
            }
    }

    /**
     * Plays [chapterId] itself: from the story's saved listening place when that place is in this
     * chapter, else from its start. "Continue" entry points use [continueListening] instead.
     */
    fun playChapter(
        ui: ListenStartUi,
        storyId: String,
        chapterId: String,
    ) {
        playIfGenerated(ui, storyId, chapterId) {
            ui.scope.launch {
                val position = data.position(storyId)
                if (position != null && !position.caughtUp && position.chapterId == chapterId) {
                    ui.start(storyId, chapterId)
                } else {
                    ui.startFromBeginning(storyId, chapterId)
                }
            }
        }
    }

    /**
     * Continues the story's audio from its own listening place, the one the bookmark never moves.
     * [fallbackChapterId] plays from its start only when the story has no place to continue from.
     */
    fun continueListening(
        ui: ListenStartUi,
        storyId: String,
        fallbackChapterId: String,
    ) {
        // Cancelled on tap, as playIfGenerated's own wait is, so a newer listen request always wins.
        pending?.cancel()
        val live = data.playback.value.snapshot
        if (live?.storyId == storyId) {
            // Already loaded: carry on from the exact live point instead of restarting its sentence.
            if (live.isPlaying) ui.toast("Already playing") else ui.resumeSession()
            return
        }
        pending =
            ui.scope.launch {
                val story = data.story(storyId) ?: return@launch
                val position = data.position(storyId)
                when (val start = TtsContinueListening.listenStart(story, fallbackChapterId, position)) {
                    // playIfGenerated replaces this job as the pending play; it does not suspend before that.
                    is TtsContinueListening.ListenStart.Play ->
                        playIfGenerated(ui, storyId, start.chapterId) { ui.start(storyId, start.chapterId) }
                    TtsContinueListening.ListenStart.CaughtUp -> ui.toast("You've listened to the end. Nothing new yet.")
                    is TtsContinueListening.ListenStart.NotDownloaded -> ui.toast("Download Ch. ${start.number} to keep listening")
                }
            }
    }

    /**
     * Waits until the chapter's audio is published (true), or gives up (false) when its job goes away or
     * fails, or other audio than [playingAtRequest] starts.
     */
    private suspend fun awaitAudio(
        storyId: String,
        chapterId: String,
        playingAtRequest: Pair<String, String>?,
    ): Boolean {
        val outcome =
            combine(data.packsRevision, data.queue, data.playback) { _, queue, playback ->
                val snapshot = playback.snapshot
                val playing = snapshot?.let { it.storyId to it.chapterId }
                val job = queue.jobs.firstOrNull { it.storyId == storyId && it.chapterId == chapterId }
                when {
                    runCatching { data.isReady(storyId, chapterId) }.getOrDefault(false) -> PendingPlay.READY
                    snapshot?.isPlaying == true && playing != storyId to chapterId && playing != playingAtRequest -> PendingPlay.GIVE_UP
                    job == null || job.state == NarrationJobState.FAILED -> PendingPlay.GIVE_UP
                    else -> PendingPlay.WAIT
                }
            }.first { it != PendingPlay.WAIT }
        return outcome == PendingPlay.READY
    }

    private enum class PendingPlay { WAIT, READY, GIVE_UP }
}

/** [ListenStartData] over the narration coordinator, the playback engine and the repository. */
class AppListenStartData(
    private val narration: NarrationCoordinator,
    private val tts: TtsEngine,
    private val repository: AppRepository,
) : ListenStartData {
    override val packsRevision: Flow<Long> get() = narration.packsRevision
    override val queue: StateFlow<NarrationQueueDocument> get() = narration.queueState
    override val playback: StateFlow<TtsPlaybackUpdate> get() = tts.playbackState

    override suspend fun isReady(
        storyId: String,
        chapterId: String,
    ): Boolean = narration.isReady(storyId, chapterId)

    override suspend fun enqueueFirst(
        storyId: String,
        chapterId: String,
    ) {
        narration.enqueue(storyId, listOf(chapterId), narration.currentVoice(), NarrationOrigin.MANUAL, front = true)
    }

    override fun chapterTitle(
        storyId: String,
        chapterId: String,
    ): String = narration.chapterTitle(storyId, chapterId)

    override suspend fun position(storyId: String): TtsStoryPosition? = repository.getTtsStoryPosition(storyId)

    override fun story(storyId: String): Story? = repository.story(storyId)
}
