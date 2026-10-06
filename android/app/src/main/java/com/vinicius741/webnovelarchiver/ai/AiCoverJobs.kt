package com.vinicius741.webnovelarchiver.ai

import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.data.repository.persistAiCoverDraftIfStoryExists
import com.vinicius741.webnovelarchiver.data.repository.saveAiCoverPromptDraft
import com.vinicius741.webnovelarchiver.data.storage.AiCoverDraftRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class AiCoverJobKind {
    ONE_STEP,
    PROMPT,
    IMAGE,
}

/** A running cover job; [message] is user-facing. */
data class AiCoverJobState(
    override val storyId: String,
    val kind: AiCoverJobKind,
    override val message: String,
    /** One-step prompt persisted while the image stage still runs. */
    val persistedPrompt: String? = null,
) : AiJobModel

/** Terminal outcome, emitted once the result is already persisted. */
sealed interface AiCoverJobEvent : AiJobEvent {
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
 * Cover generation on the shared [AiJobCoordinator] lifecycle: one billable job at a time, no
 * queue; drafts persist before the success event fires, so listeners can rehydrate from disk.
 */
class AiCoverJobCoordinator(
    scope: CoroutineScope,
    private val repository: AppRepository,
    private val engine: AiCoverArtEngine,
) {
    private val coordinator =
        AiJobCoordinator<AiCoverJobState, AiCoverJobEvent>(
            scope = scope,
            keyOf = { it.storyId },
            withMessage = { state, message -> state.copy(message = message) },
            failureEvent = { state, error ->
                AiCoverJobEvent.Failed(
                    state.storyId,
                    state.kind,
                    error.message ?: "AI cover failed",
                    state.persistedPrompt,
                )
            },
        )

    /** Running cover jobs by story id; empty = idle. */
    val jobs: StateFlow<Map<String, AiCoverJobState>> get() = coordinator.jobs

    /** Terminal outcomes; buffered (not conflated) so a fast failure cannot vanish between emissions. */
    val events: SharedFlow<AiCoverJobEvent> get() = coordinator.events

    fun jobFor(storyId: String): AiCoverJobState? = coordinator.jobFor(storyId)

    /** True while any cover job is running. */
    fun isBusy(): Boolean = coordinator.isBusy()

    /** One-shot flow: image prompt + image in a single uninterrupted run. */
    fun startOneShot(storyId: String): Boolean =
        coordinator.submit(AiCoverJobState(storyId, AiCoverJobKind.ONE_STEP, "Generating cover...")) { update ->
            val record =
                AiCoverDraftRecord.Image(
                    engine.draft(storyId, { message -> update { it.copy(message = message) } }) { prompt ->
                        if (repository.story(storyId) != null) {
                            repository.saveAiCoverPromptDraft(storyId, prompt)
                            update { it.copy(persistedPrompt = prompt) }
                        }
                    },
                )
            persistedEvent(storyId, AiCoverJobKind.ONE_STEP, record)
        }

    /** Staged flow, stage 1: writes the editable image prompt only. */
    fun startPromptDraft(storyId: String): Boolean =
        coordinator.submit(AiCoverJobState(storyId, AiCoverJobKind.PROMPT, "Writing image prompt...")) { update ->
            val record = AiCoverDraftRecord.PromptOnly(engine.draftPrompt(storyId) { message -> update { it.copy(message = message) } })
            persistedEvent(storyId, AiCoverJobKind.PROMPT, record)
        }

    /** Staged flow, stage 2: paints the (possibly edited) prompt. */
    fun startImageDraft(
        storyId: String,
        prompt: String,
    ): Boolean =
        coordinator.submit(AiCoverJobState(storyId, AiCoverJobKind.IMAGE, "Painting cover...")) { update ->
            if (!repository.persistAiCoverDraftIfStoryExists(storyId, AiCoverDraftRecord.PromptOnly(prompt))) {
                error("This novel is no longer in the library")
            }
            val record = AiCoverDraftRecord.Image(engine.draftImage(storyId, prompt) { message -> update { it.copy(message = message) } })
            persistedEvent(storyId, AiCoverJobKind.IMAGE, record)
        }

    /** Null when the story was deleted mid-run and the persisted result had to be discarded. */
    private suspend fun persistedEvent(
        storyId: String,
        kind: AiCoverJobKind,
        record: AiCoverDraftRecord,
    ): AiCoverJobEvent? =
        if (!repository.persistAiCoverDraftIfStoryExists(storyId, record)) {
            null
        } else {
            AiCoverJobEvent.Succeeded(storyId, kind, record)
        }
}
