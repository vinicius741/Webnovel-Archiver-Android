package com.vinicius741.webnovelarchiver.feature.ai

import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.data.repository.getAiUsageLedger
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.feature.details.makeStoryOperationSlot
import com.vinicius741.webnovelarchiver.feature.details.renderStoryOperationProgress
import com.vinicius741.webnovelarchiver.feature.details.showDetails
import com.vinicius741.webnovelarchiver.navigation.AppRoute
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
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.fullButton
import com.vinicius741.webnovelarchiver.ui.makeBadge
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.row
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.section
import com.vinicius741.webnovelarchiver.ui.spacer
import com.vinicius741.webnovelarchiver.ui.styledCheckBox
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import timber.log.Timber

/*
 * Per-novel AI Controls screen: the hub for AI-generated content, reached from the Details screen's
 * "More options" menu so the Details body stays free of per-feature AI buttons as more generators
 * (e.g. tags) join. It hosts description and cover-art generation as preview-then-apply flows: an
 * engine returns a draft, the user previews it here, and only "Apply" persists it through the
 * repository. It also owns the show-AI/original synopsis preference the Details screen renders.
 */

internal fun ScreenHost.showAiControls(storyId: String) {
    val story = repository.story(storyId) ?: return showDetails(storyId)
    val generating = storyOperation?.takeIf { it.storyId == story.id && it.kind == StoryOperationKind.AI_DESCRIPTION }
    // Cover jobs run on the process-wide coordinator: the lookup falls back to it so a job that
    // outlived this activity (recreated mid-run) still gates the buttons and shows its progress.
    val generatingCover = aiCoverOperationFor(story.id)
    // A draft generated in the background (or under a previous activity) lives on disk; pull it
    // into the screen state so its prompt/preview card renders. No-op when state is hydrated.
    hydrateAiCoverDraftFromStorage(story.id)
    screen(route = AppRoute.AiControls(story.id), title = "AI Controls", subtitle = story.title, onBack = {
        showDetails(story.id)
    }, scrollable = true) {
        section("Cover Art")
        text(
            "Generate a replacement cover from the novel's material. The source cover is kept " +
                "and can be restored at any time.",
            Type.BODY_SMALL,
            ThemeManager.colors.onSurfaceVariant,
        )
        spacer(Space.SM)
        addAiCoverCard(this, story, generatingCover)
        if (generatingCover != null) addView(makeStoryOperationSlot(app, generatingCover))
        aiControlsScreenState.coverPrompts[story.id]?.let { prompt -> addAiCoverPromptDraftCard(this, story, prompt) }
        aiControlsScreenState.coverDrafts[story.id]?.let { draft -> addAiCoverDraftPreviewCard(this, story, draft) }

        section("Description")
        text(
            "Generate a fresh synopsis from the novel's downloaded chapters. The source " +
                "description is never modified.",
            Type.BODY_SMALL,
            ThemeManager.colors.onSurfaceVariant,
        )
        spacer(Space.SM)
        addAiDescriptionCard(this, story, generating)
        if (generating != null) addView(makeStoryOperationSlot(app, generating))
        aiControlsScreenState.drafts[story.id]?.let { draft -> addAiDraftPreviewCard(this, story, draft) }
    }
    rerender = { showAiControls(storyId) }
}

/**
 * Current-state card: the model selector, context chapters, the applied AI synopsis,
 * the show-AI preference, and the generate/regenerate action.
 */
private fun ScreenHost.addAiDescriptionCard(
    container: LinearLayout,
    story: Story,
    generating: StoryOperationState?,
) {
    val colors = ThemeManager.colors
    val hasAi = !story.aiDescription.isNullOrBlank()
    val hasOriginal = !story.description.isNullOrBlank()
    // Without downloaded chapters there is no text to feed the model; archived snapshots are read-only.
    val canGenerate = story.isArchived != true && story.chapters.any { it.downloaded }
    val isBusy = storyOperation?.storyId == story.id
    val cardView =
        container.card {
            addAiDescriptionModelRow(this, story)
            spacer(Space.MD)
            addAiContextChaptersRow(this, story)
            spacer(Space.MD)
            if (hasAi) {
                addView(
                    makeBadge(context, "AI-generated", colors.tertiaryContainer, colors.onTertiaryContainer),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(Space.SM)
                    },
                )
                text(story.aiDescription.orEmpty(), Type.BODY_MEDIUM, colors.onSurfaceVariant).apply {
                    setLineSpacing(dp(Space.XS).toFloat(), 1f)
                }
            } else {
                text(
                    if (hasOriginal) {
                        "No AI description yet — the novel currently shows its source description."
                    } else {
                        "No description on file. Generate one to give the novel a synopsis."
                    },
                    Type.BODY_SMALL,
                    colors.onSurfaceVariant,
                )
            }
            if (hasAi && hasOriginal) {
                spacer(Space.SM)
                addAiDisplayToggleRow(this, story)
            }
            if (canGenerate) {
                spacer(Space.SM)
                fullButton(
                    label =
                        when {
                            generating != null -> "Generating..."
                            hasAi -> "Regenerate Description"
                            else -> "Generate Description with AI"
                        },
                    variant = Btn.FILLED,
                    icon = R.drawable.wna_auto_awesome,
                    enabled = generating == null && !isBusy,
                    bottomMarginDp = 0,
                ) { generateAiDescriptionDraft(story) }
            } else {
                text(
                    if (story.isArchived == true) {
                        "Archived snapshots are read-only — AI generation is disabled."
                    } else {
                        "Download at least one chapter before generating an AI description."
                    },
                    Type.BODY_SMALL,
                    colors.onSurfaceVariant,
                ).apply { setPadding(0, dp(Space.SM), 0, 0) }
            }
        }
    container.addView(cardView)
}

/** "Show AI description in Details" preference row; persists via [com.vinicius741.webnovelarchiver.data.repository.AppRepository.setShowAiDescription]. */
private fun ScreenHost.addAiDisplayToggleRow(
    container: LinearLayout,
    story: Story,
) {
    var toggle: CheckBox? = null
    container.row {
        addView(
            makeText(context, "Show AI description in Details", Type.BODY_MEDIUM, ThemeManager.colors.onSurface),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val checkBox =
            CheckBox(context).apply {
                text = ""
                isChecked = story.showAiDescription
            }
        styledCheckBox(checkBox)
        addView(checkBox)
        toggle = checkBox
    }
    toggle!!.setOnCheckedChangeListener { _, checked ->
        scope.launch { repository.setShowAiDescription(story.id, checked) }
    }
}

/** The generated draft with Apply/Discard actions. Nothing is persisted until Apply. */
private fun ScreenHost.addAiDraftPreviewCard(
    container: LinearLayout,
    story: Story,
    draft: String,
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
            text(draft, Type.BODY_MEDIUM, colors.onSurfaceVariant).apply {
                setLineSpacing(dp(Space.XS).toFloat(), 1f)
            }
            text(
                "Preview — nothing is saved yet. Apply replaces the novel's current AI description.",
                Type.BODY_SMALL,
                colors.onSurfaceVariant,
            ).apply { setPadding(0, dp(Space.XS), 0, dp(Space.SM)) }
            latestAiOperationCostLine(repository.getAiUsageLedger(), story.id, AI_FEATURE_DESCRIPTION)?.let { cost ->
                text(cost, Type.LABEL_MEDIUM, colors.tertiary).apply {
                    setPadding(0, 0, 0, dp(Space.SM))
                }
            }
            row {
                button("Apply", Btn.FILLED, R.drawable.wna_check) { applyAiDescriptionDraft(story, draft) }
                button("Discard", Btn.TEXT) { discardAiDescriptionDraft(story) }
            }
        }
    container.addView(cardView)
}

/**
 * Starts draft generation. Generating over an applied AI description or a pending (unapplied) preview
 * asks for confirmation first — every generation is a billable OpenRouter call on the user's key and
 * replaces the synopsis work already there.
 */
internal fun ScreenHost.generateAiDescriptionDraft(story: Story) {
    val model = repository.getAiSettings().descriptionModel
    val hasApplied = story.aiDescription != null
    val hasPendingDraft = aiControlsScreenState.drafts[story.id] != null
    if (!hasApplied && !hasPendingDraft) {
        startAiDescriptionDraft(story)
        return
    }
    val message =
        "Generate a new AI description with $model? This calls OpenRouter and uses your API credits." +
            (if (hasPendingDraft) " The pending preview will be replaced." else "")
    confirm(message, confirmLabel = "Generate") { startAiDescriptionDraft(story) }
}

// User-facing operation handler: funnel any failure into a toast + state cleanup after
// re-throwing CancellationException (the documented per-site opt-in for broad catches).
@Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
private fun ScreenHost.startAiDescriptionDraft(story: Story) {
    if (storyOperation != null) {
        toast("Please wait for the current operation to finish")
        return
    }
    storyOperation = StoryOperationState(story.id, StoryOperationKind.AI_DESCRIPTION, "Generating description...")
    detailsOperationSlot = null
    showAiControls(story.id)
    scope.launch {
        try {
            val draft =
                app.appContainer.aiDescriptionEngine.draft(story.id) { message ->
                    app.runOnUiThread { patchAiDraftProgress(story.id, message) }
                }
            aiControlsScreenState.drafts[story.id] = draft
            if (storyOperation?.storyId == story.id && storyOperation?.kind == StoryOperationKind.AI_DESCRIPTION) {
                storyOperation = null
                detailsOperationSlot = null
            }
            if (frameIsAiControls(story.id)) {
                showAiControls(story.id)
            } else {
                toast("AI description ready — preview it under More options → AI Controls")
                rerenderDetailsIfVisible(story.id)
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Timber.w(error, "AI description generation failed for %s", story.id)
            if (storyOperation?.storyId == story.id && storyOperation?.kind == StoryOperationKind.AI_DESCRIPTION) {
                storyOperation = null
                detailsOperationSlot = null
            }
            toast(error.message ?: "AI description failed")
            if (frameIsAiControls(story.id)) showAiControls(story.id) else rerenderDetailsIfVisible(story.id)
        }
    }
}

/**
 * Writes the progress message straight into [storyOperation] and patches whichever progress surface
 * is visible — deliberately not [com.vinicius741.webnovelarchiver.feature.story.setStoryOperation],
 * whose first tick rebuilds Details and would pull the user off this screen.
 */
private fun ScreenHost.patchAiDraftProgress(
    storyId: String,
    message: String,
) {
    val operation = storyOperation?.takeIf { it.storyId == storyId && it.kind == StoryOperationKind.AI_DESCRIPTION } ?: return
    val next = operation.copy(message = message)
    storyOperation = next
    detailsOperationSlot?.let { renderStoryOperationProgress(it, next) }
    if (frameIsAiControls(storyId)) showAiControls(storyId)
}

internal fun ScreenHost.applyAiDescriptionDraft(
    story: Story,
    draft: String,
) {
    scope.launch {
        repository.setAiDescription(story.id, draft)
        aiControlsScreenState.drafts.remove(story.id)
        toast("AI description applied")
        showAiControls(story.id)
    }
}

internal fun ScreenHost.discardAiDescriptionDraft(story: Story) {
    aiControlsScreenState.drafts.remove(story.id)
    toast("Draft discarded")
    showAiControls(story.id)
}

internal fun ScreenHost.frameIsAiControls(storyId: String): Boolean = frame.tag == AppRoute.AiControls(storyId).stableKey

/** Re-renders Details when it is the visible screen, so buttons disabled by the operation re-enable. */
internal fun ScreenHost.rerenderDetailsIfVisible(storyId: String) {
    if (frame.tag == AppRoute.Details(storyId).stableKey) showDetails(storyId)
}
