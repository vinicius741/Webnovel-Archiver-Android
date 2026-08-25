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
 * job at a time — the feature is single-chapter by design.
 */
class AiChapterRewriteJobCoordinator(
    private val scope: CoroutineScope,
    private val repository: AppRepository,
    private val engine: AiChapterRewriteEngine,
) {
    private val _jobs = MutableStateFlow<Map<String, AiChapterRewriteJobState>>(emptyMap())

    /** The running rewrite job by "<storyId>::<chapterId>"; empty = idle. */
    val jobs: StateFlow<Map<String, AiChapterRewriteJobState>> = _jobs.asStateFlow()

    private val _events =
        MutableSharedFlow<AiChapterRewriteJobEvent>(
            extraBufferCapacity = 32,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    val events: SharedFlow<AiChapterRewriteJobEvent> = _events.asSharedFlow()

    fun jobFor(
        storyId: String,
        chapterId: String,
    ): AiChapterRewriteJobState? = _jobs.value[key(storyId, chapterId)]

    fun isBusy(): Boolean = _jobs.value.isNotEmpty()

    fun start(
        storyId: String,
        chapterId: String,
        chapterTitle: String,
    ): Boolean {
        val jobKey = key(storyId, chapterId)
        var accepted = false
        _jobs.update { current ->
            if (current.isEmpty()) {
                accepted = true
                current + (jobKey to AiChapterRewriteJobState(storyId, chapterId, chapterTitle, "Preparing rewrite..."))
            } else {
                current
            }
        }
        if (!accepted) return false
        scope.launch {
            try {
                val output = engine.draft(storyId, chapterId, progressReporter(jobKey))
                if (repository.story(storyId) == null) {
                    // The story was deleted mid-run; deleteStory already cleaned its rewrites.
                    Timber.i("Chapter rewrite finished for deleted story %s; discarding result", storyId)
                    _jobs.update { it - jobKey }
                    return@launch
                }
                repository.saveChapterRewriteDraft(output)
                _jobs.update { it - jobKey }
                _events.tryEmit(AiChapterRewriteJobEvent.Succeeded(storyId, chapterId, output.chapterTitle, output.status))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (
                @Suppress("TooGenericExceptionCaught") error: Throwable,
            ) {
                // The engine throws user-presentable messages; any failure must land in the event.
                Timber.w(error, "Chapter rewrite job failed for %s ch %s", storyId, chapterId)
                _jobs.update { it - jobKey }
                _events.tryEmit(
                    AiChapterRewriteJobEvent.Failed(storyId, chapterId, error.message ?: "Chapter polish failed"),
                )
            }
        }
        return true
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
