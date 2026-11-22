package com.vinicius741.webnovelarchiver.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** A running AI description draft; [message] is user-facing. */
data class AiDescriptionJobState(
    override val storyId: String,
    override val message: String,
) : AiJobModel

/** Terminal outcome of a description draft. The draft is not persisted: it waits in memory until Apply. */
sealed interface AiDescriptionJobEvent : AiJobEvent {
    data class Succeeded(
        override val storyId: String,
        val draft: String,
    ) : AiDescriptionJobEvent

    data class Failed(
        override val storyId: String,
        val message: String,
    ) : AiDescriptionJobEvent
}

/**
 * Description drafts on the shared [AiJobCoordinator] lifecycle, like cover jobs: they run on the
 * application scope, so leaving the story never cancels the billed call. One runs at a time.
 */
class AiDescriptionJobCoordinator(
    scope: CoroutineScope,
    private val draft: suspend (storyId: String, onProgress: (String) -> Unit) -> String,
) {
    private val coordinator =
        AiJobCoordinator<AiDescriptionJobState, AiDescriptionJobEvent>(
            scope = scope,
            keyOf = { it.storyId },
            withMessage = { state, message -> state.copy(message = message) },
            failureEvent = { state, error -> AiDescriptionJobEvent.Failed(state.storyId, error.message ?: "AI description failed") },
        )

    /** The running draft by story id; empty = idle. */
    val jobs: StateFlow<Map<String, AiDescriptionJobState>> get() = coordinator.jobs

    val events: SharedFlow<AiDescriptionJobEvent> get() = coordinator.events

    /** Starts a draft; false while another one runs. */
    fun start(storyId: String): Boolean =
        coordinator.submit(AiDescriptionJobState(storyId, STARTING_MESSAGE)) { update ->
            val text = draft(storyId) { message -> update { it.copy(message = message) } }
            AiDescriptionJobEvent.Succeeded(storyId, text)
        }

    companion object {
        const val STARTING_MESSAGE = "Generating description..."
    }
}
