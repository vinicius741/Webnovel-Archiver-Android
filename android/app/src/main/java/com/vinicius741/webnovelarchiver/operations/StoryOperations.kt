package com.vinicius741.webnovelarchiver.operations

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class StoryOperationKind {
    EPUB,
    CLEANUP,
    SYNC,
    AI_DESCRIPTION,
    AI_COVER,
}

data class StoryOperationState(
    val storyId: String,
    val kind: StoryOperationKind,
    val message: String,
    val progress: Float? = null,
)

/**
 * The work in flight on a story that several screens show and gate on, published as state instead
 * of fields on the activity. Screens observe [content] and [ai] and redraw themselves; nothing here
 * knows about Views.
 *
 * Two independent slots, and the Updates syncs:
 * - [content]: sync, cleanup or EPUB. These read or rewrite the same story files, so at most one
 *   runs, and it gates the story page's content actions.
 * - [ai]: an AI cover or description job. AI jobs only produce drafts, so they never block content
 *   work; only AI starts gate on this slot.
 * - [updatesSyncs]: novels syncing from Updates (a row or "Sync all"). A row sync also holds
 *   [content] when it is free, so the story page shows it; a "Sync all" item does not (it would
 *   block every novel's content actions for the whole run), so content actions check this too.
 *
 * One instance lives with the activity, the same lifetime the operations' coroutines have.
 */
class StoryOperations {
    private val contentState = MutableStateFlow<StoryOperationState?>(null)
    private val aiState = MutableStateFlow<StoryOperationState?>(null)
    private val updatesState = MutableStateFlow<Map<String, String>>(emptyMap())

    val content: StateFlow<StoryOperationState?> = contentState.asStateFlow()
    val ai: StateFlow<StoryOperationState?> = aiState.asStateFlow()

    /** Story id to the sync's latest status line, while Updates syncs that novel. */
    val updatesSyncs: StateFlow<Map<String, String>> = updatesState.asStateFlow()

    /** The running content operation on [storyId], if any. */
    fun contentFor(storyId: String): StoryOperationState? = content.value?.takeIf { it.storyId == storyId }

    /** The running AI job on [storyId], if any. */
    fun aiFor(storyId: String): StoryOperationState? = ai.value?.takeIf { it.storyId == storyId }

    /** True while Updates syncs [storyId]; another sync or content work on it must wait. */
    fun isSyncingFromUpdates(storyId: String): Boolean = storyId in updatesSyncs.value

    /** Lists [storyId] as syncing from Updates; false when it already is. */
    fun startUpdatesSync(
        storyId: String,
        message: String,
    ): Boolean {
        var started = false
        // update() runs the block again when another thread wrote in between, so the flag restarts each time.
        updatesState.update { current ->
            started = storyId !in current
            if (started) current + (storyId to message) else current
        }
        return started
    }

    /** Advances an Updates sync's status line, only while it is still listed (any thread). */
    fun updateUpdatesSync(
        storyId: String,
        message: String,
    ) {
        updatesState.update { current -> if (storyId in current) current + (storyId to message) else current }
    }

    fun endUpdatesSync(storyId: String) {
        updatesState.update { it - storyId }
    }

    /** Starts or advances the content operation. The latest call replaces the previous state. */
    fun setContent(
        storyId: String,
        kind: StoryOperationKind,
        message: String,
        progress: Float? = null,
    ) {
        contentState.value = StoryOperationState(storyId, kind, message, progress)
    }

    /**
     * Advances the content operation only while it is still [storyId]'s [kind], so a progress
     * message that arrives late, from any thread, cannot bring back a finished operation.
     */
    fun updateContent(
        storyId: String,
        kind: StoryOperationKind,
        message: String,
        progress: Float? = null,
    ) {
        contentState.update { current ->
            if (current?.storyId == storyId && current.kind == kind) StoryOperationState(storyId, kind, message, progress) else current
        }
    }

    /** Ends the content operation if it is still this story's [kind]; returns whether it did. */
    fun clearContent(
        storyId: String,
        kind: StoryOperationKind,
    ): Boolean = clear(contentState, storyId, kind)

    /** Starts or advances the AI job. */
    fun setAi(next: StoryOperationState) {
        aiState.value = next
    }

    /**
     * Advances the AI job only while it is still [storyId]'s [kind] (or none runs and [startIfIdle]),
     * so a late progress message cannot take over another story's slot.
     */
    fun updateAi(
        storyId: String,
        kind: StoryOperationKind,
        message: String,
        startIfIdle: Boolean = false,
    ): StoryOperationState? {
        var applied: StoryOperationState? = null
        aiState.update { current ->
            val owns = current?.storyId == storyId && current.kind == kind
            applied = if (owns || (current == null && startIfIdle)) StoryOperationState(storyId, kind, message) else null
            applied ?: current
        }
        return applied
    }

    /** Ends the AI job if it is still this story's [kind]; returns whether it did. */
    fun clearAi(
        storyId: String,
        kind: StoryOperationKind,
    ): Boolean = clear(aiState, storyId, kind)

    /** Ends whichever story's AI job of [kind] is running; returns the cleared state. */
    fun clearAi(kind: StoryOperationKind): StoryOperationState? {
        val current = aiState.value?.takeIf { it.kind == kind } ?: return null
        return current.takeIf { clear(aiState, it.storyId, kind) }
    }

    private fun clear(
        state: MutableStateFlow<StoryOperationState?>,
        storyId: String,
        kind: StoryOperationKind,
    ): Boolean {
        var cleared = false
        state.update { current ->
            cleared = current?.storyId == storyId && current.kind == kind
            if (cleared) null else current
        }
        return cleared
    }
}
