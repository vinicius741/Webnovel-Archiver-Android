package com.vinicius741.webnovelarchiver.ai

import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.data.repository.saveAiCoverImageDraft
import com.vinicius741.webnovelarchiver.data.repository.saveAiCoverPromptDraft
import com.vinicius741.webnovelarchiver.data.storage.AiCoverDraftRecord
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

enum class AiCoverJobKind {
    ONE_STEP,
    PROMPT,
    IMAGE,
}

/** A running cover job; [message] is user-facing. */
data class AiCoverJobState(
    val storyId: String,
    val kind: AiCoverJobKind,
    val message: String,
    /** One-step prompt persisted while the image stage still runs. */
    val persistedPrompt: String? = null,
)

/** Terminal outcome, emitted once the result is already persisted. */
sealed interface AiCoverJobEvent {
    val storyId: String
    val kind: AiCoverJobKind

    data class Succeeded(
        override val storyId: String,
        override val kind: AiCoverJobKind,
        val record: AiCoverDraftRecord,
    ) : AiCoverJobEvent

    data class Failed(
        override val storyId: String,
        override val kind: AiCoverJobKind,
        val message: String,
        /** Prompt already persisted before a later one-step image failure. */
        val persistedPrompt: String? = null,
    ) : AiCoverJobEvent
}

/**
 * Runs billable AI cover generation on the process-wide application scope so jobs survive
 * navigation and app exit. [jobs] holds the running state (one job at a time, keyed by story id);
 * [events] carries terminal outcomes to whichever listeners are attached (UI bridge, foreground
 * service) — both may be absent. The draft is persisted before the success event fires, so
 * listeners can rehydrate from disk.
 */
class AiCoverJobCoordinator(
    private val scope: CoroutineScope,
    private val repository: AppRepository,
    private val engine: AiCoverArtEngine,
) {
    private val _jobs = MutableStateFlow<Map<String, AiCoverJobState>>(emptyMap())

    /** Running cover jobs by story id; empty = idle. */
    val jobs: StateFlow<Map<String, AiCoverJobState>> = _jobs.asStateFlow()

    private val _events =
        MutableSharedFlow<AiCoverJobEvent>(
            extraBufferCapacity = 32,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /** Terminal outcomes; buffered (not conflated) so a fast failure cannot vanish between emissions. */
    val events: SharedFlow<AiCoverJobEvent> = _events.asSharedFlow()

    fun jobFor(storyId: String): AiCoverJobState? = _jobs.value[storyId]

    /** True while any cover job is running. */
    fun isBusy(): Boolean = _jobs.value.isNotEmpty()

    /** One-shot flow: image prompt + image in a single uninterrupted run. */
    fun startOneShot(storyId: String): Boolean =
        start(storyId, AiCoverJobKind.ONE_STEP, "Generating cover...") {
            AiCoverDraftRecord.Image(
                engine.draft(storyId, progressReporter(storyId)) { prompt ->
                    if (repository.story(storyId) != null) {
                        repository.saveAiCoverPromptDraft(storyId, prompt)
                        _jobs.update { current ->
                            current[storyId]?.let {
                                current + (storyId to it.copy(persistedPrompt = prompt))
                            } ?: current
                        }
                    }
                },
            )
        }

    /** Staged flow, stage 1: writes the editable image prompt only. */
    fun startPromptDraft(storyId: String): Boolean =
        start(storyId, AiCoverJobKind.PROMPT, "Writing image prompt...") {
            AiCoverDraftRecord.PromptOnly(engine.draftPrompt(storyId, progressReporter(storyId)))
        }

    /** Staged flow, stage 2: paints the (possibly edited) prompt. */
    fun startImageDraft(
        storyId: String,
        prompt: String,
    ): Boolean =
        start(storyId, AiCoverJobKind.IMAGE, "Painting cover...") {
            AiCoverDraftRecord.Image(engine.draftImage(storyId, prompt, progressReporter(storyId)))
        }

    // A billable-call failure must land in the Failed event, never crash the process scope.
    @Suppress("TooGenericExceptionCaught")
    private fun start(
        storyId: String,
        kind: AiCoverJobKind,
        initialMessage: String,
        run: suspend () -> AiCoverDraftRecord,
    ): Boolean {
        var accepted = false
        _jobs.update { current ->
            if (current.isEmpty()) {
                accepted = true
                current + (storyId to AiCoverJobState(storyId, kind, initialMessage))
            } else {
                current
            }
        }
        if (!accepted) return false
        scope.launch {
            try {
                val record = run()
                if (repository.story(storyId) == null) {
                    // Story deleted mid-run; deleteStory already cleaned its drafts — persisting
                    // would orphan files.
                    Timber.i("AI cover job finished for deleted story %s; discarding result", storyId)
                    _jobs.update { it - storyId }
                    return@launch
                }
                // Persist before clearing the slot and emitting: listeners may hydrate from disk.
                when (record) {
                    is AiCoverDraftRecord.PromptOnly -> repository.saveAiCoverPromptDraft(storyId, record.prompt)
                    is AiCoverDraftRecord.Image -> repository.saveAiCoverImageDraft(storyId, record.draft)
                }
                Timber.i(
                    "AI cover job succeeded for %s (kind=%s, image=%s)",
                    storyId,
                    kind,
                    record is AiCoverDraftRecord.Image,
                )
                _jobs.update { it - storyId }
                _events.tryEmit(AiCoverJobEvent.Succeeded(storyId, kind, record))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // The engine throws user-presentable messages; cancellation is rethrown untouched.
                Timber.w(error, "AI cover job failed for %s (kind=%s)", storyId, kind)
                val persistedPrompt = _jobs.value[storyId]?.persistedPrompt
                _jobs.update { it - storyId }
                _events.tryEmit(
                    AiCoverJobEvent.Failed(
                        storyId,
                        kind,
                        error.message ?: "AI cover failed",
                        persistedPrompt,
                    ),
                )
            }
        }
        return true
    }

    private fun progressReporter(storyId: String): (String) -> Unit =
        { message ->
            _jobs.update { current ->
                current[storyId]?.let { current + (storyId to it.copy(message = message)) } ?: current
            }
        }
}
