package com.vinicius741.webnovelarchiver.app

import com.vinicius741.webnovelarchiver.ai.AiChapterRewriteEngine
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.data.repository.saveChapterRewriteDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/** A chapter rewrite currently running on the process scope; [message] is user-facing. */
data class AiChapterRewriteJobState(
    val storyId: String,
    val chapterId: String,
    val chapterTitle: String,
    val message: String,
)

/** Terminal outcome of a chapter rewrite, emitted once the draft is already persisted. */
sealed interface AiChapterRewriteJobEvent {
    val storyId: String
    val chapterId: String

    /** [status] mirrors the draft record: "ready" | "blocked" | "verify_failed". */
    data class Succeeded(
        override val storyId: String,
        override val chapterId: String,
        val chapterTitle: String,
        val status: String,
    ) : AiChapterRewriteJobEvent

    data class Failed(
        override val storyId: String,
        override val chapterId: String,
        val message: String,
    ) : AiChapterRewriteJobEvent
}

/**
 * Runs the billable chapter-rewrite flow on the process-wide application scope (the cover-job
 * lifecycle, keyed by story+chapter instead of story): jobs keep running through navigation and
 * app exit, and the validated draft is persisted before anyone is told it is ready. One rewrite
 * runs at a time; [enqueue] lines up further chapters (batch polish) and they drain sequentially.
 */
class AiChapterRewriteJobCoordinator(
    private val scope: CoroutineScope,
    private val repository: AppRepository,
    private val engine: AiChapterRewriteEngine,
) {
    private val _jobs = MutableStateFlow<Map<String, AiChapterRewriteJobState>>(emptyMap())

    /** The running rewrite job by "<storyId>::<chapterId>"; empty = idle. */
    val jobs: StateFlow<Map<String, AiChapterRewriteJobState>> = _jobs.asStateFlow()

    private val _queue = MutableStateFlow<List<AiChapterRewriteJobState>>(emptyList())

    /** Chapters accepted but not yet started, in start order. */
    val queue: StateFlow<List<AiChapterRewriteJobState>> = _queue.asStateFlow()

    private val _events =
        MutableSharedFlow<AiChapterRewriteJobEvent>(
            extraBufferCapacity = 32,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    val events: SharedFlow<AiChapterRewriteJobEvent> = _events.asSharedFlow()

    /** Guards slot handoffs (enqueue start, batch drain) so observers always see a consistent jobs+queue view. */
    private val queueLock = Any()

    fun jobFor(
        storyId: String,
        chapterId: String,
    ): AiChapterRewriteJobState? = _jobs.value[key(storyId, chapterId)]

    fun isBusy(): Boolean = _jobs.value.isNotEmpty()

    fun queuedFor(storyId: String): List<AiChapterRewriteJobState> = _queue.value.filter { it.storyId == storyId }

    /**
     * Queues a chapter for polishing; it starts immediately when the coordinator is idle, otherwise
     * it waits its turn. Returns false when the chapter is already running or queued.
     */
    fun enqueue(
        storyId: String,
        chapterId: String,
        chapterTitle: String,
    ): Boolean {
        synchronized(queueLock) {
            val jobKey = key(storyId, chapterId)
            if (_jobs.value.containsKey(jobKey) || _queue.value.any { key(it.storyId, it.chapterId) == jobKey }) {
                return false
            }
            _queue.update { it + AiChapterRewriteJobState(storyId, chapterId, chapterTitle, "Queued for polish...") }
            launchNextLocked()
        }
        return true
    }

    /** Drops a story's not-yet-started chapters from the queue; the running chapter finishes. */
    fun cancelQueued(storyId: String): Int =
        synchronized(queueLock) {
            var removed = 0
            _queue.update { current ->
                val kept = current.filter { it.storyId != storyId }
                removed = current.size - kept.size
                kept
            }
            removed
        }

    private fun launchNextLocked() {
        if (_jobs.value.isNotEmpty()) return
        val next = _queue.value.firstOrNull() ?: return
        // Register before popping the queue: between the two updates the chapter must stay visible
        // in at least one map, or the service collector reads an idle coordinator mid-handoff.
        _jobs.update { it + (key(next.storyId, next.chapterId) to next.copy(message = "Preparing rewrite...")) }
        _queue.update { it - next }
        scope.launch { runJob(next) }
    }

    private suspend fun runJob(state: AiChapterRewriteJobState) {
        val jobKey = key(state.storyId, state.chapterId)
        try {
            val output = engine.draft(state.storyId, state.chapterId, progressReporter(jobKey))
            if (repository.story(state.storyId) == null) {
                // The story was deleted mid-run; deleteStory already cleaned its rewrites.
                Timber.i("Chapter rewrite finished for deleted story %s; discarding result", state.storyId)
                finishJob(jobKey)
                return
            }
            repository.saveChapterRewriteDraft(output)
            finishJob(jobKey)
            _events.tryEmit(
                AiChapterRewriteJobEvent.Succeeded(state.storyId, state.chapterId, output.chapterTitle, output.status),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (
            @Suppress("TooGenericExceptionCaught") error: Throwable,
        ) {
            // The engine throws user-presentable messages; any failure must land in the event.
            Timber.w(error, "Chapter rewrite job failed for %s ch %s", state.storyId, state.chapterId)
            finishJob(jobKey)
            _events.tryEmit(
                AiChapterRewriteJobEvent.Failed(state.storyId, state.chapterId, error.message ?: "Chapter polish failed"),
            )
        }
    }

    /**
     * Clears the finished job and starts the next queued chapter, if any. The handoff swaps the
     * finished entry for the next one in a single [_jobs] update: clearing first would expose an
     * idle coordinator while the queue still has work, crashing the service collector or stopping
     * the keep-alive service mid-batch.
     */
    private fun finishJob(jobKey: String) {
        synchronized(queueLock) {
            val next = _queue.value.firstOrNull()
            if (next == null) {
                _jobs.update { it - jobKey }
                return
            }
            _queue.update { it - next }
            _jobs.update {
                (it - jobKey) + (key(next.storyId, next.chapterId) to next.copy(message = "Preparing rewrite..."))
            }
            scope.launch { runJob(next) }
        }
    }

    private fun progressReporter(jobKey: String): (String) -> Unit =
        { message ->
            _jobs.update { current ->
                current[jobKey]?.let { current + (jobKey to it.copy(message = message)) } ?: current
            }
        }

    private fun key(
        storyId: String,
        chapterId: String,
    ): String = "$storyId::$chapterId"
}
