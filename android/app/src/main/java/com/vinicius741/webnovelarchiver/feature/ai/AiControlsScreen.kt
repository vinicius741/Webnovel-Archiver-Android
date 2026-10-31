package com.vinicius741.webnovelarchiver.feature.ai

import android.graphics.Color
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.data.repository.getAiUsageLedger
import com.vinicius741.webnovelarchiver.data.repository.markAiCoverResultSeen
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.feature.details.renderStoryOperationProgress
import com.vinicius741.webnovelarchiver.feature.details.showDetails
import com.vinicius741.webnovelarchiver.feature.settings.showAiSettings
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
import com.vinicius741.webnovelarchiver.ui.makeChip
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.row
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.spacer
import com.vinicius741.webnovelarchiver.ui.styledCheckBox
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The per-novel AI screen, split into a Cover tab and a Description tab. Global model choices
 * live in AI Settings; each tab only names the model it uses and links there. [tab] switches the
 * remembered tab (job chip and notifications open the tab their result belongs to).
 */
internal fun ScreenHost.showAiControls(
    storyId: String,
    tab: AiControlsTab? = null,
) {
    val story = repository.story(storyId) ?: return showDetails(storyId)
    tab?.let { aiControlsScreenState.tabs[story.id] = it }
    val shownTab = aiControlsTab(story.id)
    val generating = storyOperation?.takeIf { it.storyId == story.id && it.kind == StoryOperationKind.AI_DESCRIPTION }
    // Cover jobs run on the process-wide coordinator so one that outlived a recreated activity still gates the UI.
    val generatingCover = aiCoverOperationFor(story.id)
    hydrateAiCoverDraftFromStorage(story.id)
    markAiResultsSeen(story.id, shownTab)
    screen(route = AppRoute.AiControls(story.id), title = "AI Controls", subtitle = story.title, scrollable = true) {
        val binding = AiControlsBinding(story.id, this, storyOperation?.takeIf { it.storyId == story.id }?.kind)
        aiControlsScreenState.binding = binding
        addAiTabBar(this, story.id, shownTab)
        when (shownTab) {
            AiControlsTab.COVER -> addAiCoverTab(this, story, generatingCover)
            AiControlsTab.DESCRIPTION -> {
                addAiDescriptionCard(this, story, generating)
                aiControlsScreenState.drafts[story.id]?.let { draft -> addAiDraftPreviewCard(this, story, draft) }
                addAiModelFooter(this, "Text model" to repository.aiSettings.get().descriptionModel)
            }
        }
    }
    rerender = { showAiControls(storyId) }
}

internal fun ScreenHost.aiControlsTab(storyId: String): AiControlsTab = aiControlsScreenState.tabs[storyId] ?: AiControlsTab.COVER

/** Opening a tab is looking at its result: clears the unseen marker, chip, and stale failure. */
private fun ScreenHost.markAiResultsSeen(
    storyId: String,
    tab: AiControlsTab,
) {
    aiControlsScreenState.clearChipHistory(storyId, tab)
    when (tab) {
        AiControlsTab.COVER -> if (storyId in repository.aiCoverUnseen.value) scope.launch { repository.markAiCoverResultSeen(storyId) }
        AiControlsTab.DESCRIPTION -> aiControlsScreenState.unseenDescriptions.remove(storyId)
    }
}

private fun ScreenHost.addAiTabBar(
    container: LinearLayout,
    storyId: String,
    selected: AiControlsTab,
) {
    container.row {
        AiControlsTab.entries.forEach { tab ->
            // A finished result waiting on the other tab gets a dot so it is not missed.
            val waiting =
                tab != selected &&
                    when (tab) {
                        AiControlsTab.COVER -> aiControlsScreenState.coverDrafts[storyId] != null
                        AiControlsTab.DESCRIPTION -> aiControlsScreenState.drafts[storyId] != null
                    }
            addView(
                makeChip(context, if (waiting) "${tab.label} •" else tab.label, tab == selected) {
                    if (tab != selected) showAiControls(storyId, tab)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(Space.SM)
                },
            )
        }
    }
    container.spacer(Space.SM)
}

/** One quiet line naming the models a tab uses; tapping it opens AI Settings, where models are chosen. */
internal fun ScreenHost.addAiModelFooter(
    container: LinearLayout,
    vararg models: Pair<String, String>,
) {
    val names = models.joinToString(" · ") { (label, id) -> "$label: $id" }
    container.text("$names · Change in AI Settings", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
        setPadding(0, dp(Space.XS), 0, dp(Space.SM))
        background = ripple(roundedBg(Color.TRANSPARENT, dp(Space.SM).toFloat()), dp(Space.SM).toFloat(), ThemeManager.colors.onSurface)
        isClickable = true
        setOnClickListener { showAiSettings() }
    }
}

private fun ScreenHost.addAiDescriptionCard(
    container: LinearLayout,
    story: Story,
    generating: StoryOperationState?,
) {
    val binding = aiControlsScreenState.binding
    val colors = ThemeManager.colors
    val hasAi = !story.aiDescription.isNullOrBlank()
    val hasOriginal = !story.description.isNullOrBlank()
    // Without downloaded chapters there is no text to feed the model; archived snapshots are read-only.
    val canGenerate = story.isArchived != true && story.chapters.any { it.downloaded }
    val isBusy = storyOperation?.storyId == story.id
    val cardView =
        container.card {
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
                generating?.let { operation ->
                    spacer(Space.MD)
                    binding?.addProgress(this, operation)
                }
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

/** Draft preview; nothing is persisted until Apply. */
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
                "Preview — not saved yet",
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

/** Confirms first when an applied or pending synopsis would be replaced: each run is a billable OpenRouter call. */
internal fun ScreenHost.generateAiDescriptionDraft(story: Story) {
    val model = repository.aiSettings.get().descriptionModel
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

// Re-throw CancellationException; funnel other failures into toast + state cleanup.
@Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
private fun ScreenHost.startAiDescriptionDraft(story: Story) {
    if (storyOperation != null) {
        toast("Please wait for the current operation to finish")
        return
    }
    storyOperation = StoryOperationState(story.id, StoryOperationKind.AI_DESCRIPTION, "Generating description...")
    detailsOperationSlot = null
    aiControlsScreenState.clearChipHistory(story.id, AiControlsTab.DESCRIPTION)
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
            // Off its tab, the app-wide job chip points back to the waiting preview.
            if (!frameIsAiControls(story.id) || aiControlsTab(story.id) != AiControlsTab.DESCRIPTION) {
                aiControlsScreenState.unseenDescriptions += story.id
            }
            aiControlsScreenState.refreshJobChip()
            if (frameIsAiControls(story.id)) showAiControls(story.id) else rerenderDetailsIfVisible(story.id)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Timber.w(error, "AI description generation failed for %s", story.id)
            if (storyOperation?.storyId == story.id && storyOperation?.kind == StoryOperationKind.AI_DESCRIPTION) {
                storyOperation = null
                detailsOperationSlot = null
            }
            val message = error.message ?: "AI description failed"
            if (frameIsAiControls(story.id)) {
                toast(message)
                showAiControls(story.id)
            } else {
                aiControlsScreenState.recordFailure(story.id, AiControlsTab.DESCRIPTION, message)
                rerenderDetailsIfVisible(story.id)
            }
            aiControlsScreenState.refreshJobChip()
        }
    }
}

/** Patches progress in place; setStoryOperation's first tick would rebuild Details and pull the user off this screen. */
internal fun ScreenHost.patchAiDraftProgress(
    storyId: String,
    message: String,
) {
    val operation = storyOperation?.takeIf { it.storyId == storyId && it.kind == StoryOperationKind.AI_DESCRIPTION } ?: return
    val next = operation.copy(message = message)
    storyOperation = next
    detailsOperationSlot?.let { renderStoryOperationProgress(it, next) }
    updateAiControlsProgress(next)
    aiControlsScreenState.refreshJobChip()
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

internal fun ScreenHost.rerenderDetailsIfVisible(storyId: String) {
    if (frame.tag == AppRoute.Details(storyId).stableKey) showDetails(storyId)
}
