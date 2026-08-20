package com.vinicius741.webnovelarchiver.app

import com.vinicius741.webnovelarchiver.ai.AiCoverJobEvent
import com.vinicius741.webnovelarchiver.ai.AiCoverJobKind
import com.vinicius741.webnovelarchiver.data.storage.AiCoverDraftRecord
import com.vinicius741.webnovelarchiver.feature.ai.frameIsAiControls
import com.vinicius741.webnovelarchiver.feature.ai.rerenderDetailsIfVisible
import com.vinicius741.webnovelarchiver.feature.ai.showAiControls
import com.vinicius741.webnovelarchiver.feature.details.renderStoryOperationProgress
import com.vinicius741.webnovelarchiver.navigation.StoryOperationKind
import com.vinicius741.webnovelarchiver.navigation.StoryOperationState
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

/*
 * The activity-side bridge for background AI cover jobs (see AiCoverJobCoordinator). Cover
 * generation now runs on the process-wide application scope, so the work outlives this activity —
 * this bridge is what makes a running job VISIBLE again: it mirrors the coordinator's state into
 * the shared [StoryOperationState] slot (Details progress bar, AI Controls button gating) and
 * reacts to terminal events by surfacing the persisted draft, toasting, and re-rendering.
 *
 * The bridge dies with the activity (it launches on the activity scope), but every event it might
 * miss is covered elsewhere: the draft itself is persisted before the event fires, the foreground
 * service posts a result notification, and showAiControls rehydrates persisted drafts on render.
 */

/**
 * Attaches the cover-job observers. Called once from [MainActivity.onCreate]; the collectors are
 * not stored in [com.vinicius741.webnovelarchiver.navigation.ScreenHost.screenObserver] on purpose
 * — they must survive in-place screen re-renders and only end with the activity.
 */
internal fun MainActivity.attachAiCoverJobBridge() {
    val coordinator = appContainer.aiCoverJobCoordinator
    scope.launch {
        var renderedMessages: Map<String, String> = emptyMap()
        coordinator.jobs.collect { jobs ->
            val job = jobs.values.firstOrNull()
            if (job != null) {
                val promptChanged =
                    job.persistedPrompt?.let { prompt ->
                        aiControlsScreenState.replaceCoverPreviewWithPrompt(job.storyId, prompt)
                    } ?: false
                val current = storyOperation
                val ownsSlot =
                    current == null || (current.storyId == job.storyId && current.kind == StoryOperationKind.AI_COVER)
                if (ownsSlot) {
                    val next = StoryOperationState(job.storyId, StoryOperationKind.AI_COVER, job.message)
                    storyOperation = next
                    if (promptChanged || renderedMessages[job.storyId] != job.message) {
                        detailsOperationSlot?.let { renderStoryOperationProgress(it, next) }
                        if (frameIsAiControls(job.storyId)) showAiControls(job.storyId)
                    }
                }
            } else {
                val cleared = storyOperation?.takeIf { it.kind == StoryOperationKind.AI_COVER }
                if (cleared != null) {
                    storyOperation = null
                    detailsOperationSlot = null
                    // The event collector may re-render before or after this clearing; refresh the
                    // visible surface so a finished job never leaves a stale "Generating..." slot.
                    if (frameIsAiControls(cleared.storyId)) showAiControls(cleared.storyId) else rerenderDetailsIfVisible(cleared.storyId)
                }
            }
            renderedMessages = jobs.mapValues { it.value.message }
        }
    }
    scope.launch {
        coordinator.events.collect { event -> presentAiCoverJobEvent(event) }
    }
}

private fun MainActivity.presentAiCoverJobEvent(event: AiCoverJobEvent) {
    when (event) {
        is AiCoverJobEvent.Succeeded -> {
            when (val record = event.record) {
                is AiCoverDraftRecord.PromptOnly -> {
                    // A fresh prompt invalidates the preview painted from the previous one (the store
                    // dropped its image on save); clear the in-memory mirror so the replaced preview
                    // stays neither renderable nor applicable.
                    aiControlsScreenState.coverDrafts.remove(event.storyId)
                    aiControlsScreenState.coverPrompts[event.storyId] = record.prompt
                }
                is AiCoverDraftRecord.Image -> {
                    aiControlsScreenState.coverDrafts[event.storyId] = record.draft
                    if (event.kind == AiCoverJobKind.ONE_STEP) {
                        // The one-shot flow bypasses the prompt editor; any staged prompt is stale.
                        aiControlsScreenState.coverPrompts.remove(event.storyId)
                    } else {
                        // Keep the editor in sync with the cleaned prompt the model actually received.
                        aiControlsScreenState.coverPrompts[event.storyId] = record.draft.prompt
                    }
                }
            }
            val message =
                if (event.record is AiCoverDraftRecord.PromptOnly) {
                    "Image prompt ready — edit it under More options → AI Controls"
                } else {
                    "AI cover ready — preview it under More options → AI Controls"
                }
            if (frameIsAiControls(event.storyId)) {
                showAiControls(event.storyId)
            } else {
                toast(message)
                rerenderDetailsIfVisible(event.storyId)
            }
        }
        is AiCoverJobEvent.Failed -> {
            event.persistedPrompt?.let { prompt ->
                aiControlsScreenState.replaceCoverPreviewWithPrompt(event.storyId, prompt)
            }
            toast(event.message)
            if (frameIsAiControls(event.storyId)) showAiControls(event.storyId) else rerenderDetailsIfVisible(event.storyId)
        }
    }
}
