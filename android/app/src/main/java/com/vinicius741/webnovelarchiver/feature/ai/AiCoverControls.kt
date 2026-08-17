package com.vinicius741.webnovelarchiver.feature.ai

import android.graphics.BitmapFactory
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.ai.AiCoverDraft
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.data.repository.clearAiCover
import com.vinicius741.webnovelarchiver.data.repository.setAiCover
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.feature.details.makeStoryOperationSlot
import com.vinicius741.webnovelarchiver.feature.details.renderStoryOperationProgress
import com.vinicius741.webnovelarchiver.feature.story.showCoverZoomDialog
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.navigation.StoryOperationKind
import com.vinicius741.webnovelarchiver.navigation.StoryOperationState
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.button
import com.vinicius741.webnovelarchiver.ui.card
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.coverImage
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.fullButton
import com.vinicius741.webnovelarchiver.ui.makeBadge
import com.vinicius741.webnovelarchiver.ui.roundCorners
import com.vinicius741.webnovelarchiver.ui.row
import com.vinicius741.webnovelarchiver.ui.spacer
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import timber.log.Timber

/*
 * Cover Art section of the AI Controls screen. Mirrors the description flow: a billable two-stage
 * draft (the description model writes an image prompt from the novel's material, the image model
 * paints it) that is previewed here and persisted only on Apply. The source cover URL is never
 * modified, so "Use source cover" can always restore it.
 */

/** Current-state card: the applied AI cover (or an explanatory line), plus generate/revert actions. */
internal fun ScreenHost.addAiCoverCard(
    container: LinearLayout,
    story: Story,
    generating: StoryOperationState?,
) {
    val colors = ThemeManager.colors
    val hasAiCover = !story.aiCoverPath.isNullOrBlank()
    val hasSourceCover = !story.coverUrl.isNullOrBlank()
    // Same gating as descriptions: no context chapters means nothing to feed the text model.
    val canGenerate = story.isArchived != true && story.chapters.any { it.downloaded }
    val isBusy = storyOperation?.storyId == story.id
    val cardView =
        container.card {
            if (hasAiCover) {
                addView(
                    makeBadge(context, "AI-generated", colors.tertiaryContainer, colors.onTertiaryContainer),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(Space.SM)
                    },
                )
                addView(
                    coverImage(story, widthDp = 120, heightDp = 180, tapToOpen = true).apply {
                        (layoutParams as LinearLayout.LayoutParams).marginEnd = 0
                        (layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL
                    },
                )
            } else {
                text(
                    if (hasSourceCover) {
                        "The novel currently shows its source cover."
                    } else {
                        "No cover on file. Generate one to give the novel a cover."
                    },
                    Type.BODY_SMALL,
                    colors.onSurfaceVariant,
                )
            }
            if (canGenerate) {
                spacer(Space.SM)
                fullButton(
                    label =
                        when {
                            generating != null -> "Generating..."
                            hasAiCover -> "Regenerate Cover"
                            else -> "Generate Cover with AI"
                        },
                    variant = Btn.FILLED,
                    icon = R.drawable.wna_auto_awesome,
                    enabled = generating == null && !isBusy,
                    bottomMarginDp = 0,
                ) { generateAiCoverDraft(story) }
                if (hasAiCover) {
                    button(
                        if (hasSourceCover) "Use source cover" else "Remove AI cover",
                        Btn.TEXT,
                    ) { revertAiCover(story) }
                }
            } else {
                text(
                    if (story.isArchived == true) {
                        "Archived snapshots are read-only — AI generation is disabled."
                    } else {
                        "Download at least one chapter before generating an AI cover."
                    },
                    Type.BODY_SMALL,
                    colors.onSurfaceVariant,
                ).apply { setPadding(0, dp(Space.SM), 0, 0) }
            }
        }
    container.addView(cardView)
}

/** The generated cover draft (image + the prompt that produced it) with Apply/Discard actions. */
internal fun ScreenHost.addAiCoverDraftPreviewCard(
    container: LinearLayout,
    story: Story,
    draft: AiCoverDraft,
) {
    val colors = ThemeManager.colors
    val cardView =
        container.card {
            addView(
                makeBadge(context, "AI-generated · preview", colors.tertiaryContainer, colors.onTertiaryContainer),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(Space.SM)
                },
            )
            val bitmap = BitmapFactory.decodeByteArray(draft.bytes, 0, draft.bytes.size)
            if (bitmap != null) {
                addView(
                    ImageView(context).apply {
                        contentDescription = "Generated cover preview"
                        setImageBitmap(bitmap)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setBackgroundColor(colors.surfaceVariant)
                        roundCorners(ThemeManager.shapes.cardRadius.toFloat() * 0.7f)
                        layoutParams =
                            LinearLayout.LayoutParams(dp(150), dp(225)).apply {
                                gravity = Gravity.CENTER_HORIZONTAL
                            }
                        setOnClickListener { showCoverZoomDialog(bitmap, story.title) }
                    },
                )
                spacer(Space.SM)
                text("Image prompt", Type.LABEL_MEDIUM, colors.onSurfaceVariant)
                text(draft.prompt, Type.BODY_SMALL, colors.onSurfaceVariant).apply {
                    setLineSpacing(dp(Space.XS).toFloat(), 1f)
                }
                text(
                    "Preview — nothing is saved yet. Apply replaces the novel's current cover.",
                    Type.BODY_SMALL,
                    colors.onSurfaceVariant,
                ).apply { setPadding(0, dp(Space.XS), 0, dp(Space.SM)) }
                row {
                    button("Apply", Btn.FILLED, R.drawable.wna_check) { applyAiCoverDraft(story, draft) }
                    button("Discard", Btn.TEXT) { discardAiCoverDraft(story) }
                }
            } else {
                text(
                    "The model returned an image the app could not decode. Discard and try again.",
                    Type.BODY_SMALL,
                    colors.onSurfaceVariant,
                )
                spacer(Space.SM)
                button("Discard", Btn.TEXT) { discardAiCoverDraft(story) }
            }
        }
    container.addView(cardView)
}

/**
 * Starts cover draft generation. Generating over an applied AI cover or a pending preview asks for
 * confirmation first — every generation makes two billable OpenRouter calls on the user's key.
 */
internal fun ScreenHost.generateAiCoverDraft(story: Story) {
    val model = repository.getAiSettings().imageModel
    val hasApplied = story.aiCoverPath != null
    val hasPendingDraft = aiControlsScreenState.coverDrafts[story.id] != null
    if (!hasApplied && !hasPendingDraft) {
        startAiCoverDraft(story)
        return
    }
    val message =
        "Generate a new AI cover with $model? This makes two OpenRouter calls (image prompt + image) and uses your API credits." +
            (if (hasPendingDraft) " The pending preview will be replaced." else "")
    confirm(message, confirmLabel = "Generate") { startAiCoverDraft(story) }
}

@Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
private fun ScreenHost.startAiCoverDraft(story: Story) {
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

/**
 * Writes the progress message straight into [storyOperation] and patches whichever progress surface
 * is visible — same in-place strategy as the description flow so the user is never pulled off this
 * screen by a full Details rebuild.
 */
private fun ScreenHost.patchAiCoverProgress(
    storyId: String,
    message: String,
) {
    val operation = storyOperation?.takeIf { it.storyId == storyId && it.kind == StoryOperationKind.AI_COVER } ?: return
    val next = operation.copy(message = message)
    storyOperation = next
    detailsOperationSlot?.let { renderStoryOperationProgress(it, next) }
    if (frameIsAiControls(storyId)) showAiControls(storyId)
}

private fun ScreenHost.finishAiCoverOperation(storyId: String) {
    if (storyOperation?.storyId == storyId && storyOperation?.kind == StoryOperationKind.AI_COVER) {
        storyOperation = null
        detailsOperationSlot = null
    }
}

internal fun ScreenHost.applyAiCoverDraft(
    story: Story,
    draft: AiCoverDraft,
) {
    scope.launch {
        repository.setAiCover(story.id, draft.bytes, draft.mediaType)
        aiControlsScreenState.coverDrafts.remove(story.id)
        toast("AI cover applied")
        showAiControls(story.id)
    }
}

internal fun ScreenHost.discardAiCoverDraft(story: Story) {
    aiControlsScreenState.coverDrafts.remove(story.id)
    toast("Draft discarded")
    showAiControls(story.id)
}

/** Deletes the generated cover so the story falls back to its (untouched) source cover URL. */
internal fun ScreenHost.revertAiCover(story: Story) {
    val hasSourceCover = !story.coverUrl.isNullOrBlank()
    val message =
        if (hasSourceCover) {
            "Use the novel's source cover again? The generated cover image will be deleted."
        } else {
            "Remove the generated cover? The novel will have no cover."
        }
    confirm(message, confirmLabel = if (hasSourceCover) "Use source cover" else "Remove") {
        scope.launch {
            repository.clearAiCover(story.id)
            aiControlsScreenState.coverDrafts.remove(story.id)
            toast(if (hasSourceCover) "Source cover restored" else "AI cover removed")
            showAiControls(story.id)
        }
    }
}
