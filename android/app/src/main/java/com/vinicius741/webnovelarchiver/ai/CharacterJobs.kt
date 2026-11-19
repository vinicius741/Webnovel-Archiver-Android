package com.vinicius741.webnovelarchiver.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class CharacterJobKind {
    FIND,
    PORTRAIT,
}

/** A running Characters-tab job; [characterId] is set for portraits. */
data class CharacterJobState(
    override val storyId: String,
    val kind: CharacterJobKind,
    val characterId: String?,
    override val message: String,
) : AiJobModel

sealed interface CharacterJobEvent : AiJobEvent {
    val kind: CharacterJobKind
    val characterId: String?

    data class Succeeded(
        override val storyId: String,
        override val kind: CharacterJobKind,
        override val characterId: String?,
        val characterName: String?,
    ) : CharacterJobEvent

    data class Failed(
        override val storyId: String,
        override val kind: CharacterJobKind,
        override val characterId: String?,
        val message: String,
    ) : CharacterJobEvent
}

/**
 * Characters-tab jobs on the shared [AiJobCoordinator] lifecycle: they run on the application scope,
 * so leaving the screen never cancels a billed call, and results are saved before events fire.
 * One character job runs at a time; cover jobs run on their own coordinator.
 */
class CharacterJobCoordinator(
    scope: CoroutineScope,
    private val engine: CharacterEngine,
) {
    private val coordinator =
        AiJobCoordinator<CharacterJobState, CharacterJobEvent>(
            scope = scope,
            keyOf = { it.storyId },
            withMessage = { state, message -> state.copy(message = message) },
            failureEvent = { state, error ->
                CharacterJobEvent.Failed(state.storyId, state.kind, state.characterId, error.message ?: "Character job failed")
            },
        )

    val jobs: StateFlow<Map<String, CharacterJobState>> get() = coordinator.jobs
    val events: SharedFlow<CharacterJobEvent> get() = coordinator.events

    fun jobFor(storyId: String): CharacterJobState? = coordinator.jobFor(storyId)

    fun isBusy(): Boolean = coordinator.isBusy()

    fun startFind(storyId: String): Boolean =
        coordinator.submit(CharacterJobState(storyId, CharacterJobKind.FIND, null, "Reading chapters...")) { update ->
            engine.findCharacters(storyId) { message -> update { it.copy(message = message) } }
            CharacterJobEvent.Succeeded(storyId, CharacterJobKind.FIND, null, null)
        }

    fun startPortrait(
        storyId: String,
        characterId: String,
    ): Boolean =
        coordinator.submit(CharacterJobState(storyId, CharacterJobKind.PORTRAIT, characterId, "Finding descriptions...")) { update ->
            val character = engine.draftPortrait(storyId, characterId) { message -> update { it.copy(message = message) } }
            CharacterJobEvent.Succeeded(storyId, CharacterJobKind.PORTRAIT, characterId, character.name)
        }
}
