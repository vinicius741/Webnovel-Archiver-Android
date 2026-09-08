package com.vinicius741.webnovelarchiver.feature.ai

import android.graphics.BitmapFactory
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.ai.AiCoverDraft
import com.vinicius741.webnovelarchiver.data.repository.deleteAiCoverDraft
import com.vinicius741.webnovelarchiver.data.repository.getAiUsageLedger
import com.vinicius741.webnovelarchiver.data.repository.setAiCover
import com.vinicius741.webnovelarchiver.data.repository.setShowAiCover
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.navigation.StoryOperationState
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.button
import com.vinicius741.webnovelarchiver.ui.card
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.fullButton
import com.vinicius741.webnovelarchiver.ui.makeBadge
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.row
import com.vinicius741.webnovelarchiver.ui.spacer
import com.vinicius741.webnovelarchiver.ui.styledCheckBox
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

/*
 * Cover Art section of the AI Controls screen: applied Source|AI comparison, show-AI preference,
 * generation-mode checkbox, preview/apply/discard/delete. The billable generation flows live in
 * AiCoverGeneration.kt; before/after thumbnails in AiCoverCompareUi.kt. The source cover URL is
 * never modified; deleting the generated image only reclaims the choice entirely.
 */

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
                addAppliedCoverCompareRow(this, story)
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
            if (hasAiCover && hasSourceCover && story.isArchived != true) {
                spacer(Space.SM)
                addAiCoverDisplayToggleRow(this, story)
            }
            if (canGenerate) {
                spacer(Space.SM)
                addAiContextChaptersRow(this, story, forCover = true)
                spacer(Space.SM)
                addAiCoverModeRow(this, story, oneStep)
                spacer(Space.SM)
                fullButton(
                    label =
                        when {
                            generating != null -> "Generating..."
                            oneStep ->
                                if (hasAiCover) "Try a new cover" else "Generate Cover with AI"
                            hasPromptDraft -> "Try a new AI prompt"
                            else -> "Generate Prompt with AI"
                        },
                    variant = Btn.FILLED,
                    icon = R.drawable.wna_auto_awesome,
                    enabled = generating == null && !isBusy,
                    bottomMarginDp = if (hasAiCover) Space.MD else 0,
                ) { generateAiCoverDraft(story) }
                if (hasAiCover && hasSourceCover) {
                    fullButton(
                        label = "Use source cover",
                        variant = Btn.TEXT,
                        enabled = generating == null && !isBusy,
                        bottomMarginDp = 0,
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
            if (canGenerate) {
                fullButton("Write your own prompt", Btn.TEXT) { editAiCoverPrompt(story, "") }
            }
            addAiCoverVersions(this, story)
        }
    container.addView(cardView)
}

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

/** "Generate prompt + image in one step" preference; persists into AiSettings.coverOneStep and
 *  re-renders so the button/hint follow the new mode. Unchecked = staged generation (prompt
 *  editor in AiCoverGeneration.kt). */
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

/** Compares a saved candidate with the active cover before changing the display choice. */
internal fun ScreenHost.addAiCoverDraftPreviewCard(
    container: LinearLayout,
    story: Story,
    draft: AiCoverDraft,
    savedVersion: Boolean = false,
    currentVersion: Boolean = false,
    onDone: () -> Unit = {},
) {
    val editable = story.isArchived != true && aiCoverOperationFor(story.id) == null
    val colors = ThemeManager.colors
    val cardView =
        container.card {
            addView(
                makeBadge(
                    context,
                    if (savedVersion) "Saved cover" else "New cover · saved to history",
                    colors.tertiaryContainer,
                    colors.onTertiaryContainer,
                ),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(Space.SM)
                },
            )
            // R24: the preview bitmap is decoded off main, sampled to preview size, with a
            // dimension sanity check before any large allocation.
            val previewSlot = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            addView(previewSlot)
            var applyButton: Button? = null
            scope.launch(kotlinx.coroutines.Dispatchers.Default) {
                val bitmap = decodeSampledDraftPreview(draft.bytes)
                app.runOnUiThread {
                    if (app.isFinishing || app.isDestroyed) return@runOnUiThread
                    previewSlot.removeAllViews()
                    if (bitmap != null) {
                        addAiCoverDraftCompareRow(previewSlot, story, bitmap, currentVersion)
                        applyButton?.apply {
                            isEnabled = editable && !currentVersion
                            alpha = if (editable && !currentVersion) 1f else 0.4f
                        }
                    } else {
                        previewSlot.addView(
                            makeText(
                                app,
                                "The model returned an image the app could not decode. Discard and try again.",
                                Type.BODY_SMALL,
                                colors.onSurfaceVariant,
                            ),
                        )
                        // Undecodable bytes must not become the permanent cover — Discard is the
                        // only outcome, so Apply is hidden once the decode verdict is in.
                        applyButton?.visibility = View.GONE
                    }
                }
            }
            spacer(Space.SM)
            text("Image prompt", Type.LABEL_MEDIUM, colors.onSurfaceVariant)
            text(
                draft.prompt.ifBlank { "The prompt for this older cover is unavailable." },
                Type.BODY_SMALL,
                colors.onSurfaceVariant,
            ).apply {
                setLineSpacing(dp(Space.XS).toFloat(), 1f)
            }
            text(
                if (currentVersion) "This is the cover currently in use." else "Your current cover stays in use until you choose this one.",
                Type.BODY_SMALL,
                colors.onSurfaceVariant,
            ).apply { setPadding(0, dp(Space.XS), 0, dp(Space.SM)) }
            val costLine =
                if (savedVersion) {
                    null
                } else {
                    latestAiOperationCostLine(repository.getAiUsageLedger(), story.id, AI_FEATURE_COVER_IMAGE)
                }
            costLine?.let { cost ->
                text(cost, Type.LABEL_MEDIUM, colors.tertiary).apply {
                    setPadding(0, 0, 0, dp(Space.SM))
                }
            }
            row {
                applyButton =
                    button(if (currentVersion) "Current cover" else "Use this cover", Btn.FILLED, R.drawable.wna_check, enabled = false) {
                        applyAiCoverDraft(story, draft, clearPreview = !savedVersion)
                        onDone()
                    }
            }
            if (editable && story.chapters.any { it.downloaded }) {
                fullButton(if (draft.prompt.isBlank()) "Write a new prompt" else "Edit prompt / try again", Btn.OUTLINED) {
                    onDone()
                    editAiCoverPrompt(story, draft.prompt)
                }
            }
            if (!savedVersion) {
                fullButton("Close preview", Btn.TEXT, enabled = aiCoverOperationFor(story.id) == null) { discardAiCoverDraft(story) }
            }
        }
    if (savedVersion) {
        cardView.background = null
        cardView.setPadding(0, 0, 0, 0)
    }
    container.addView(cardView)
}

/**
 * Decodes a preview-sized bitmap (R24): bounds are validated against a dimension sanity cap before
 * the sampled allocation, so a small-compressed/huge-dimension payload cannot claim unbounded
 * memory and the main thread never performs the decode.
 */
internal fun decodeSampledDraftPreview(
    bytes: ByteArray,
    targetPx: Int = 1024,
): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    if (bounds.outWidth > MAX_PREVIEW_DIMENSION_PX || bounds.outHeight > MAX_PREVIEW_DIMENSION_PX) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) {
        sample *= 2
    }
    return BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )
}

private const val MAX_PREVIEW_DIMENSION_PX = 8192

internal fun ScreenHost.applyAiCoverDraft(
    story: Story,
    draft: AiCoverDraft,
    clearPreview: Boolean = true,
) {
    if (story.isArchived == true || aiCoverOperationFor(story.id) != null) return
    scope.launch {
        coverUiAttempt {
            repository.setAiCover(story.id, draft.bytes, draft.mediaType)
            if (clearPreview) {
                repository.deleteAiCoverDraft(story.id)
                aiControlsScreenState.coverDrafts.remove(story.id)
                aiControlsScreenState.coverPrompts.remove(story.id)
            }
            toast("AI cover applied")
            if (frameIsAiControls(story.id)) showAiControls(story.id)
        }
    }
}

internal fun ScreenHost.discardAiCoverDraft(story: Story) {
    scope.launch {
        coverUiAttempt {
            repository.deleteAiCoverDraft(story.id)
            aiControlsScreenState.coverDrafts.remove(story.id)
            aiControlsScreenState.coverPrompts.remove(story.id)
            toast("Cover kept in saved covers")
            if (frameIsAiControls(story.id)) showAiControls(story.id)
        }
    }
}

/** Selects the source cover while retaining every generated version. */
internal fun ScreenHost.revertAiCover(story: Story) {
    scope.launch {
        coverUiAttempt {
            repository.setShowAiCover(story.id, false)
            toast("Source cover selected")
            if (frameIsAiControls(story.id)) showAiControls(story.id)
        }
    }
}
