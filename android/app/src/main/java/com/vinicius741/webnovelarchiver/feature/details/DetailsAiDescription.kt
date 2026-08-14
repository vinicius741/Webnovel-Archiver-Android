package com.vinicius741.webnovelarchiver.feature.details

import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.feature.story.clearStoryOperation
import com.vinicius741.webnovelarchiver.feature.story.setStoryOperation
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.navigation.StoryOperationKind
import com.vinicius741.webnovelarchiver.navigation.StoryOperationState
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.size
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import timber.log.Timber

/*
 * AI description generation for the Details screen. Rides the same storyOperation progress
 * machinery as EPUB/cleanup/sync: the first tick rebuilds Details (buttons disable + progress slot
 * allocates), later ticks patch the slot's message in place, and completion re-renders to reveal
 * the generated synopsis with its AI/original toggle.
 */

/** Views [addDetailsDescription] hands back to the Details screen builder. */
internal data class DetailsDescriptionViews(
    /** Description "Listen" button for the TTS observer; null when no description is displayed. */
    val listenButton: Button?,
    /** AI-generation progress slot, non-null only while an AI_DESCRIPTION operation is active. */
    val aiOperationSlot: LinearLayout?,
)

/**
 * The AI description action row inside the description block: a source/AI toggle once an AI
 * synopsis exists, and the generate/regenerate button when the story has downloadable context
 * (archived snapshots are read-only, so they only keep the toggle). Returns the in-flight
 * operation progress slot when an AI generation is running.
 */
internal fun ScreenHost.addAiDescriptionControls(
    container: LinearLayout,
    story: Story,
    operation: StoryOperationState?,
): LinearLayout? {
    val canGenerate = story.isArchived != true && story.chapters.any { it.downloaded }
    val hasAi = !story.aiDescription.isNullOrBlank()
    val hasOriginal = !story.description.isNullOrBlank()
    val generating = operation?.kind == StoryOperationKind.AI_DESCRIPTION
    if (!canGenerate && !hasAi) return null
    val isBusy = operation != null

    // Compact inline action buttons: align glyphs with description edge, use LABEL_MEDIUM (12sp)
    val padV = dp(Space.XS)
    val padH = dp(Space.MD)
    val row =
        LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(Space.XS)
                    bottomMargin = dp(Space.XS)
                }
        }
    if (hasAi && hasOriginal) {
        val toggle =
            makeButton(
                app,
                if (story.showAiDescription) "Show original" else "Show AI description",
                Btn.TEXT,
                0,
            ) { toggleAiDescription(story) }.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.LABEL_MEDIUM.size())
                minHeight = dp(32)
                minimumHeight = dp(32)
                setPadding(0, padV, padH / 2, padV)
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                isEnabled = !isBusy
            }
        toggle.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        row.addView(toggle)
        row.addView(
            View(app),
            LinearLayout.LayoutParams(0, 1, 1f),
        )
    }
    if (canGenerate) {
        val label =
            when {
                generating -> "Generating..."
                hasAi -> "Regenerate"
                else -> "Generate with AI"
            }
        val leadingPad = if (hasAi && hasOriginal) padH / 2 else 0
        val trailingPad = if (hasAi && hasOriginal) 0 else padH / 2
        val generate =
            makeButton(app, label, Btn.TEXT, R.drawable.wna_auto_awesome) { generateAiDescription(story) }.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.LABEL_MEDIUM.size())
                minHeight = dp(32)
                minimumHeight = dp(32)
                setPadding(leadingPad, padV, trailingPad, padV)
                compoundDrawablePadding = dp(4)
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                isEnabled = !isBusy
            }
        generate.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        row.addView(generate)
    }
    container.addView(row)
    return if (generating && operation != null) {
        makeStoryOperationSlot(app, operation).also(container::addView)
    } else {
        null
    }
}

/**
 * Starts AI description generation for [story]. Regenerating over an existing AI description asks
 * for confirmation first — every generation is a billable OpenRouter call on the user's key.
 */
internal fun ScreenHost.generateAiDescription(story: Story) {
    val model = repository.getAiSettings().descriptionModel
    if (story.aiDescription != null) {
        confirm(
            "Generate a new AI description with $model? This calls OpenRouter and uses your API credits.",
            confirmLabel = "Generate",
        ) { startAiDescriptionGeneration(story) }
    } else {
        startAiDescriptionGeneration(story)
    }
}

private fun ScreenHost.startAiDescriptionGeneration(story: Story) {
    if (storyOperation != null) {
        toast("Please wait for the current operation to finish")
        return
    }
    scope.launch {
        try {
            setStoryOperation(story.id, StoryOperationKind.AI_DESCRIPTION, "Generating description...")
            val description =
                app.appContainer.aiDescriptionEngine.generate(story.id) { message ->
                    app.runOnUiThread {
                        setStoryOperation(story.id, StoryOperationKind.AI_DESCRIPTION, message)
                    }
                }
            clearStoryOperation(story.id, StoryOperationKind.AI_DESCRIPTION, rerender = false)
            toast("AI description generated (${description.length} chars)")
            showDetails(story.id)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Timber.w(error, "AI description generation failed for %s", story.id)
            clearStoryOperation(story.id, StoryOperationKind.AI_DESCRIPTION, rerender = false)
            toast(error.message ?: "AI description failed")
            showDetails(story.id)
        }
    }
}

/** Switches the displayed synopsis between the source description and the AI-generated one. */
internal fun ScreenHost.toggleAiDescription(story: Story) {
    scope.launch {
        repository.setShowAiDescription(story.id, !story.showAiDescription)
        showDetails(story.id)
    }
}
