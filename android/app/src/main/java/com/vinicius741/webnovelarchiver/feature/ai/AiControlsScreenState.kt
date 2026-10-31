package com.vinicius741.webnovelarchiver.feature.ai

import com.vinicius741.webnovelarchiver.ai.AiCoverDraft

/** The two halves of the AI Controls screen; each novel remembers the one last shown. */
internal enum class AiControlsTab(
    val label: String,
) {
    COVER("Cover"),
    DESCRIPTION("Description"),
}

/**
 * Transient UI state for the AI Controls screen. Holds the pending (generated but not yet applied)
 * synopsis drafts keyed by story id, so a preview survives navigating back to Details and returning
 * to the screen while the user decides. Cleared on Apply/Discard; deliberately not persisted — an
 * unapplied draft is process-transient. Cover-art drafts get their own map of image bytes, and
 * [coverPrompts] mirrors the editable prompt box (persisted separately as the prompt draft).
 *
 * The job-chip fields feed the app-wide chip (AiJobChip.kt): failures and dismissals are
 * in-memory only; unseen cover results are persisted by the repository instead.
 */
class AiControlsScreenState {
    internal var binding: AiControlsBinding? = null

    val drafts: MutableMap<String, String> = linkedMapOf()
    val coverDrafts: MutableMap<String, AiCoverDraft> = linkedMapOf()
    val coverPrompts: MutableMap<String, String> = linkedMapOf()
    internal val tabs: MutableMap<String, AiControlsTab> = linkedMapOf()

    /** Description drafts finished while their tab was not on screen. */
    internal val unseenDescriptions: MutableSet<String> = linkedSetOf()
    internal val jobFailures: LinkedHashMap<String, AiJobFailure> = linkedMapOf()
    internal val dismissedChips: MutableSet<String> = mutableSetOf()

    /** Re-evaluates the app-wide job chip; installed by attachAiJobChip. */
    internal var refreshJobChip: () -> Unit = {}

    /** Refreshes the model rows of whichever screen currently shows them (AI Settings). */
    internal var refreshModels: () -> Unit = {}

    /** Replaces a painted preview after the one-step flow has persisted its newer prompt. */
    internal fun replaceCoverPreviewWithPrompt(
        storyId: String,
        prompt: String,
    ): Boolean {
        val removedPreview = coverDrafts.remove(storyId) != null
        val changedPrompt = coverPrompts.put(storyId, prompt) != prompt
        return removedPreview || changedPrompt
    }

    internal fun recordFailure(
        storyId: String,
        tab: AiControlsTab,
        message: String,
    ) {
        val key = AiJobChipPlanning.failureKey(storyId, tab)
        // Re-insert so the newest failure is last and wins the chip.
        jobFailures.remove(key)
        jobFailures[key] = AiJobFailure(storyId, tab, message)
        dismissedChips.remove(key)
    }

    /** A new run or a visit to the page supersedes the story's earlier failure and dismissal. */
    internal fun clearChipHistory(
        storyId: String,
        tab: AiControlsTab,
    ) {
        jobFailures.remove(AiJobChipPlanning.failureKey(storyId, tab))
        dismissedChips.remove(AiJobChipPlanning.failureKey(storyId, tab))
        dismissedChips.remove(AiJobChipPlanning.readyKey(storyId, tab))
    }
}
