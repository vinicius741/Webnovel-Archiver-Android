package com.vinicius741.webnovelarchiver.feature.ai

import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.feature.details.renderStoryOperationProgress
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import timber.log.Timber

/*
 * The billable cover-art generation flows of the AI Controls screen, split out of
 * AiCoverControls.kt to respect the file-size budget. The one-shot flow runs both OpenRouter
 * stages (prompt + image) in a single run; the staged flow stops after the prompt, shows it in an
 * editable draft card, and paints only when the user asks — re-painting after an edit re-bills
 * just the image call. Both modes share the AI_COVER story operation slot, so they can never run
 * concurrently for a story.
 */

/**
 * Starts cover generation in the configured mode. One-step runs both billable calls together;
 * staged mode writes only the image prompt here (the image is painted from the editable prompt
 * card below). Generating over an applied AI cover or pending drafts asks for confirmation
 * first — every call bills the user's OpenRouter key.
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

@Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
internal fun ScreenHost.startAiCoverDraft(story: Story) {
    if (storyOperation != null) {
        toast("Please wait for the current operation to finish")
        return
    }
    storyOperation = StoryOperationState(story.id, StoryOperationKind.AI_COVER, "Generating cover...")
    detailsOperationSlot = null
    showAiControls(story.id)
    scope.launch {
        try {
            val draft =
                app.appContainer.aiCoverArtEngine.draft(story.id) { message ->
                    app.runOnUiThread { patchAiCoverProgress(story.id, message) }
                }
            aiControlsScreenState.coverDrafts[story.id] = draft
            // The one-shot flow bypasses the prompt editor, so any staged prompt draft is stale.
            aiControlsScreenState.coverPrompts.remove(story.id)
            finishAiCoverOperation(story.id)
            if (frameIsAiControls(story.id)) {
                showAiControls(story.id)
            } else {
                toast("AI cover ready — preview it under More options → AI Controls")
                rerenderDetailsIfVisible(story.id)
            }
        } catch (error: Throwable) {
            // The engine throws user-presentable messages; rethrow cancellation untouched.
            if (error is CancellationException) throw error
            Timber.w(error, "AI cover generation failed for %s", story.id)
            finishAiCoverOperation(story.id)
            toast(error.message ?: "AI cover failed")
            if (frameIsAiControls(story.id)) showAiControls(story.id) else rerenderDetailsIfVisible(story.id)
        }
    }
}

/** The editable prompt draft (stage 1 result / stage 2 input) with Generate Image / Discard actions. */
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
                "Edit the prompt, then generate the image — repainting after an edit re-bills only the image call.",
                Type.BODY_SMALL,
                colors.onSurfaceVariant,
            ).apply { setPadding(0, dp(Space.XS), 0, dp(Space.SM)) }
            row {
                button("Generate Image", Btn.FILLED, R.drawable.wna_auto_awesome, enabled = !isBusy) {
                    generateAiCoverImageDraft(story, field.text.toString())
                }
                button("Discard", Btn.TEXT) { discardAiCoverPromptDraft(story) }
            }
        }
    container.addView(cardView)
}

/**
 * Starts stage 1: writes the image prompt only. Generating over pending work was already
 * confirmed in [generateAiCoverDraft]; a fresh prompt invalidates any preview painted from the
 * previous one.
 */
@Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
internal fun ScreenHost.startAiCoverPromptDraft(story: Story) {
    if (storyOperation != null) {
        toast("Please wait for the current operation to finish")
        return
    }
    storyOperation = StoryOperationState(story.id, StoryOperationKind.AI_COVER, "Writing image prompt...")
    detailsOperationSlot = null
    showAiControls(story.id)
    scope.launch {
        try {
            val prompt =
                app.appContainer.aiCoverArtEngine.draftPrompt(story.id) { message ->
                    app.runOnUiThread { patchAiCoverProgress(story.id, message) }
                }
            aiControlsScreenState.coverPrompts[story.id] = prompt
            aiControlsScreenState.coverDrafts.remove(story.id)
            finishAiCoverOperation(story.id)
            if (frameIsAiControls(story.id)) {
                showAiControls(story.id)
            } else {
                toast("Image prompt ready — edit it under More options → AI Controls")
                rerenderDetailsIfVisible(story.id)
            }
        } catch (error: Throwable) {
            // The engine throws user-presentable messages; rethrow cancellation untouched.
            if (error is CancellationException) throw error
            Timber.w(error, "AI cover prompt generation failed for %s", story.id)
            finishAiCoverOperation(story.id)
            toast(error.message ?: "AI cover failed")
            if (frameIsAiControls(story.id)) showAiControls(story.id) else rerenderDetailsIfVisible(story.id)
        }
    }
}

/**
 * Starts stage 2 with confirmation when it replaces a pending preview — the image call is the
 * billable one. [prompt] is the field's current content, so edits survive the re-render.
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

@Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
internal fun ScreenHost.startAiCoverImageDraft(
    story: Story,
    prompt: String,
) {
    if (storyOperation != null) {
        toast("Please wait for the current operation to finish")
        return
    }
    // The field content — not the stored draft — is the source of truth while the user edits.
    aiControlsScreenState.coverPrompts[story.id] = prompt
    storyOperation = StoryOperationState(story.id, StoryOperationKind.AI_COVER, "Painting cover...")
    detailsOperationSlot = null
    showAiControls(story.id)
    scope.launch {
        try {
            val draft =
                app.appContainer.aiCoverArtEngine.draftImage(story.id, prompt) { message ->
                    app.runOnUiThread { patchAiCoverProgress(story.id, message) }
                }
            aiControlsScreenState.coverDrafts[story.id] = draft
            // Keep the editor in sync with the cleaned prompt the model actually received.
            aiControlsScreenState.coverPrompts[story.id] = draft.prompt
            finishAiCoverOperation(story.id)
            if (frameIsAiControls(story.id)) {
                showAiControls(story.id)
            } else {
                toast("AI cover ready — preview it under More options → AI Controls")
                rerenderDetailsIfVisible(story.id)
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Timber.w(error, "AI cover image generation failed for %s", story.id)
            finishAiCoverOperation(story.id)
            toast(error.message ?: "AI cover failed")
            if (frameIsAiControls(story.id)) showAiControls(story.id) else rerenderDetailsIfVisible(story.id)
        }
    }
}

internal fun ScreenHost.discardAiCoverPromptDraft(story: Story) {
    aiControlsScreenState.coverPrompts.remove(story.id)
    toast("Prompt discarded")
    showAiControls(story.id)
}

/**
 * Writes the progress message straight into [storyOperation] and patches whichever progress surface
 * is visible — same in-place strategy as the description flow so the user is never pulled off this
 * screen by a full Details rebuild. Shared by the one-shot and staged flows.
 */
internal fun ScreenHost.patchAiCoverProgress(
    storyId: String,
    message: String,
) {
    val operation = storyOperation?.takeIf { it.storyId == storyId && it.kind == StoryOperationKind.AI_COVER } ?: return
    val next = operation.copy(message = message)
    storyOperation = next
    detailsOperationSlot?.let { renderStoryOperationProgress(it, next) }
    if (frameIsAiControls(storyId)) showAiControls(storyId)
}

internal fun ScreenHost.finishAiCoverOperation(storyId: String) {
    if (storyOperation?.storyId == storyId && storyOperation?.kind == StoryOperationKind.AI_COVER) {
        storyOperation = null
        detailsOperationSlot = null
    }
}
