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
 * Runs billable chapter rewrites on the process-wide application scope (the cover-job lifecycle,
 * keyed by story+chapter): jobs survive navigation and app exit, and the validated draft is
 * persisted before anyone is told it is ready. One rewrite at a time; [enqueue] queues further
 * chapters (batch polish) and they drain sequentially.
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

    /** The running job's coroutine handle; [cancelActive] cancels it so cancellation releases the slot (R15). */
    private var activeHandle: kotlinx.coroutines.Job? = null

    fun jobFor(
        storyId: String,
        chapterId: String,
    ): AiChapterRewriteJobState? = _jobs.value[key(storyId, chapterId)]

    fun isBusy(): Boolean = _jobs.value.isNotEmpty()

    fun queuedFor(storyId: String): List<AiChapterRewriteJobState> = _queue.value.filter { it.storyId == storyId }

    /**
     * Drops queued chapters and cancels the running rewrite (R15): a foreground-service timeout
     * must not leave the batch draining unprotected in the background. Cancellation releases the
     * busy slot through [runJob]'s cancellation path; queued work is discarded, never replayed —
     * an AI request with an unknown billing outcome is not re-sent blind.
     */
    fun cancelAll(reason: String) {
        synchronized(queueLock) {
            val dropped = _queue.value.size
            _queue.value = emptyList()
            activeHandle?.cancel()
            if (dropped > 0 || activeHandle != null) {
                Timber.w("Chapter rewrite work cancelled (%s): %d queued dropped", reason, dropped)
            }
        }
    }

    /** Queues a chapter; starts immediately when idle. False when already running or queued. */
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
        // Register before popping so the chapter stays visible in at least one map throughout —
        // the service collector must never read an idle coordinator mid-handoff.
        _jobs.update { it + (key(next.storyId, next.chapterId) to next.copy(message = "Preparing rewrite...")) }
        _queue.update { it - next }
        activeHandle = scope.launch { runJob(next) }
    }

    private suspend fun runJob(state: AiChapterRewriteJobState) {
        val jobKey = key(state.storyId, state.chapterId)
        var slotReleased = false
        try {
            val output = engine.draft(state.storyId, state.chapterId, progressReporter(jobKey))
            // The save's own transaction re-checks story existence (R05): a story deleted mid-run
            // cannot regain rewrite state.
            if (!repository.saveChapterRewriteDraft(output)) {
                Timber.i("Chapter rewrite finished for deleted story %s; discarding result", state.storyId)
                finishJob(jobKey)
                slotReleased = true
                return
            }
            finishJob(jobKey)
            slotReleased = true
            _events.tryEmit(
                AiChapterRewriteJobEvent.Succeeded(state.storyId, state.chapterId, output.chapterTitle, output.status),
            )
        } catch (cancellation: CancellationException) {
            // R15: cancellation must release the registered slot and unblock the queue instead of
            // leaving a permanently busy coordinator. Distinguish it from failure: no error event.
            if (!slotReleased) {
                finishJob(jobKey)
                slotReleased = true
            }
            throw cancellation
        } catch (
            @Suppress("TooGenericExceptionCaught") error: Throwable,
        ) {
            // The engine throws user-presentable messages; any failure must land in the event.
            Timber.w(error, "Chapter rewrite job failed for %s ch %s", state.storyId, state.chapterId)
            if (!slotReleased) {
                finishJob(jobKey)
                slotReleased = true
            }
            _events.tryEmit(
                AiChapterRewriteJobEvent.Failed(state.storyId, state.chapterId, error.message ?: "Chapter polish failed"),
            )
        }
    }

    /**
     * Swaps the finished entry for the next queued chapter in a single [_jobs] update; clearing
     * first would expose an idle coordinator with work queued, crashing the service collector or
     * stopping the keep-alive service mid-batch.
     */
    private fun finishJob(jobKey: String) {
        synchronized(queueLock) {
            if (activeHandle != null && activeHandle?.isCancelled == true) activeHandle = null
            val next = _queue.value.firstOrNull()
            if (next == null) {
                _jobs.update { it - jobKey }
                return
            }
            _queue.update { it - next }
            _jobs.update {
                (it - jobKey) + (key(next.storyId, next.chapterId) to next.copy(message = "Preparing rewrite..."))
            }
            activeHandle = scope.launch { runJob(next) }
        }
    }

    private fun progressReporter(jobKey: String): (String) -> Unit =
        { message ->
            _jobs.update { current ->
                current[jobKey]?.let { current + (jobKey to it.copy(message = message)) } ?: current
            }
        }
}

private fun key(
    storyId: String,
    chapterId: String,
): String = "$storyId::$chapterId"
