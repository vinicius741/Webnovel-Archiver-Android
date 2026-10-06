package com.vinicius741.webnovelarchiver.ai

import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.data.repository.saveChapterRewriteDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** A chapter rewrite currently running on the process scope; [message] is user-facing. */
data class AiChapterRewriteJobState(
    override val storyId: String,
    val chapterId: String,
    val chapterTitle: String,
    override val message: String,
) : AiJobModel

/** Terminal outcome of a chapter rewrite, emitted once the draft is already persisted. */
sealed interface AiChapterRewriteJobEvent : AiJobEvent {
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
 * Chapter rewrites on the shared [AiJobCoordinator] lifecycle: one billable job plus a FIFO batch
 * queue that drains sequentially; the validated draft persists before anyone is told it is ready.
 */
class AiChapterRewriteJobCoordinator(
    scope: CoroutineScope,
    private val repository: AppRepository,
    private val engine: AiChapterRewriteEngine,
) {
    private val coordinator =
        AiJobCoordinator<AiChapterRewriteJobState, AiChapterRewriteJobEvent>(
            scope = scope,
            keyOf = { "${it.storyId}::${it.chapterId}" },
            withMessage = { state, message -> state.copy(message = message) },
            failureEvent = { state, error ->
                AiChapterRewriteJobEvent.Failed(state.storyId, state.chapterId, error.message ?: "Chapter polish failed")
            },
            queueEnabled = true,
            queuedStartMessage = "Preparing rewrite...",
        )

    /** The running rewrite job by "<storyId>::<chapterId>"; empty = idle. */
    val jobs: StateFlow<Map<String, AiChapterRewriteJobState>> get() = coordinator.jobs

    /** Chapters accepted but not yet started, in start order. */
    val queue: StateFlow<List<AiChapterRewriteJobState>> get() = coordinator.queue

    val events: SharedFlow<AiChapterRewriteJobEvent> get() = coordinator.events

    fun jobFor(
        storyId: String,
        chapterId: String,
    ): AiChapterRewriteJobState? = coordinator.jobFor("$storyId::$chapterId")

    fun isBusy(): Boolean = coordinator.isBusy()

    fun queuedFor(storyId: String): List<AiChapterRewriteJobState> = coordinator.queuedFor(storyId)

    /**
     * Drops queued chapters and cancels the running rewrite: a foreground-service timeout must
     * not leave the batch draining unprotected in the background. Queued work is discarded, never
     * replayed — an AI request with an unknown billing outcome is not re-sent blind.
     */
    fun cancelAll(reason: String) = coordinator.cancelAll(reason)

    /** Queues a chapter; starts immediately when idle. False when already running or queued. */
    fun enqueue(
        storyId: String,
        chapterId: String,
        chapterTitle: String,
    ): Boolean =
        coordinator.submit(AiChapterRewriteJobState(storyId, chapterId, chapterTitle, "Queued for polish...")) { update ->
            val output = engine.draft(storyId, chapterId) { message -> update { it.copy(message = message) } }
            // The save's own transaction re-checks story existence: a story deleted mid-run
            // cannot regain rewrite state.
            if (!repository.saveChapterRewriteDraft(output)) {
                null
            } else {
                AiChapterRewriteJobEvent.Succeeded(storyId, chapterId, output.chapterTitle, output.status)
            }
        }

    /** Drops a story's not-yet-started chapters from the queue; the running chapter finishes. */
    fun cancelQueued(storyId: String): Int = coordinator.cancelQueuedForStory(storyId)
}
