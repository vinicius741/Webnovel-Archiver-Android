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
import com.vinicius741.webnovelarchiver.feature.story.CoverViewerAction
import com.vinicius741.webnovelarchiver.feature.story.CoverViewerItem
import com.vinicius741.webnovelarchiver.feature.story.showCoverViewer
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.activeCoverSource
import com.vinicius741.webnovelarchiver.ui.applyInputStyle
import com.vinicius741.webnovelarchiver.ui.button
import com.vinicius741.webnovelarchiver.ui.card
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.fullButton
import com.vinicius741.webnovelarchiver.ui.makeBadge
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.row
import com.vinicius741.webnovelarchiver.ui.scrollTextInsideParent
import com.vinicius741.webnovelarchiver.ui.section
import com.vinicius741.webnovelarchiver.ui.spacer
import com.vinicius741.webnovelarchiver.ui.strokeBg
import com.vinicius741.webnovelarchiver.ui.text
import kotlinx.coroutines.Dispatchers
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
    render: AiTabRender,
) {
    render.state.coverDraft?.let { draft -> addAiNewCoverCard(container, render, draft) }
    addAiCoverStrip(container, render)
    container.section("New cover")
    addAiCoverMakerCard(container, render)
}

/**
 * The waiting result beside the cover in use, each at half the card's width, with View full size
 * (the compare viewer) and the decision buttons.
 */
private fun ScreenHost.addAiNewCoverCard(
    container: LinearLayout,
    render: AiTabRender,
    draft: AiCoverDraft,
) {
    val story = render.story
    // Deciding on a waiting result stays open while another cover generates: the job never touches the cover in use.
    val editable = story.isArchived != true
    val colors = ThemeManager.colors
    val radius = dp(ThemeManager.shapes.cardRadius).toFloat()
    val cardView =
        container.card {
            background = strokeBg(colors.primaryContainer and NEW_COVER_TINT_MASK, radius, colors.primary and NEW_COVER_STROKE_MASK, dp(1))
            row {
                addView(
                    makeText(context, "New cover ready", Type.TITLE_MEDIUM, colors.onSurface),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(makeBadge(context, "New", colors.primaryContainer, colors.onPrimaryContainer))
            }
            // R24: the preview bitmap is decoded off main, sampled to preview size, with a
            // dimension sanity check before any large allocation.
            val previewSlot = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            addView(previewSlot)
            var useButton: Button? = null
            var viewButton: Button? = null
            scope.launch(Dispatchers.Default) {
                val bitmap = decodeSampledDraftPreview(draft.bytes)
                app.runOnUiThread {
                    if (app.isFinishing || app.isDestroyed) return@runOnUiThread
                    previewSlot.removeAllViews()
                    if (bitmap != null) {
                        val open = { index: Int -> showNewCoverViewer(render, draft, bitmap, index, editable) }
                        addAiCoverCompareRow(previewSlot, story, bitmap, onOpen = open)
                        viewButton?.apply {
                            visibility = View.VISIBLE
                            setOnClickListener { open(1) }
                        }
                        useButton?.apply {
                            isEnabled = editable
                            alpha = if (editable) 1f else DISABLED_ALPHA
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
            viewButton =
                fullButton("View full size", Btn.TONAL, R.drawable.wna_open_in_full, topMarginDp = Space.XS, bottomMarginDp = Space.SM) {}
                    .apply { visibility = View.GONE }
            render.state.costs.coverImage?.let { cost ->
                text(cost, Type.BODY_SMALL, colors.onSurfaceVariant).apply {
                    setPadding(0, 0, 0, dp(Space.SM))
                }
            }
            row {
                useButton =
                    button("Use it", Btn.FILLED, R.drawable.wna_check, enabled = false) {
                        render.model.resolveCoverDraft(draft, use = true)
                    }
                button("Keep current", Btn.SURFACE, enabled = editable) { render.model.resolveCoverDraft(draft, use = false) }
                for (index in 0 until childCount) {
                    (getChildAt(index).layoutParams as LinearLayout.LayoutParams).apply {
                        width = 0
                        weight = 1f
                    }
                }
            }
        }
    container.addView(cardView)
}

/** The compare viewer: Current and New full screen, swipe between them, then keep or use. */
private fun ScreenHost.showNewCoverViewer(
    render: AiTabRender,
    draft: AiCoverDraft,
    bitmap: android.graphics.Bitmap,
    index: Int,
    editable: Boolean,
) {
    val story = render.story
    val current = activeCoverSource(story)
    val items =
        listOfNotNull(
            current?.let { CoverViewerItem(it, "Current cover", story.title) },
            CoverViewerItem(bitmap, "New cover", story.title, prompt = draft.prompt),
        )
    val start = if (current == null) 0 else index
    showCoverViewer(
        items,
        start,
        actions = {
            listOf(
                CoverViewerAction(KEEP_ACTION, "Keep current", enabled = editable),
                CoverViewerAction(USE_ACTION, "Use it", R.drawable.wna_check, primary = true, enabled = editable),
            )
        },
    ) { action, viewer ->
        viewer.close()
        render.model.resolveCoverDraft(draft, use = action == USE_ACTION)
    }
}

/** The single generation card: chapters, prompt box, the two actions, progress, and the models used. */
private fun ScreenHost.addAiCoverMakerCard(
    container: LinearLayout,
    render: AiTabRender,
) {
    val story = render.story
    val state = render.state
    val generating = state.coverJob
    val colors = ThemeManager.colors
    // Same gating as descriptions: no context chapters means nothing to feed the text model.
    val canGenerate = story.isArchived != true && story.chapters.any { it.downloaded }
    if (!canGenerate) {
        container.addView(
            container.card {
                text(
                    "Download at least one chapter before generating an AI cover.",
                    Type.BODY_SMALL,
                    colors.onSurfaceVariant,
                )
            },
        )
        return
    }
    val isBusy = state.aiJob != null || generating != null
    val prompt = state.coverPrompt?.text ?: state.coverDraft?.prompt.orEmpty()
    val cardView =
        container.card {
            addAiContextChaptersRow(this, render, forCover = true)
            addAiCoverPhaseRow(this, render)
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
                    scrollTextInsideParent()
                    isEnabled = !isBusy
                    contentDescription = "Image prompt"
                }
            addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val hint =
                text("", Type.BODY_SMALL, colors.onSurfaceVariant).apply {
                    setPadding(0, dp(Space.SM), 0, dp(Space.SM))
                }
            var clearButton: Button? = null

            fun syncPromptState() {
                val blank = field.text.isNullOrBlank()
                hint.text =
                    if (blank) {
                        "Leave it empty and AI writes a prompt from the chapters, then paints it."
                    } else {
                        "Paints exactly this prompt. Only the image call uses credits."
                    }
                clearButton?.visibility = if (blank || isBusy) View.GONE else View.VISIBLE
            }
            row {
                button("Draft a prompt", Btn.OUTLINED, R.drawable.wna_auto_awesome, enabled = !isBusy) {
                    writeAiCoverPrompt(render, field.text.toString())
                }
                clearButton = button("Clear", Btn.TEXT) { render.model.clearCoverPrompt() }
            }
            fullButton("New cover", Btn.FILLED, R.drawable.wna_auto_awesome, enabled = !isBusy, topMarginDp = Space.SM) {
                generateAiCover(render, field.text.toString().trim())
            }
            // Keep typing across re-renders (progress ticks, results); it persists when a run starts.
            field.doAfterTextChanged { text ->
                render.model.typeCoverPrompt(text?.toString().orEmpty())
                syncPromptState()
            }
            syncPromptState()
            state.costs.coverPrompt
                ?.takeIf { state.coverDraft == null && prompt.isNotBlank() }
                ?.let { cost -> text("Last prompt — $cost", Type.LABEL_MEDIUM, colors.tertiary) }
            generating?.let { operation ->
                spacer(Space.MD)
                render.addProgress(this, operation)
            }
        }
    container.addView(cardView)
    addAiModelFooter(container, "Image" to state.settings.imageModel, "Prompt" to state.settings.descriptionModel)
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

private const val USE_ACTION = "use"
private const val KEEP_ACTION = "keep"
private const val DISABLED_ALPHA = 0.4f
private const val NEW_COVER_TINT_MASK = 0x59FFFFFF
private const val NEW_COVER_STROKE_MASK = 0x40FFFFFF
