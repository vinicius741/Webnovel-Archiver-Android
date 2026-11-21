package com.vinicius741.webnovelarchiver.feature.ai

import com.vinicius741.webnovelarchiver.ai.AiCoverDraft
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The prompt box of a story's Cover tab. [revision] changes only when something other than typing
 * sets the text (a job, Clear, Reuse this prompt), which is when the open tab redraws the box.
 */
internal data class CoverPrompt(
    val text: String,
    val revision: Int,
)

/**
 * AI results waiting for the user, per story. Background jobs finish while the AI tab is closed, so
 * these outlive any one screen: the AI tab, the story page's hero and the app-wide job chip all read
 * them.
 *
 * - [descriptionDrafts]: generated synopses not yet applied. Not persisted: an unapplied draft lasts
 *   only as long as the process.
 * - [coverDrafts]: a painted cover waiting for "Use it" or "Keep current" (persisted by the cover store
 *   too; this is the shown copy).
 * - [coverPrompts]: the Cover tab's prompt box, which jobs also rewrite.
 * - The job chip's markers: finished results not yet looked at, failures not yet acknowledged
 *   (oldest first, keyed by [AiJobChipPlanning.failureKey]), and dismissed chip entries. Unseen covers
 *   are persisted by the repository instead (`aiCoverUnseen`).
 */
internal data class AiAttentionState(
    val descriptionDrafts: Map<String, String> = emptyMap(),
    val coverDrafts: Map<String, AiCoverDraft> = emptyMap(),
    val coverPrompts: Map<String, CoverPrompt> = emptyMap(),
    val unseenDescriptions: Set<String> = emptySet(),
    val unseenCharacters: Set<String> = emptySet(),
    val failures: Map<String, AiJobFailure> = emptyMap(),
    val dismissed: Set<String> = emptySet(),
)

/** One process-wide instance ([com.vinicius741.webnovelarchiver.app.AppContainer.aiAttention]); safe to update from any thread. */
internal class AiAttention {
    private val mutableState = MutableStateFlow(AiAttentionState())
    val state: StateFlow<AiAttentionState> = mutableState.asStateFlow()

    private var promptRevision = 0

    fun setDescriptionDraft(
        storyId: String,
        draft: String?,
    ) = mutableState.update { it.copy(descriptionDrafts = it.descriptionDrafts.with(storyId, draft)) }

    fun setCoverDraft(
        storyId: String,
        draft: AiCoverDraft?,
    ) = mutableState.update { it.copy(coverDrafts = it.coverDrafts.with(storyId, draft)) }

    /** Sets the prompt box. Typing ([typed]) keeps the revision, so the open tab is not redrawn under the cursor. */
    fun setCoverPrompt(
        storyId: String,
        text: String?,
        typed: Boolean = false,
    ) = mutableState.update { state ->
        val current = state.coverPrompts[storyId]
        val next =
            when {
                text == null -> null
                // Typing keeps the box's revision; a first keystroke takes revision 0, the same as no box.
                typed -> current?.copy(text = text) ?: CoverPrompt(text, 0)
                current?.text == text -> current
                else -> CoverPrompt(text, nextRevision())
            }
        state.copy(coverPrompts = state.coverPrompts.with(storyId, next))
    }

    /** Replaces a painted preview once a newer prompt was persisted: the preview no longer matches it. */
    fun replaceCoverPreviewWithPrompt(
        storyId: String,
        prompt: String,
    ) {
        setCoverDraft(storyId, null)
        setCoverPrompt(storyId, prompt)
    }

    fun markUnseen(
        storyId: String,
        tab: AiControlsTab,
    ) = mutableState.update { state ->
        when (tab) {
            AiControlsTab.DESCRIPTION -> state.copy(unseenDescriptions = state.unseenDescriptions + storyId)
            AiControlsTab.CHARACTERS -> state.copy(unseenCharacters = state.unseenCharacters + storyId)
            AiControlsTab.COVER -> state
        }
    }

    /** Looking at a tab (or starting a new run there) supersedes its unseen marker, earlier failure and dismissals. */
    fun markSeen(
        storyId: String,
        tab: AiControlsTab,
    ) = mutableState.update { state ->
        val failureKey = AiJobChipPlanning.failureKey(storyId, tab)
        state.copy(
            unseenDescriptions = if (tab == AiControlsTab.DESCRIPTION) state.unseenDescriptions - storyId else state.unseenDescriptions,
            unseenCharacters = if (tab == AiControlsTab.CHARACTERS) state.unseenCharacters - storyId else state.unseenCharacters,
            failures = state.failures - failureKey,
            dismissed = state.dismissed - failureKey - AiJobChipPlanning.readyKey(storyId, tab),
        )
    }

    fun recordFailure(
        storyId: String,
        tab: AiControlsTab,
        message: String,
    ) = mutableState.update { state ->
        val key = AiJobChipPlanning.failureKey(storyId, tab)
        // Re-insert so the newest failure is last and wins the chip.
        state.copy(failures = state.failures - key + (key to AiJobFailure(storyId, tab, message)), dismissed = state.dismissed - key)
    }

    fun dismiss(key: String) = mutableState.update { it.copy(dismissed = it.dismissed + key) }

    private fun nextRevision(): Int = synchronized(this) { ++promptRevision }
}

private fun <V> Map<String, V>.with(
    key: String,
    value: V?,
): Map<String, V> = if (value == null) this - key else this + (key to value)
