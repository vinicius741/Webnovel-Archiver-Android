package com.vinicius741.webnovelarchiver.feature.ai

import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.ai.AiCoverForegroundService
import com.vinicius741.webnovelarchiver.ai.AiCoverJobCoordinator
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.data.repository.deleteAiCoverDraft
import com.vinicius741.webnovelarchiver.data.repository.getAiUsageLedger
import com.vinicius741.webnovelarchiver.data.repository.loadAiCoverDraft
import com.vinicius741.webnovelarchiver.data.repository.saveAiCoverPromptDraft
import com.vinicius741.webnovelarchiver.data.storage.AiCoverDraftRecord
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.navigation.StoryOperationKind
import com.vinicius741.webnovelarchiver.navigation.StoryOperationState
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.applyInputStyle
import com.vinicius741.webnovelarchiver.ui.button
import com.vinicius741.webnovelarchiver.ui.card
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeBadge
import com.vinicius741.webnovelarchiver.ui.row
import com.vinicius741.webnovelarchiver.ui.spacer
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

// Billable cover-art generation: one-shot runs both OpenRouter stages in a single run; staged
// stops after the prompt and paints only on request (a repaint re-bills just the image call).
// Jobs run on the process-wide AiCoverJobCoordinator, not the activity scope, so navigating away
// never cancels an in-flight call; both modes share the AI_COVER operation slot and can never run
// concurrently for a story.

/**
 * Starts generation in the configured mode; asks before overwriting an applied cover or pending
 * drafts — every call bills the user's OpenRouter key.
 */
internal fun ScreenHost.generateAiCoverDraft(story: Story) {
    val settings = repository.getAiSettings()
    val hasApplied = story.aiCoverPath != null
    val hasPendingDraft = aiControlsScreenState.coverDrafts[story.id] != null
    val hasPendingPrompt = aiControlsScreenState.coverPrompts[story.id] != null
    val hasPendingWork = hasPendingDraft || hasPendingPrompt
    if (settings.coverOneStep) {
        if (!hasApplied && !hasPendingWork) {
            startAiCoverDraft(story)
            return
        }
        val message =
            "Generate a new AI cover with ${settings.imageModel}? This makes two OpenRouter calls " +
                "(image prompt + image) and uses your API credits." +
                (if (hasPendingWork) " The pending preview will be replaced." else "")
        confirm(message, confirmLabel = "Generate") { startAiCoverDraft(story) }
        return
    }
    if (!hasApplied && !hasPendingWork) {
        startAiCoverPromptDraft(story)
        return
    }
    val message =
        "Write a new image prompt with ${settings.descriptionModel}? This calls OpenRouter and uses your API credits." +
            (if (hasPendingWork) " The pending prompt and preview will be replaced." else "")
    confirm(message, confirmLabel = "Generate") { startAiCoverPromptDraft(story) }
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
    // Optimistic set for the first re-render; the activity bridge syncs the slot on every emission.
    storyOperation = StoryOperationState(story.id, StoryOperationKind.AI_COVER, initialMessage)
    detailsOperationSlot = null
    showAiControls(story.id)
    AiCoverForegroundService.start(app)
}

/** The editable prompt draft (stage 1 result / stage 2 input). */
internal fun ScreenHost.addAiCoverPromptDraftCard(
    container: LinearLayout,
    story: Story,
    prompt: String,
) {
    val colors = ThemeManager.colors
    val isBusy = storyOperation?.storyId == story.id
    val cardView =
        container.card {
            addView(
                makeBadge(context, "Image prompt · draft", colors.tertiaryContainer, colors.onTertiaryContainer),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(Space.SM)
                },
            )
            val field =
                EditText(context).apply {
                    applyInputStyle(
                        "Image prompt",
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                        singleLine = false,
                    )
                    setText(prompt)
                    setSelection(prompt.length)
                    minLines = 4
                }
            addView(field)
            text(
                "Repainting after an edit re-bills only the image call.",
                Type.BODY_SMALL,
                colors.onSurfaceVariant,
            ).apply { setPadding(0, dp(Space.XS), 0, dp(Space.SM)) }
            latestAiOperationCostLine(repository.getAiUsageLedger(), story.id, AI_FEATURE_COVER_PROMPT)?.let { cost ->
                text(cost, Type.LABEL_MEDIUM, colors.tertiary).apply {
                    setPadding(0, 0, 0, dp(Space.SM))
                }
            }
            row {
                button("Generate Image", Btn.FILLED, R.drawable.wna_auto_awesome, enabled = !isBusy) {
                    generateAiCoverImageDraft(story, field.text.toString())
                }
                button("Discard", Btn.TEXT) { discardAiCoverPromptDraft(story) }
            }
        }
    container.addView(cardView)
}

/** Stage 1: writes the image prompt only; a fresh prompt invalidates any painted preview. */
internal fun ScreenHost.startAiCoverPromptDraft(story: Story) {
    startAiCoverJob(story, "Writing image prompt...") { coordinator -> coordinator.startPromptDraft(story.id) }
}

/**
 * Stage 2 — the billable image call; confirms when replacing a pending preview. [prompt] is the
 * field's current text so edits survive re-renders.
 */
internal fun ScreenHost.generateAiCoverImageDraft(
    story: Story,
    prompt: String,
) {
    val model = repository.getAiSettings().imageModel
    val hasPendingDraft = aiControlsScreenState.coverDrafts[story.id] != null
    if (!hasPendingDraft) {
        startAiCoverImageDraft(story, prompt)
        return
    }
    val message =
        "Generate the image with $model? This calls OpenRouter and uses your API credits. " +
            "The pending preview will be replaced."
    confirm(message, confirmLabel = "Generate") { startAiCoverImageDraft(story, prompt) }
}

internal fun ScreenHost.startAiCoverImageDraft(
    story: Story,
    prompt: String,
) {
    startAiCoverJob(
        story,
        "Painting cover...",
        onAccepted = {
            // Persist the field content with the job so the prompt survives a mid-paint process
            // death. Persisting the prompt drops the disk preview, so drop its in-memory mirror too.
            aiControlsScreenState.coverPrompts[story.id] = prompt
            aiControlsScreenState.coverDrafts.remove(story.id)
            scope.launch { repository.saveAiCoverPromptDraft(story.id, prompt) }
        },
    ) { coordinator -> coordinator.startImageDraft(story.id, prompt) }
}

internal fun ScreenHost.discardAiCoverPromptDraft(story: Story) {
    aiControlsScreenState.coverPrompts.remove(story.id)
    scope.launch { repository.deleteAiCoverDraft(story.id) }
    toast("Prompt discarded")
    showAiControls(story.id)
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
