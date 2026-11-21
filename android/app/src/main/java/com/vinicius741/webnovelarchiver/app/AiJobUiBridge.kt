package com.vinicius741.webnovelarchiver.app

import com.vinicius741.webnovelarchiver.ai.AiCoverJobEvent
import com.vinicius741.webnovelarchiver.ai.AiCoverJobKind
import com.vinicius741.webnovelarchiver.ai.AiCoverJobState
import com.vinicius741.webnovelarchiver.ai.AiDescriptionJobEvent
import com.vinicius741.webnovelarchiver.ai.AiJobModel
import com.vinicius741.webnovelarchiver.ai.CharacterJobEvent
import com.vinicius741.webnovelarchiver.ai.CharacterJobKind
import com.vinicius741.webnovelarchiver.data.storage.AiCoverDraftRecord
import com.vinicius741.webnovelarchiver.feature.ai.AiAttention
import com.vinicius741.webnovelarchiver.feature.ai.AiControlsTab
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.operations.StoryOperationKind
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/*
 * The activity-side bridge for the background AI jobs (cover, description, characters), which run
 * on the process-wide application scope and so outlive this activity. It translates them into
 * state that screens observe: running jobs into the AI slot of [StoryOperations], finished results
 * and failures into [AiAttention]. It toasts, but it redraws nothing; the AI tab, the story page and
 * the job chip follow that state themselves.
 *
 * The bridge dies with the activity (it launches on the activity scope), but every event it might
 * miss is covered elsewhere: cover results are persisted before the event fires, the foreground
 * service posts a result notification, and the AI tab rehydrates persisted cover work.
 */

/** Attaches the AI job observers. Called once from [MainActivity.onCreate]; they end with the activity. */
internal fun MainActivity.attachAiJobBridges() {
    val container = appContainer
    val attention = container.aiAttention
    mirrorIntoAiSlot(container.aiCoverJobCoordinator.jobs, StoryOperationKind.AI_COVER) { job ->
        // A one-step job persists its prompt before painting: the old preview no longer matches it.
        (job as? AiCoverJobState)?.persistedPrompt?.let { prompt ->
            val held = attention.state.value
            if (held.coverPrompts[job.storyId]?.text != prompt || job.storyId in held.coverDrafts) {
                attention.replaceCoverPreviewWithPrompt(job.storyId, prompt)
            }
        }
    }
    mirrorIntoAiSlot(container.aiDescriptionJobs.jobs, StoryOperationKind.AI_DESCRIPTION)
    scope.launch { container.aiCoverJobCoordinator.events.collect { presentCoverEvent(attention, it) } }
    scope.launch { container.aiDescriptionJobs.events.collect { presentDescriptionEvent(attention, it) } }
    scope.launch { container.characterJobCoordinator.events.collect { presentCharacterEvent(attention, it) } }
}

/** Keeps the AI slot in step with one coordinator: its running job's progress, then clearing it once idle. */
private fun MainActivity.mirrorIntoAiSlot(
    jobs: StateFlow<Map<String, AiJobModel>>,
    kind: StoryOperationKind,
    onJob: (AiJobModel) -> Unit = {},
) {
    scope.launch {
        jobs.collect { running ->
            val job = running.values.firstOrNull()
            if (job != null) {
                onJob(job)
                operations.updateAi(job.storyId, kind, job.message, startIfIdle = true)
            } else {
                operations.clearAi(kind)
            }
        }
    }
}

/** The user is looking at [storyId]'s AI tab, which shows a failure itself (as a toast) instead of the chip. */
private fun MainActivity.viewingAiTab(storyId: String): Boolean = navigator.current == AppRoute.AiControls(storyId)

private fun MainActivity.presentCoverEvent(
    attention: AiAttention,
    event: AiCoverJobEvent,
) {
    when (event) {
        is AiCoverJobEvent.Succeeded ->
            when (val record = event.record) {
                // A fresh prompt invalidates the preview painted from the previous one (the store
                // dropped its image on save), so the replaced preview is neither shown nor applicable.
                is AiCoverDraftRecord.PromptOnly -> attention.replaceCoverPreviewWithPrompt(event.storyId, record.prompt)
                is AiCoverDraftRecord.Image -> {
                    attention.setCoverDraft(event.storyId, record.draft)
                    // The one-shot flow bypasses the prompt editor, so any staged prompt is stale; otherwise
                    // the editor keeps the cleaned prompt the model actually received.
                    attention.setCoverPrompt(event.storyId, if (event.kind == AiCoverJobKind.ONE_STEP) null else record.draft.prompt)
                }
            }
        is AiCoverJobEvent.Failed -> {
            event.persistedPrompt?.let { attention.replaceCoverPreviewWithPrompt(event.storyId, it) }
            reportFailure(attention, event.storyId, AiControlsTab.COVER, event.message)
        }
    }
}

private fun MainActivity.presentDescriptionEvent(
    attention: AiAttention,
    event: AiDescriptionJobEvent,
) {
    when (event) {
        is AiDescriptionJobEvent.Succeeded -> {
            attention.setDescriptionDraft(event.storyId, event.draft)
            // The chip points back to the waiting preview; the open Description tab marks it seen at once.
            attention.markUnseen(event.storyId, AiControlsTab.DESCRIPTION)
        }
        is AiDescriptionJobEvent.Failed -> reportFailure(attention, event.storyId, AiControlsTab.DESCRIPTION, event.message)
    }
}

/** Results are saved before the event arrives; the Characters tab reloads them when its job ends. */
private fun MainActivity.presentCharacterEvent(
    attention: AiAttention,
    event: CharacterJobEvent,
) {
    when (event) {
        is CharacterJobEvent.Succeeded -> {
            attention.markUnseen(event.storyId, AiControlsTab.CHARACTERS)
            toast(if (event.kind == CharacterJobKind.FIND) "Characters found" else "Portrait of ${event.characterName} ready")
        }
        is CharacterJobEvent.Failed -> toast(event.message)
    }
}

/** On the story's AI tab a failure is a toast; anywhere else the job chip shows it until dismissed. */
private fun MainActivity.reportFailure(
    attention: AiAttention,
    storyId: String,
    tab: AiControlsTab,
    message: String,
) {
    if (viewingAiTab(storyId)) toast(message) else attention.recordFailure(storyId, tab, message)
}
