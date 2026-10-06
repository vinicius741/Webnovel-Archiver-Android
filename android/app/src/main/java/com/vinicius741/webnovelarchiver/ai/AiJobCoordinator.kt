package com.vinicius741.webnovelarchiver.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

/** The shared shape of a running AI job: which story it belongs to and what its progress says. */
interface AiJobModel {
    val storyId: String
    val message: String
}

/** Terminal outcome of an AI job, emitted only after the result is already persisted. */
interface AiJobEvent {
    val storyId: String
}

/**
 * Runs billable AI jobs on the process-wide application scope so they survive navigation and app
 * exit. One job runs at a time; with [queueEnabled] further submissions queue and drain
 * sequentially, otherwise a busy coordinator rejects new work. [submit]'s [runRequest] performs the
 * billable call, persists the result, and returns the terminal event — or null when the story was
 * deleted mid-run, in which case the result is discarded silently (the deletion already cleaned
 * the story's files; persisting would orphan them). Cancellation releases the busy slot; a
 * failure lands in [failureEvent] instead of crashing the process scope.
 *
 * [jobs] holds the running state and [events] carries terminal outcomes to whichever listeners are
 * attached (UI bridge, foreground service) — both may be absent.
 */
class AiJobCoordinator<S : AiJobModel, E : AiJobEvent>(
    private val scope: CoroutineScope,
    private val keyOf: (S) -> String,
    private val withMessage: (S, String) -> S,
    private val failureEvent: (S, Throwable) -> E,
    private val queueEnabled: Boolean = false,
    /** Message a queued entry shows once it starts running; null keeps its queued message. */
    private val queuedStartMessage: String? = null,
) {
    private class Pending<S, E>(
        val state: S,
        val runRequest: suspend (update: ((S) -> S) -> Unit) -> E?,
    )

    private val _jobs = MutableStateFlow<Map<String, S>>(emptyMap())

    /** The running job by its key; empty = idle. */
    val jobs: StateFlow<Map<String, S>> = _jobs.asStateFlow()

    private val _queue = MutableStateFlow<List<S>>(emptyList())

    /** Accepted-but-not-started jobs in submit order; always empty without [queueEnabled]. */
    val queue: StateFlow<List<S>> = _queue.asStateFlow()

    private val pending = mutableListOf<Pending<S, E>>()

    private val _events =
        MutableSharedFlow<E>(
            extraBufferCapacity = 32,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /** Terminal outcomes; buffered (not conflated) so a fast failure cannot vanish between emissions. */
    val events: SharedFlow<E> = _events.asSharedFlow()

    /** Guards slot handoffs (submit, batch drain) so observers always see a consistent jobs+queue view. */
    private val queueLock = Any()

    /** The running job's coroutine handle; cancellation releases the slot through [runJob]. */
    private var activeHandle: Job? = null

    fun jobFor(key: String): S? = _jobs.value[key]

    /** True while a job is running. */
    fun isBusy(): Boolean = _jobs.value.isNotEmpty()

    fun queuedFor(storyId: String): List<S> = _queue.value.filter { it.storyId == storyId }

    /** Queues or starts a job; false when the coordinator cannot accept it now. */
    fun submit(
        state: S,
        runRequest: suspend (update: ((S) -> S) -> Unit) -> E?,
    ): Boolean {
        val jobKey = keyOf(state)
        if (!queueEnabled) {
            var accepted = false
            _jobs.update { current ->
                if (current.isEmpty()) {
                    accepted = true
                    current + (jobKey to state)
                } else {
                    current
                }
            }
            if (!accepted) return false
            activeHandle = scope.launch { runJob(jobKey, state, runRequest) }
            return true
        }
        synchronized(queueLock) {
            if (_jobs.value.containsKey(jobKey) || pending.any { keyOf(it.state) == jobKey }) {
                return false
            }
            pending.add(Pending(state, runRequest))
            _queue.update { it + state }
            launchNextLocked()
        }
        return true
    }

    /**
     * Drops queued jobs and cancels the running one: a foreground-service timeout must not leave a
     * batch draining unprotected in the background. Queued work is discarded, never replayed — an
     * AI request with an unknown billing outcome is not re-sent blind.
     */
    fun cancelAll(reason: String) {
        if (!queueEnabled) {
            activeHandle?.cancel()
            return
        }
        synchronized(queueLock) {
            val dropped = pending.size
            pending.clear()
            _queue.value = emptyList()
            activeHandle?.cancel()
            if (dropped > 0 || activeHandle != null) {
                Timber.w("AI job work cancelled (%s): %d queued dropped", reason, dropped)
            }
        }
    }

    /** Drops a story's not-yet-started jobs from the queue; the running job finishes. */
    fun cancelQueuedForStory(storyId: String): Int =
        synchronized(queueLock) {
            var removed = 0
            val kept = pending.filter { it.state.storyId != storyId }
            removed = pending.size - kept.size
            if (removed > 0) {
                pending.clear()
                pending.addAll(kept)
                _queue.value = kept.map { it.state }
            }
            removed
        }

    private fun launchNextLocked() {
        if (_jobs.value.isNotEmpty()) return
        val next = pending.firstOrNull() ?: return
        // Register before popping so the job stays visible in at least one collection throughout —
        // the service collector must never read an idle coordinator mid-handoff.
        _jobs.update { it + (keyOf(next.state) to startShape(next.state)) }
        pending.removeAt(0)
        _queue.update { it - next.state }
        activeHandle = scope.launch { runJob(keyOf(next.state), next.state, next.runRequest) }
    }

    private fun startShape(state: S): S = queuedStartMessage?.let { withMessage(state, it) } ?: state

    private suspend fun runJob(
        jobKey: String,
        state: S,
        runRequest: suspend (update: ((S) -> S) -> Unit) -> E?,
    ) {
        var slotReleased = false
        val update: ((S) -> S) -> Unit = { transform ->
            _jobs.update { current ->
                current[jobKey]?.let { current + (jobKey to transform(it)) } ?: current
            }
        }
        try {
            val event = runRequest(update)
            if (event == null) {
                Timber.i("AI job for %s finished for a deleted story; discarding result", state.storyId)
            }
            finishJob(jobKey)
            slotReleased = true
            event?.let { _events.tryEmit(it) }
        } catch (cancellation: CancellationException) {
            // Cancellation releases the registered slot instead of leaving a permanently busy
            // coordinator; it is distinguished from failure: no error event.
            if (!slotReleased) {
                finishJob(jobKey)
                slotReleased = true
            }
            throw cancellation
        } catch (
            @Suppress("TooGenericExceptionCaught") error: Throwable,
        ) {
            Timber.w(error, "AI job failed for %s", state.storyId)
            // The latest state carries mid-run attachments (e.g. an already-persisted prompt).
            val latest = _jobs.value[jobKey] ?: state
            if (!slotReleased) {
                finishJob(jobKey)
                slotReleased = true
            }
            _events.tryEmit(failureEvent(latest, error))
        }
    }

    /**
     * Swaps the finished entry for the next queued job in a single [_jobs] update; clearing first
     * would expose an idle coordinator with work queued, crashing the service collector or
     * stopping the keep-alive service mid-batch.
     */
    private fun finishJob(jobKey: String) {
        synchronized(queueLock) {
            if (activeHandle != null && activeHandle?.isCancelled == true) activeHandle = null
            val next = pending.firstOrNull()
            if (next == null) {
                _jobs.update { it - jobKey }
                return
            }
            pending.removeAt(0)
            _queue.update { it - next.state }
            _jobs.update {
                (it - jobKey) + (keyOf(next.state) to startShape(next.state))
            }
            activeHandle = scope.launch { runJob(keyOf(next.state), next.state, next.runRequest) }
        }
    }
}
