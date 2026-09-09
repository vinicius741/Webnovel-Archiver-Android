package com.vinicius741.webnovelarchiver.feature.ai

import com.vinicius741.webnovelarchiver.ai.AiCoverDraft

/**
 * Transient UI state for the AI Controls screen. Holds the pending (generated but not yet applied)
 * synopsis drafts keyed by story id, so a preview survives navigating back to Details and returning
 * to the screen while the user decides. Cleared on Apply/Discard; deliberately not persisted — an
 * unapplied draft is process-transient. Cover-art drafts get their own map of image bytes, and
 * staged cover generation adds an editable prompt draft between the two billable calls.
 */
class AiControlsScreenState {
    internal var binding: AiControlsBinding? = null

    val drafts: MutableMap<String, String> = linkedMapOf()
    val coverDrafts: MutableMap<String, AiCoverDraft> = linkedMapOf()
    val coverPrompts: MutableMap<String, String> = linkedMapOf()
    val coverOptionsExpanded: MutableSet<String> = mutableSetOf()

    /** Replaces a painted preview after the one-step flow has persisted its newer prompt. */
    internal fun replaceCoverPreviewWithPrompt(
        storyId: String,
        prompt: String,
    ): Boolean {
        val removedPreview = coverDrafts.remove(storyId) != null
        val changedPrompt = coverPrompts.put(storyId, prompt) != prompt
        return removedPreview || changedPrompt
    }
}
