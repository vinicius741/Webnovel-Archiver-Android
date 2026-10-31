package com.vinicius741.webnovelarchiver.feature.ai

import com.vinicius741.webnovelarchiver.ai.AiCoverJobCoordinator
import com.vinicius741.webnovelarchiver.ai.AiJobForegroundService
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.data.repository.deleteAiCoverDraft
import com.vinicius741.webnovelarchiver.data.repository.loadAiCoverDraft
import com.vinicius741.webnovelarchiver.data.storage.AiCoverDraftRecord
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.navigation.StoryOperationKind
import com.vinicius741.webnovelarchiver.navigation.StoryOperationState
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

// Billable cover-art generation, driven by the Cover tab's prompt box: an empty box runs both
// OpenRouter stages in one job (AI prompt + image); a filled box paints just that prompt; "Write
// prompt with AI" runs only the prompt stage and puts the result in the box. Jobs run on the
// process-wide AiCoverJobCoordinator, not the activity scope, so navigating away never cancels an
// in-flight call; all share the AI_COVER operation slot and never run concurrently for a story.

/** Generate cover: an empty [prompt] lets AI write one first (two calls); otherwise paints [prompt]. */
internal fun ScreenHost.generateAiCover(
    story: Story,
    prompt: String,
) {
    val settings = repository.aiSettings.get()
    if (prompt.isNotBlank()) {
        val message =
            "Generate the image with ${settings.imageModel}? This calls OpenRouter and uses your API credits. " +
                "Earlier covers remain in Saved covers."
        confirm(message, confirmLabel = "Generate") { startAiCoverImageDraft(story, prompt) }
        return
    }
    // First-ever run skips the confirmation, as before; repeat runs say what they cost.
    if (story.aiCoverPath == null && aiControlsScreenState.coverDrafts[story.id] == null) {
        startAiCoverDraft(story)
        return
    }
    val message =
        "Generate a new AI cover with ${settings.imageModel}? This makes two OpenRouter calls " +
            "(image prompt + image) and uses your API credits. " +
            "Automatic selection also uses OpenRouter credits for uncached chapters. Earlier covers remain in Saved covers."
    confirm(message, confirmLabel = "Generate") { startAiCoverDraft(story) }
}

/** Write prompt with AI: fills the prompt box without painting; confirms before replacing typed text. */
internal fun ScreenHost.writeAiCoverPrompt(
    story: Story,
    currentPrompt: String,
) {
    if (currentPrompt.isBlank() && story.aiCoverPath == null) {
        startAiCoverPromptDraft(story)
        return
    }
    val model = repository.aiSettings.get().descriptionModel
    val message =
        "Write a new image prompt with $model? This calls OpenRouter and uses your API credits. " +
            "Automatic selection also uses OpenRouter credits for uncached chapters." +
            (if (currentPrompt.isNotBlank()) " It replaces the prompt in the box." else "")
    confirm(message, confirmLabel = "Write prompt") { startAiCoverPromptDraft(story) }
}

/** One-shot flow: both billable stages in a single background run. */
internal fun ScreenHost.startAiCoverDraft(story: Story) {
    startAiCoverJob(story, "Generating cover...") { coordinator -> coordinator.startOneShot(story.id) }
}

/** Shared launch path: coordinator-backed job (survives this activity) + foreground service. */
private fun ScreenHost.startAiCoverJob(
    story: Story,
    initialMessage: String,
    onAccepted: () -> Unit = {},
    start: (AiCoverJobCoordinator) -> Boolean,
) {
    if (storyOperation != null) {
        toast("Please wait for the current operation to finish")
        return
    }
    val coordinator = app.appContainer.aiCoverJobCoordinator
    if (!start(coordinator)) {
        toast("Please wait for the current cover generation to finish")
        return
    }
    onAccepted()
    aiControlsScreenState.clearChipHistory(story.id, AiControlsTab.COVER)
    // Optimistic set for the first re-render; the activity bridge syncs the slot on every emission.
    storyOperation = StoryOperationState(story.id, StoryOperationKind.AI_COVER, initialMessage)
    detailsOperationSlot = null
    showAiControls(story.id)
    AiJobForegroundService.start(app, "Generating AI cover...")
}

/** Stage 1: writes the image prompt only; a fresh prompt invalidates any painted preview. */
internal fun ScreenHost.startAiCoverPromptDraft(story: Story) {
    startAiCoverJob(story, "Writing image prompt...") { coordinator -> coordinator.startPromptDraft(story.id) }
}

internal fun ScreenHost.startAiCoverImageDraft(
    story: Story,
    prompt: String,
) {
    startAiCoverJob(
        story,
        "Painting cover...",
        onAccepted = {
            // The coordinator persists this prompt before calling the image model.
            aiControlsScreenState.coverPrompts[story.id] = prompt
            aiControlsScreenState.coverDrafts.remove(story.id)
        },
    ) { coordinator -> coordinator.startImageDraft(story.id, prompt) }
}

/** Empties the prompt box. A waiting new cover keeps its own record until the user decides on it. */
internal fun ScreenHost.clearAiCoverPrompt(story: Story) {
    aiControlsScreenState.coverPrompts[story.id] = ""
    scope.launch {
        coverUiAttempt {
            if (aiControlsScreenState.coverDrafts[story.id] == null) repository.deleteAiCoverDraft(story.id)
            if (frameIsAiControls(story.id)) showAiControls(story.id)
        }
    }
}

/**
 * Shows a draft persisted while this screen was closed (possibly a previous activity instance).
 * Memory wins over disk; re-renders only when hydration added something, since every render
 * calls this — an unconditional re-render would loop.
 */
internal fun ScreenHost.hydrateAiCoverDraftFromStorage(storyId: String) {
    if (aiControlsScreenState.coverDrafts[storyId] != null) return
    scope.launch {
        val record = repository.loadAiCoverDraft(storyId) ?: return@launch
        var hydrated = false
        when (record) {
            is AiCoverDraftRecord.PromptOnly ->
                if (aiControlsScreenState.coverPrompts[storyId] == null) {
                    aiControlsScreenState.coverPrompts[storyId] = record.prompt
                    hydrated = true
                }
            // The preview card shows the prompt too, so don't re-seed the editor once a preview exists.
            is AiCoverDraftRecord.Image ->
                if (aiControlsScreenState.coverDrafts[storyId] == null) {
                    aiControlsScreenState.coverDrafts[storyId] = record.draft
                    hydrated = true
                }
        }
        if (hydrated && frameIsAiControls(storyId)) showAiControls(storyId)
    }
}

/**
 * The AI-cover operation for a story: the shared slot, or a background job the bridge has not
 * reflected yet (e.g. after activity recreation).
 */
internal fun ScreenHost.aiCoverOperationFor(storyId: String): StoryOperationState? {
    storyOperation?.takeIf { it.storyId == storyId && it.kind == StoryOperationKind.AI_COVER }?.let { return it }
    return app.appContainer.aiCoverJobCoordinator.jobFor(storyId)?.let {
        StoryOperationState(it.storyId, StoryOperationKind.AI_COVER, it.message)
    }
}
