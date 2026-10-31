package com.vinicius741.webnovelarchiver.feature.ai

import android.graphics.BitmapFactory
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.widget.doAfterTextChanged
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.ai.AiCoverDraft
import com.vinicius741.webnovelarchiver.ai.AiCoverPlanning
import com.vinicius741.webnovelarchiver.data.repository.coverFile
import com.vinicius741.webnovelarchiver.data.repository.getAiUsageLedger
import com.vinicius741.webnovelarchiver.data.repository.saveAiCoverPromptDraft
import com.vinicius741.webnovelarchiver.data.repository.setAiCover
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.navigation.StoryOperationState
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.applyInputStyle
import com.vinicius741.webnovelarchiver.ui.button
import com.vinicius741.webnovelarchiver.ui.card
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.fullButton
import com.vinicius741.webnovelarchiver.ui.makeBadge
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.row
import com.vinicius741.webnovelarchiver.ui.section
import com.vinicius741.webnovelarchiver.ui.spacer
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

/*
 * Cover tab of the AI Controls screen, top to bottom: a "New cover ready" decision card while a
 * generated cover waits for review, the strip of every cover (source + saved AI versions; tap one
 * to use it, AiCoverVersionsUi.kt), and one "Make a new cover" card whose prompt box decides the
 * flow: empty = AI writes the prompt and paints it, filled = paint exactly that prompt. The billable
 * job launchers live in AiCoverGeneration.kt. The source cover URL is never modified.
 */

internal fun ScreenHost.addAiCoverTab(
    container: LinearLayout,
    story: Story,
    generating: StoryOperationState?,
) {
    aiControlsScreenState.coverDrafts[story.id]?.let { draft -> addAiNewCoverCard(container, story, draft) }
    container.section("Covers")
    addAiCoverStrip(container, story)
    container.section("Make a new cover")
    addAiCoverMakerCard(container, story, generating)
}

/** The waiting result, side by side with the cover in use, so the choice is one tap. */
private fun ScreenHost.addAiNewCoverCard(
    container: LinearLayout,
    story: Story,
    draft: AiCoverDraft,
) {
    val editable = story.isArchived != true && aiCoverOperationFor(story.id) == null
    val colors = ThemeManager.colors
    val cardView =
        container.card {
            addView(
                makeBadge(context, "New cover ready", colors.tertiaryContainer, colors.onTertiaryContainer),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(Space.SM)
                },
            )
            // R24: the preview bitmap is decoded off main, sampled to preview size, with a
            // dimension sanity check before any large allocation.
            val previewSlot = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            addView(previewSlot)
            var useButton: Button? = null
            scope.launch(kotlinx.coroutines.Dispatchers.Default) {
                // Older app versions could apply a draft without closing it; one identical to the
                // cover in use is not news, so settle it instead of offering a no-op choice.
                val applied = repository.coverFile(story)?.takeIf { AiCoverPlanning.isAiCoverActive(story) }
                if (applied != null && runCatching { applied.readBytes() }.getOrNull()?.contentEquals(draft.bytes) == true) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        coverUiAttempt { closeAiCoverDraft(story.id, draft.prompt) }
                        if (frameIsAiControls(story.id)) showAiControls(story.id)
                    }
                    return@launch
                }
                val bitmap = decodeSampledDraftPreview(draft.bytes)
                app.runOnUiThread {
                    if (app.isFinishing || app.isDestroyed) return@runOnUiThread
                    previewSlot.removeAllViews()
                    if (bitmap != null) {
                        addAiCoverCompareRow(previewSlot, story, bitmap)
                        useButton?.apply {
                            isEnabled = editable
                            alpha = if (editable) 1f else 0.4f
                        }
                    } else {
                        previewSlot.addView(
                            makeText(
                                app,
                                "The model returned an image the app could not decode. Try generating again.",
                                Type.BODY_SMALL,
                                colors.onSurfaceVariant,
                            ),
                        )
                        // Undecodable bytes must not become the permanent cover.
                        useButton?.visibility = View.GONE
                    }
                }
            }
            spacer(Space.SM)
            latestAiOperationCostLine(repository.getAiUsageLedger(), story.id, AI_FEATURE_COVER_IMAGE)?.let { cost ->
                text(cost, Type.LABEL_MEDIUM, colors.tertiary).apply {
                    setPadding(0, 0, 0, dp(Space.SM))
                }
            }
            row {
                useButton =
                    button("Use new cover", Btn.FILLED, R.drawable.wna_check, enabled = false) {
                        resolveAiCoverDraft(story, draft, use = true)
                    }
                button("Keep current", Btn.TEXT, enabled = editable) { resolveAiCoverDraft(story, draft, use = false) }
            }
        }
    container.addView(cardView)
}

/**
 * Closes the "New cover ready" decision. The image is already in Saved covers either way; its
 * prompt stays in the prompt box so the next attempt can start from it.
 */
internal fun ScreenHost.resolveAiCoverDraft(
    story: Story,
    draft: AiCoverDraft,
    use: Boolean,
) {
    if (story.isArchived == true || aiCoverOperationFor(story.id) != null) return
    scope.launch {
        coverUiAttempt {
            if (use) repository.setAiCover(story.id, draft.bytes, draft.mediaType)
            closeAiCoverDraft(story.id, draft.prompt)
            if (use) toast("AI cover applied")
            if (frameIsAiControls(story.id)) showAiControls(story.id)
        }
    }
}

/** Turns a pending image draft back into a prompt-only draft (the store keeps the image in history). */
internal suspend fun ScreenHost.closeAiCoverDraft(
    storyId: String,
    prompt: String,
) {
    if (aiControlsScreenState.coverDrafts.remove(storyId) == null) return
    if (prompt.isNotBlank()) {
        repository.saveAiCoverPromptDraft(storyId, prompt)
        aiControlsScreenState.coverPrompts[storyId] = prompt
    }
}

/** The single generation card: chapters, prompt box, the two actions, progress, and the models used. */
private fun ScreenHost.addAiCoverMakerCard(
    container: LinearLayout,
    story: Story,
    generating: StoryOperationState?,
) {
    val colors = ThemeManager.colors
    // Same gating as descriptions: no context chapters means nothing to feed the text model.
    val canGenerate = story.isArchived != true && story.chapters.any { it.downloaded }
    if (!canGenerate) {
        container.addView(
            container.card {
                text(
                    if (story.isArchived == true) {
                        "Archived snapshots are read-only — AI generation is disabled."
                    } else {
                        "Download at least one chapter before generating an AI cover."
                    },
                    Type.BODY_SMALL,
                    colors.onSurfaceVariant,
                )
            },
        )
        return
    }
    val isBusy = storyOperation?.storyId == story.id || generating != null
    val prompt = aiControlsScreenState.coverPrompts[story.id] ?: aiControlsScreenState.coverDrafts[story.id]?.prompt.orEmpty()
    val cardView =
        container.card {
            addAiContextChaptersRow(this, story, forCover = true)
            spacer(Space.MD)
            val field =
                EditText(context).apply {
                    applyInputStyle(
                        "Image prompt (optional)",
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                        singleLine = false,
                    )
                    setText(prompt)
                    minLines = 3
                    maxLines = 10
                    isEnabled = !isBusy
                    contentDescription = "Image prompt"
                }
            addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val hint =
                text("", Type.BODY_SMALL, colors.onSurfaceVariant).apply {
                    setPadding(0, dp(Space.SM), 0, dp(Space.SM))
                }
            var clearButton: Button? = null
            var generateButton: Button? = null

            fun syncPromptState() {
                val blank = field.text.isNullOrBlank()
                hint.text =
                    if (blank) {
                        "Leave it empty and AI writes a prompt from the chapters, then paints it."
                    } else {
                        "Paints exactly this prompt. Only the image call uses credits."
                    }
                clearButton?.visibility = if (blank || isBusy) View.GONE else View.VISIBLE
                generateButton?.text = if (blank) "Generate cover" else "Generate from prompt"
            }
            row {
                button("Write prompt with AI", Btn.OUTLINED, R.drawable.wna_auto_awesome, enabled = !isBusy) {
                    writeAiCoverPrompt(story, field.text.toString())
                }
                clearButton = button("Clear", Btn.TEXT) { clearAiCoverPrompt(story) }
            }
            generateButton =
                fullButton("Generate cover", Btn.FILLED, R.drawable.wna_auto_awesome, enabled = !isBusy, topMarginDp = Space.SM) {
                    generateAiCover(story, field.text.toString().trim())
                }
            // Keep typing across re-renders (progress ticks, results); it persists when a run starts.
            field.doAfterTextChanged { text ->
                aiControlsScreenState.coverPrompts[story.id] = text?.toString().orEmpty()
                syncPromptState()
            }
            syncPromptState()
            latestAiOperationCostLine(repository.getAiUsageLedger(), story.id, AI_FEATURE_COVER_PROMPT)
                ?.takeIf { aiControlsScreenState.coverDrafts[story.id] == null && prompt.isNotBlank() }
                ?.let { cost -> text("Last prompt — $cost", Type.LABEL_MEDIUM, colors.tertiary) }
            generating?.let { operation ->
                spacer(Space.MD)
                aiControlsScreenState.binding?.addProgress(this, operation)
            }
        }
    container.addView(cardView)
    val settings = repository.aiSettings.get()
    addAiModelFooter(container, "Image" to settings.imageModel, "Prompt" to settings.descriptionModel)
}

/**
 * Decodes a preview-sized bitmap: bounds are validated against a dimension sanity cap before
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
