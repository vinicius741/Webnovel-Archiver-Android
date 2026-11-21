package com.vinicius741.webnovelarchiver.feature.narration

import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.feature.player.ContinuePlace
import com.vinicius741.webnovelarchiver.feature.player.PlaybackControls
import com.vinicius741.webnovelarchiver.feature.player.PlayerData
import com.vinicius741.webnovelarchiver.narration.NarrationCompressionStatus
import com.vinicius741.webnovelarchiver.narration.NarrationJobState
import com.vinicius741.webnovelarchiver.narration.NarrationPack
import com.vinicius741.webnovelarchiver.narration.NarrationProgress
import com.vinicius741.webnovelarchiver.narration.NarrationQueueDocument
import com.vinicius741.webnovelarchiver.narration.NarrationServerPlanning
import com.vinicius741.webnovelarchiver.navigation.ScreenModel
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackSnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Generated packs by story: those of chapters in the library, and the detached rest. */
internal data class GeneratedAudio(
    val attached: Map<String, List<NarrationPack>>,
    val detached: Map<String, List<NarrationPack>>,
)

/** What the Listen screen reads and changes: the generation queue and its progress, the packs, and playback. */
internal interface ListenData : PlayerData {
    val queue: StateFlow<NarrationQueueDocument>
    val progress: StateFlow<NarrationProgress?>
    val generationRunning: StateFlow<Boolean>
    val packsRevision: StateFlow<Long>
    val compression: StateFlow<NarrationCompressionStatus>

    fun chapterTitle(
        storyId: String,
        chapterId: String,
    ): String

    /** Splits the packs by whether their chapter is still in the library (reads every pack's story). */
    fun generatedAudio(): GeneratedAudio

    fun usedBytes(): Long

    fun freeBytes(): Long

    /** Whether the current voice is a built-in model, the only kind a computer can generate. */
    fun voiceCanUseComputers(): Boolean

    /** The saved place found last, so a new screen shows it before looking again. */
    fun lastContinuePlace(): ContinuePlace?

    fun pause()

    fun resume()

    fun retry(jobId: String)

    fun retryAll()

    fun clearFailed()

    fun clearQueue()

    fun remove(jobIds: Set<String>)

    fun moveToTop(jobId: String)

    fun moveStoryToTop(storyId: String)

    suspend fun deleteAudio(
        storyId: String,
        chapterIds: List<String>,
    )

    fun setGenerateOn(
        serverIds: List<String>,
        phoneFallback: Boolean,
    )
}

/** One story's card in the queue. */
internal data class ListenQueueCard(
    val group: QueueGroup,
    /** Null when the story is no longer in the library. */
    val story: Story?,
    /** Each job's chapter title, by job id. */
    val chapterTitles: Map<String, String>,
    val isFirst: Boolean,
)

/**
 * What a Listen build depends on: the queue without its live-only fields, the packs, and the
 * running job. Keep-ahead and speed measurements change without anything to rebuild (the stepper
 * and the live banner show them), and a rebuild would also cut a held stepper.
 */
internal data class ListenBuildKey(
    val queue: NarrationQueueDocument,
    val packsRevision: Long,
    val runningJobId: String?,
)

/** The part of the Listen screen built once per [key]; a different key rebuilds the screen. */
internal data class ListenContent(
    val key: ListenBuildKey,
    /** The queue as it was when [key] changed, live fields included. */
    val queue: NarrationQueueDocument,
    val cards: List<ListenQueueCard>,
    val failed: Int,
    val audio: GeneratedAudio,
    /** The stories of [GeneratedAudio.attached], by id. */
    val audioStories: Map<String, Story?>,
    val usedBytes: Long,
    val freeBytes: Long,
    /** The queue offers "Generate on": there is a computer, and the current voice can generate on one. */
    val showGenerateOn: Boolean,
) {
    val runningJobId: String? get() = key.runningJobId
}

/** The top card: what plays, else the place to continue, else nothing. */
internal sealed interface ListenNow {
    data class Playing(
        val snapshot: TtsPlaybackSnapshot,
        val story: Story?,
        val voice: String?,
    ) : ListenNow

    data class Continue(
        val place: ContinuePlace,
    ) : ListenNow

    data object Nothing : ListenNow
}

/** What background compression is doing, with the storage it is measured against. */
internal data class CompressionUi(
    val status: NarrationCompressionStatus,
    val totalBytes: Long,
)

internal data class ListenUiState(
    val content: ListenContent,
    val banner: GenerationBanner,
    /** The running chapter's progress, which its queue row shows. */
    val progress: NarrationProgress?,
    val now: ListenNow,
    val compression: CompressionUi,
)

/**
 * The Listen destination (`AppRoute.Narration`): the Now playing / Continue listening card, the
 * cross-story generation queue with its live banner, and the generated audio. It follows the
 * narration coordinator and playback while a renderer collects [state], and runs the queue's
 * changes off the main thread; failures come out of [messages].
 */
internal class ListenScreenModel(
    private val data: ListenData,
    private val controls: PlaybackControls,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ScreenModel(scope) {
    private var content: ListenContent? = null
    private var playingStoryId: String? = null
    private var playingStory: Story? = null
    private var idle: ListenNow = data.lastContinuePlace()?.let(ListenNow::Continue) ?: ListenNow.Nothing
    private var compression: CompressionUi? = null
    private var compressionRevision = -1L
    private val messageChannel = Channel<String>(MESSAGE_BUFFER, BufferOverflow.DROP_OLDEST)
    private val mutableState = MutableStateFlow(compute())

    val state: StateFlow<ListenUiState> = mutableState.asStateFlow()

    /** One-off messages for the user (failed changes), buffered until a renderer shows them. */
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    init {
        this.scope.launch {
            mutableState.subscriptionCount
                .map { it > 0 }
                .distinctUntilChanged()
                .collectLatest { observed -> if (observed) observe() }
        }
    }

    /** Recomputes the state from its sources now and returns it. */
    fun refresh(): ListenUiState = compute().also { mutableState.value = it }

    fun pauseGeneration() = change("Could not pause") { pause() }

    fun resumeGeneration() = change("Could not resume") { resume() }

    fun retryAll() = change("Could not retry") { retryAll() }

    fun retry(jobIds: Collection<String>) = change("Could not retry") { jobIds.forEach { retry(it) } }

    fun clearFailed() = change("Could not clear") { clearFailed() }

    fun clearQueue() = change("Could not clear the queue") { clearQueue() }

    fun remove(jobIds: Set<String>) = change("Could not remove") { remove(jobIds) }

    fun moveToTop(jobId: String) = change("Could not move the chapter") { moveToTop(jobId) }

    fun moveStoryToTop(storyId: String) = change("Could not reorder the queue") { moveStoryToTop(storyId) }

    /** Deletes the audio of chapters no longer in the library, as the screen lists it. */
    fun deleteDetachedAudio() {
        val detached = state.value.content.audio.detached
        change("Could not delete the audio") {
            detached.forEach { (storyId, packs) -> deleteAudio(storyId, packs.map { it.chapterId }) }
        }
    }

    /** A tap on a Generate on chip ([serverId] null is "This phone"), against the choice the screen shows. */
    fun chooseGenerateOn(serverId: String?) {
        val current = state.value.content.queue.generateOnChoice
        val next = NarrationServerPlanning.tapGenerateOn(current, serverId)
        if (next == current) return
        change("Could not save the setting") { setGenerateOn(next.serverIds, next.phoneFallback) }
    }

    fun playPause() = controls.playPause()

    /** Resumes the saved place the Continue listening card shows. */
    fun resume(place: ContinuePlace) = controls.resume(place.target.story.id, place.target.chapter.id)

    private suspend fun observe() =
        coroutineScope {
            launch {
                merge(data.queue, data.progress, data.generationRunning, data.packsRevision, data.compression, data.playback)
                    .collect { refresh() }
            }
            launch {
                // While nothing plays, look the place up again when playback stops and whenever the screen would rebuild.
                val idleNow = data.playback.map { it.snapshot == null }.distinctUntilChanged()
                combine(idleNow, buildKeys()) { isIdle, key -> isIdle to key }
                    .distinctUntilChanged()
                    .collectLatest { (isIdle, _) ->
                        if (!isIdle) return@collectLatest
                        idle = runCatching { data.continuePlace() }.getOrNull()?.let(ListenNow::Continue) ?: ListenNow.Nothing
                        refresh()
                    }
            }
        }

    private fun buildKeys(): Flow<ListenBuildKey> =
        combine(data.queue, data.packsRevision, data.progress.map { it?.job?.id }.distinctUntilChanged()) { queue, packs, job ->
            ListenBuildKey(queue.withoutLiveFields(), packs, job)
        }.distinctUntilChanged()

    private fun compute(): ListenUiState {
        val queue = data.queue.value
        val progress = data.progress.value
        val key = ListenBuildKey(queue.withoutLiveFields(), data.packsRevision.value, progress?.job?.id)
        val built = content?.takeIf { it.key == key } ?: buildContent(key, queue).also { content = it }
        return ListenUiState(
            content = built,
            banner = NarrationScreenPlanning.banner(progress, queue, data.generationRunning.value),
            progress = progress,
            now = now(),
            compression = compression(),
        )
    }

    private fun buildContent(
        key: ListenBuildKey,
        queue: NarrationQueueDocument,
    ): ListenContent {
        val cards =
            NarrationScreenPlanning.groups(queue.jobs).mapIndexed { index, group ->
                ListenQueueCard(
                    group = group,
                    story = data.story(group.storyId),
                    chapterTitles = group.jobs.associate { it.id to data.chapterTitle(it.storyId, it.chapterId) },
                    isFirst = index == 0,
                )
            }
        val audio = data.generatedAudio()
        return ListenContent(
            key = key,
            queue = queue,
            cards = cards,
            failed = queue.jobs.count { it.state == NarrationJobState.FAILED },
            audio = audio,
            audioStories = audio.attached.keys.associateWith(data::story),
            usedBytes = data.usedBytes(),
            freeBytes = data.freeBytes(),
            showGenerateOn = queue.servers.isNotEmpty() && data.voiceCanUseComputers(),
        )
    }

    private fun now(): ListenNow {
        val snapshot = data.playback.value.snapshot ?: return idle
        if (snapshot.storyId != playingStoryId) {
            playingStoryId = snapshot.storyId
            playingStory = data.story(snapshot.storyId)
        }
        return ListenNow.Playing(snapshot, playingStory, data.packVoice(snapshot.storyId, snapshot.chapterId))
    }

    /** The compression status, re-measured against storage only when it or the packs change. */
    private fun compression(): CompressionUi {
        val status = data.compression.value
        val revision = data.packsRevision.value
        val current = compression
        if (current != null && current.status == status && compressionRevision == revision) return current
        compressionRevision = revision
        return CompressionUi(status, data.usedBytes()).also { compression = it }
    }

    /** Runs a queue change off the main thread, to the end even if the screen goes away, and reports a failure. */
    private fun change(
        failure: String,
        block: suspend ListenData.() -> Unit,
    ) {
        // Undispatched, so a change asked for just before the screen goes away still starts.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val result = withContext(io + NonCancellable) { runCatching { data.block() } }
            result.exceptionOrNull()?.let {
                Timber.e(it, failure)
                messageChannel.trySend(failure)
            }
        }
    }

    private companion object {
        const val MESSAGE_BUFFER = 8
    }
}

internal fun NarrationQueueDocument.withoutLiveFields() = copy(keepAhead = 0, msPerChar = emptyMap(), speed = emptyMap())
