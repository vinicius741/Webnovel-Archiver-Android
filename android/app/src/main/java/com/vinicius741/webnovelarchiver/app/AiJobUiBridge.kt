package com.vinicius741.webnovelarchiver.app

import com.vinicius741.webnovelarchiver.ai.AiCoverJobEvent
import com.vinicius741.webnovelarchiver.ai.AiCoverJobKind
import com.vinicius741.webnovelarchiver.ai.CharacterJobEvent
import com.vinicius741.webnovelarchiver.ai.CharacterJobKind
import com.vinicius741.webnovelarchiver.data.storage.AiCoverDraftRecord
import com.vinicius741.webnovelarchiver.feature.ai.AiControlsTab
import com.vinicius741.webnovelarchiver.feature.ai.aiControlsTab
import com.vinicius741.webnovelarchiver.feature.ai.frameIsAiControls
import com.vinicius741.webnovelarchiver.feature.ai.patchCharacterProgress
import com.vinicius741.webnovelarchiver.feature.ai.rerenderDetailsIfVisible
import com.vinicius741.webnovelarchiver.feature.ai.showAiControls
import com.vinicius741.webnovelarchiver.feature.ai.updateAiControlsProgress
import com.vinicius741.webnovelarchiver.operations.StoryOperationKind
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

/*
 * The activity-side bridge for background AI cover jobs, which
 * run on the process-wide application scope and so outlive this activity — this bridge is what
 * makes a running job VISIBLE again: it mirrors the coordinator's state into the AI
 * [StoryOperationState] slot (Details progress bar, AI Controls button gating) and reacts to
 * terminal events by surfacing the persisted draft, toasting, and re-rendering.
 *
 * The bridge dies with the activity (it launches on the activity scope), but every event it might
 * miss is covered elsewhere: the draft itself is persisted before the event fires, the foreground
 * service posts a result notification, and the AI screens rehydrate persisted state on render.
 */

/**
 * Attaches the AI job observers. Called once from [MainActivity.onCreate]; the collectors are
 * not stored in [com.vinicius741.webnovelarchiver.navigation.ScreenHost.screenObserver] on purpose
 * — they must survive in-place screen re-renders and only end with the activity.
 */
internal fun MainActivity.attachAiJobBridges() {
    attachCoverJobBridge()
    attachCharacterJobBridge()
}

/**
 * Characters-tab jobs: a job starting or ending re-renders the tab when it is on screen; message
 * ticks only patch its progress line. Results are already saved when the event arrives.
 */
private fun MainActivity.attachCharacterJobBridge() {
    val coordinator = appContainer.characterJobCoordinator
    scope.launch {
        var running: Map<String, Pair<CharacterJobKind, String?>> = emptyMap()
        coordinator.jobs.collect { jobs ->
            val now = jobs.mapValues { it.value.kind to it.value.characterId }
            // A start or finish changes buttons and results: re-render. A message only patches its line.
            (running.keys + now.keys).forEach { storyId ->
                val showing = frameIsAiControls(storyId) && aiControlsTab(storyId) == AiControlsTab.CHARACTERS
                if (showing && running[storyId] != now[storyId]) showAiControls(storyId)
            }
            jobs.values.forEach { patchCharacterProgress(it) }
            // Another novel's job ending re-enables this novel's buttons.
            if (running.isNotEmpty() && now.isEmpty()) {
                (navigator.current as? com.vinicius741.webnovelarchiver.navigation.AppRoute.AiControls)?.storyId?.let { storyId ->
                    if (storyId !in running && aiControlsTab(storyId) == AiControlsTab.CHARACTERS) showAiControls(storyId)
                }
            }
            running = now
        }
    }
    scope.launch {
        coordinator.events.collect { event ->
            // The jobs collector has re-rendered by now; results were saved before the event.
            val showing = frameIsAiControls(event.storyId) && aiControlsTab(event.storyId) == AiControlsTab.CHARACTERS
            when (event) {
                is CharacterJobEvent.Succeeded -> {
                    if (!showing) aiControlsScreenState.unseenCharacters += event.storyId
                    toast(if (event.kind == CharacterJobKind.FIND) "Characters found" else "Portrait of ${event.characterName} ready")
                }
                is CharacterJobEvent.Failed -> toast(event.message)
            }
        }
    }
}

private fun MainActivity.attachCoverJobBridge() {
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
                val next = operations.updateAi(job.storyId, StoryOperationKind.AI_COVER, job.message, startIfIdle = true)
                if (next != null) {
                    if (promptChanged || renderedMessages[job.storyId] != job.message) {
                        updateAiControlsProgress(next, structuralChange = promptChanged)
                    }
                }
            } else {
                clearOperationSlot(StoryOperationKind.AI_COVER)
            }
            renderedMessages = jobs.mapValues { it.value.message }
        }
    }
    scope.launch {
        coordinator.events.collect { event -> presentAiCoverJobEvent(event) }
    }
}

/** Clears this kind's progress slot and refreshes the visible surface so nothing stays stale. */
private fun MainActivity.clearOperationSlot(kind: StoryOperationKind) {
    val cleared = operations.clearAi(kind) ?: return
    // The event collector may re-render before or after this clearing; refresh the visible
    // surface so a finished job never leaves a stale progress slot behind.
    if (frameIsAiControls(cleared.storyId)) showAiControls(cleared.storyId) else rerenderDetailsIfVisible(cleared.storyId)
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
            // Off this page the app-wide job chip announces the result (it follows the repository's
            // unseen set), so no toast has to describe where to find it.
            if (frameIsAiControls(event.storyId)) showAiControls(event.storyId) else rerenderDetailsIfVisible(event.storyId)
        }
        is AiCoverJobEvent.Failed -> {
            event.persistedPrompt?.let { prompt ->
                aiControlsScreenState.replaceCoverPreviewWithPrompt(event.storyId, prompt)
            }
            if (frameIsAiControls(event.storyId)) {
                toast(event.message)
                showAiControls(event.storyId)
            } else {
                aiControlsScreenState.recordFailure(event.storyId, AiControlsTab.COVER, event.message)
                aiControlsScreenState.refreshJobChip()
                rerenderDetailsIfVisible(event.storyId)
            }
        }
    }
}
