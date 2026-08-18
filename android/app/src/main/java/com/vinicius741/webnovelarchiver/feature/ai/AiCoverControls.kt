package com.vinicius741.webnovelarchiver.feature.ai

import android.graphics.BitmapFactory
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.ai.AiCoverDraft
import com.vinicius741.webnovelarchiver.data.repository.clearAiCover
import com.vinicius741.webnovelarchiver.data.repository.coverFile
import com.vinicius741.webnovelarchiver.data.repository.deleteAiCoverDraft
import com.vinicius741.webnovelarchiver.data.repository.setAiCover
import com.vinicius741.webnovelarchiver.data.repository.setShowAiCover
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.feature.story.showCoverZoomDialog
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.navigation.StoryOperationState
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.button
import com.vinicius741.webnovelarchiver.ui.card
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.fullButton
import com.vinicius741.webnovelarchiver.ui.loadImage
import com.vinicius741.webnovelarchiver.ui.makeBadge
import com.vinicius741.webnovelarchiver.ui.makeCover
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.roundCorners
import com.vinicius741.webnovelarchiver.ui.row
import com.vinicius741.webnovelarchiver.ui.spacer
import com.vinicius741.webnovelarchiver.ui.styledCheckBox
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

/*
 * Cover Art section of the AI Controls screen: the current-state card, the show-AI/source cover
 * preference, the generation-mode checkbox, and the preview/apply/discard/delete actions. The
 * billable generation flows themselves (one-shot and staged, with the editable prompt in between)
 * live in AiCoverGeneration.kt. The source cover URL is never modified, and once an AI cover is
 * applied the user can switch between it and the source cover at any time — deleting the
 * generated image is only for reclaiming the choice entirely.
 */

/** Current-state card: the applied AI cover, the show-AI/source preference, and the generate/delete actions. */
internal fun ScreenHost.addAiCoverCard(
    container: LinearLayout,
    story: Story,
    generating: StoryOperationState?,
) {
    val colors = ThemeManager.colors
    val hasAiCover = !story.aiCoverPath.isNullOrBlank()
    val hasSourceCover = !story.coverUrl.isNullOrBlank()
    val oneStep = repository.getAiSettings().coverOneStep
    val hasPromptDraft = aiControlsScreenState.coverPrompts[story.id] != null
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
                addAppliedAiCoverThumbnail(this, story)
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
            if (hasAiCover && hasSourceCover) {
                spacer(Space.SM)
                addAiCoverDisplayToggleRow(this, story)
            }
            if (canGenerate) {
                spacer(Space.SM)
                addAiCoverModeRow(this, story, oneStep)
                if (!oneStep) {
                    text(
                        "Staged: the prompt is written first and can be edited before the image call.",
                        Type.BODY_SMALL,
                        colors.onSurfaceVariant,
                    ).apply { setPadding(0, dp(Space.XS), 0, 0) }
                }
                spacer(Space.SM)
                fullButton(
                    label =
                        when {
                            generating != null -> "Generating..."
                            oneStep ->
                                if (hasAiCover) "Regenerate Cover" else "Generate Cover with AI"
                            hasPromptDraft -> "Regenerate Prompt"
                            else -> "Generate Prompt with AI"
                        },
                    variant = Btn.FILLED,
                    icon = R.drawable.wna_auto_awesome,
                    enabled = generating == null && !isBusy,
                    bottomMarginDp = 0,
                ) { generateAiCoverDraft(story) }
                if (hasAiCover) {
                    button("Delete AI cover", Btn.TEXT) { revertAiCover(story) }
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

/**
 * The applied AI cover thumbnail — always the generated file itself, never the toggle-aware
 * [com.vinicius741.webnovelarchiver.ui.coverImage], so this card keeps showing the AI cover even
 * while the app displays the source one.
 */
private fun ScreenHost.addAppliedAiCoverThumbnail(
    container: LinearLayout,
    story: Story,
) {
    val file = repository.coverFile(story) ?: return
    val cover = makeCover(app, 120, 180)
    cover.layoutParams =
        (cover.layoutParams as LinearLayout.LayoutParams).apply {
            marginEnd = 0
            gravity = Gravity.CENTER_HORIZONTAL
        }
    loadImage(file, cover)
    cover.setOnClickListener { showCoverZoomDialog(file, story.title) }
    container.addView(cover)
}

/** "Show AI cover" preference row; persists via [com.vinicius741.webnovelarchiver.data.repository.setShowAiCover]. */
private fun ScreenHost.addAiCoverDisplayToggleRow(
    container: LinearLayout,
    story: Story,
) {
    var toggle: CheckBox? = null
    container.row {
        addView(
            makeText(context, "Show AI cover", Type.BODY_MEDIUM, ThemeManager.colors.onSurface),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val checkBox =
            CheckBox(context).apply {
                text = ""
                isChecked = story.showAiCover
            }
        styledCheckBox(checkBox)
        addView(checkBox)
        toggle = checkBox
    }
    toggle!!.setOnCheckedChangeListener { _, checked ->
        scope.launch { repository.setShowAiCover(story.id, checked) }
    }
}

/**
 * "Generate prompt + image in one step" preference row; persists into
 * [com.vinicius741.webnovelarchiver.domain.model.AiSettings.coverOneStep] and re-renders so the
 * generate button and staged hint follow the new mode immediately. Unchecked = staged generation,
 * whose prompt editor lives in AiCoverGeneration.kt.
 */
private fun ScreenHost.addAiCoverModeRow(
    container: LinearLayout,
    story: Story,
    oneStep: Boolean,
) {
    var toggle: CheckBox? = null
    container.row {
        addView(
            makeText(context, "Generate prompt + image in one step", Type.BODY_MEDIUM, ThemeManager.colors.onSurface),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val checkBox =
            CheckBox(context).apply {
                text = ""
                isChecked = oneStep
            }
        styledCheckBox(checkBox)
        addView(checkBox)
        toggle = checkBox
    }
    toggle!!.setOnCheckedChangeListener { _, checked ->
        scope.launch {
            repository.saveAiSettings(repository.getAiSettings().copy(coverOneStep = checked))
            if (frameIsAiControls(story.id)) showAiControls(story.id)
        }
    }
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

internal fun ScreenHost.applyAiCoverDraft(
    story: Story,
    draft: AiCoverDraft,
) {
    scope.launch {
        repository.setAiCover(story.id, draft.bytes, draft.mediaType)
        repository.deleteAiCoverDraft(story.id)
        aiControlsScreenState.coverDrafts.remove(story.id)
        aiControlsScreenState.coverPrompts.remove(story.id)
        toast("AI cover applied")
        showAiControls(story.id)
    }
}

internal fun ScreenHost.discardAiCoverDraft(story: Story) {
    aiControlsScreenState.coverDrafts.remove(story.id)
    scope.launch { repository.deleteAiCoverDraft(story.id) }
    toast("Draft discarded")
    showAiControls(story.id)
}

/**
 * Deletes the generated cover file and record. Switching which cover the app shows is the
 * "Show AI cover" toggle; this is only for giving up the generated image entirely — the untouched
 * source [Story.coverUrl] then applies again.
 */
internal fun ScreenHost.revertAiCover(story: Story) {
    val hasSourceCover = !story.coverUrl.isNullOrBlank()
    val message =
        if (hasSourceCover) {
            "Delete the generated cover image? The novel will go back to its source cover."
        } else {
            "Remove the generated cover? The novel will have no cover."
        }
    confirm(message, confirmLabel = "Delete") {
        scope.launch {
            repository.clearAiCover(story.id)
            repository.deleteAiCoverDraft(story.id)
            aiControlsScreenState.coverDrafts.remove(story.id)
            aiControlsScreenState.coverPrompts.remove(story.id)
            toast("AI cover deleted")
            showAiControls(story.id)
        }
    }
}
