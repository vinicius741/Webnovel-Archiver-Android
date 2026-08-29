package com.vinicius741.webnovelarchiver.app

import com.vinicius741.webnovelarchiver.feature.ai.frameIsAiControls
import com.vinicius741.webnovelarchiver.feature.ai.rerenderDetailsIfVisible
import com.vinicius741.webnovelarchiver.feature.ai.showAiControls
import com.vinicius741.webnovelarchiver.feature.ai.showChapterRewritePreview
import com.vinicius741.webnovelarchiver.feature.details.renderStoryOperationProgress
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.StoryOperationKind
import com.vinicius741.webnovelarchiver.navigation.StoryOperationState
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

/*
 * The activity-side bridge for background chapter-rewrite jobs (see AiChapterRewriteJobCoordinator),
 * mirroring [attachAiCoverJobBridge]: a running job stays visible through the shared
 * [StoryOperationState] slot, and terminal events surface the persisted draft. The bridge dies
 * with the activity; every event it might miss is covered by the persisted draft, the foreground
 * service's result notification, and AI Controls rehydrating manifest state on render.
 */

internal fun MainActivity.attachAiChapterRewriteJobBridge() {
    val coordinator = appContainer.aiChapterRewriteJobCoordinator
    scope.launch {
        var renderedMessage: String? = null
        coordinator.jobs.collect { jobs ->
            val job = jobs.values.firstOrNull()
            if (job != null) {
                val current = storyOperation
                val ownsSlot =
                    current == null || (current.storyId == job.storyId && current.kind == StoryOperationKind.AI_CHAPTER_REWRITE)
                if (ownsSlot) {
                    val next = StoryOperationState(job.storyId, StoryOperationKind.AI_CHAPTER_REWRITE, job.message)
                    storyOperation = next
                    if (renderedMessage != job.message) {
                        detailsOperationSlot?.let { renderStoryOperationProgress(it, next) }
                        if (frameIsAiControls(job.storyId)) showAiControls(job.storyId)
                    }
                    renderedMessage = job.message
                }
            } else {
                renderedMessage = null
                val cleared = storyOperation?.takeIf { it.kind == StoryOperationKind.AI_CHAPTER_REWRITE }
                if (cleared != null) {
                    storyOperation = null
                    detailsOperationSlot = null
                    // The event collector may re-render before or after this clearing; refresh the
                    // visible surface so a finished job never leaves a stale "Polishing…" slot.
                    if (frameIsAiControls(cleared.storyId)) showAiControls(cleared.storyId) else rerenderDetailsIfVisible(cleared.storyId)
                }
            }
        }
    }
    scope.launch {
        // Queue changes (batch polish enqueues, cancels, drain) refresh the visible AI Controls
        // screen so its queued count and cancel action stay current. The previous set joins the
        // check so the drain-to-empty transition also triggers exactly one final refresh.
        var lastQueuedStories: Set<String> = emptySet()
        coordinator.queue.collect { queue ->
            val stories = queue.map { it.storyId }.toSet()
            (stories + lastQueuedStories).firstOrNull { frameIsAiControls(it) }?.let { showAiControls(it) }
            lastQueuedStories = stories
        }
    }
    scope.launch {
        coordinator.events.collect { event ->
            when (event) {
                is AiChapterRewriteJobEvent.Succeeded -> {
                    val detail =
                        when (event.status) {
                            "ready" -> "Polished draft ready — compare before applying."
                            "blocked" -> "Polished draft flagged by the verifier — review it."
                            else -> "Polished draft could not be verified — review or regenerate."
                        }
                    toast(detail)
                    if (frameIsAiControls(event.storyId) ||
                        frame.tag == AppRoute.Reader(event.storyId, event.chapterId).stableKey
                    ) {
                        showChapterRewritePreview(event.storyId, event.chapterId)
                    } else {
                        // A Details screen showing the mirrored progress slot must rebuild too,
                        // or the finished job leaves a stale "Polishing…" spinner behind.
                        rerenderDetailsIfVisible(event.storyId)
                    }
                }
                is AiChapterRewriteJobEvent.Failed -> {
                    toast("Chapter polish failed: ${event.message}")
                    if (frameIsAiControls(event.storyId)) showAiControls(event.storyId) else rerenderDetailsIfVisible(event.storyId)
                }
            }
        }
    }
}
