package com.vinicius741.webnovelarchiver.feature.ai

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.feature.details.makeStoryOperationProgress
import com.vinicius741.webnovelarchiver.feature.details.showDetails
import com.vinicius741.webnovelarchiver.feature.details.storyScreen
import com.vinicius741.webnovelarchiver.feature.settings.showAiSettings
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.BackIntercept
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.operations.StoryOperationKind
import com.vinicius741.webnovelarchiver.operations.StoryOperationState
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.SegmentedControl.Segment
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.bindWhileAttached
import com.vinicius741.webnovelarchiver.ui.button
import com.vinicius741.webnovelarchiver.ui.card
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.fullButton
import com.vinicius741.webnovelarchiver.ui.makeBadge
import com.vinicius741.webnovelarchiver.ui.makeChip
import com.vinicius741.webnovelarchiver.ui.makeSegmentedControl
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.row
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.spacer
import com.vinicius741.webnovelarchiver.ui.styledCheckBox
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.toast

/**
 * The per-novel AI screen, split into Cover, Characters and Description tabs. Global model choices
 * live in AI Settings; each tab only names the model it uses and links there. [tab] switches the
 * open tab (job chip and the story hero open the tab their result belongs to).
 *
 * The tab is built from its [AiControlsScreenModel]'s state, and an [AiControlsRenderer] keeps the
 * render in step: a layout change re-renders the tab, progress messages patch their lines, and the
 * cover strip and character list fill their slots when they load.
 */
internal fun ScreenHost.showAiControls(
    storyId: String,
    tab: AiControlsTab? = null,
) {
    val story = repository.story(storyId) ?: return showDetails(storyId)
    val key = AppRoute.AiControls(story.id).stableKey
    var render: AiTabRender? = null
    storyScreen(
        route = AppRoute.AiControls(story.id),
        story = story,
        scrollable = true,
        backIntercept =
            BackIntercept(
                isActive = {
                    screenModels
                        .find<AiControlsScreenModel>(key)
                        ?.state
                        ?.value
                        ?.characterPageOpen == true
                },
                clear = { screenModels.find<AiControlsScreenModel>(key)?.closeCharacterPage() },
            ),
    ) {
        // Read inside the scaffold, once the route is in the history the model store keeps.
        val model = aiControlsModel(story.id)
        tab?.let(model::selectTab)
        val state = model.refresh()
        val shown = AiTabRender(model, state, state.story ?: story)
        render = shown
        addAiTabBar(this, shown)
        when (state.tab) {
            AiControlsTab.COVER -> addAiCoverTab(this, shown)
            AiControlsTab.CHARACTERS -> addAiCharactersTab(this, shown)
            AiControlsTab.DESCRIPTION -> {
                addAiDescriptionCard(this, shown)
                state.descriptionDraft?.let { draft -> addAiDraftPreviewCard(this, shown, draft) }
                addAiModelFooter(this, "Text model" to state.settings.descriptionModel)
            }
        }
    }
    rerender = { showAiControls(storyId) }
    render?.let { AiControlsRenderer(this, it, frame.getChildAt(0)).start() }
}

/** One render of the AI tab: the model, the state it was built from, and the views its renderer patches. */
internal class AiTabRender(
    val model: AiControlsScreenModel,
    val state: AiControlsUiState,
    val story: Story,
) {
    /** Progress lines of the running AI job, by kind. */
    val progress = mutableMapOf<StoryOperationKind, TextView>()

    /** The running character job's progress line. */
    var characterProgress: TextView? = null

    /** The cover strip's slot, filled once the saved versions load. */
    var coverStrip: CoverStripSlot? = null

    /** The Characters tab's slot, filled once the list loads. */
    var charactersSlot: LinearLayout? = null

    /** Adds the operation's progress block to [container], the card whose action started it. */
    fun addProgress(
        container: LinearLayout,
        operation: StoryOperationState,
    ) {
        container.addView(makeStoryOperationProgress(container.context, operation, indeterminate = true) { progress[operation.kind] = it })
    }
}

/**
 * Keeps one render of the AI tab in step with its model while the render's root is attached. A
 * change of layout (see [AiControlsUiState.layoutKey]) re-renders the tab; otherwise loaded parts
 * fill their slots and progress messages patch their lines. Model messages become toasts.
 */
private class AiControlsRenderer(
    private val host: ScreenHost,
    private val render: AiTabRender,
    private val root: View,
) {
    private var applied = render.state

    fun start() {
        root.bindWhileAttached(host.scope, render.model.state) { apply(it) }
        root.bindWhileAttached(host.scope, render.model.messages) { host.toast(it) }
    }

    private fun apply(next: AiControlsUiState) {
        val previous = applied
        if (!root.isAttachedToWindow || next == previous) return
        applied = next
        if (next.layoutKey() != previous.layoutKey()) {
            host.showAiControls(render.model.storyId)
            return
        }
        if (next.characters != previous.characters) {
            val slot = render.charactersSlot
            // A list already on screen re-renders the tab, so it keeps its scroll position.
            val shown = previous.characters?.takeIf { it.openId == next.openCharacterId }
            if (slot != null && shown != null) {
                host.showAiControls(render.model.storyId)
                return
            }
            slot?.let { host.fillCharactersSlot(it, render, next) }
        }
        if (next.coverVersions !=
            previous.coverVersions
        ) {
            render.coverStrip?.let { host.fillCoverStrip(it, render.story, render.model, next) }
        }
        next.aiJob?.let { render.progress[it.kind]?.text = it.message }
        next.coverJob?.let { render.progress[StoryOperationKind.AI_COVER]?.text = it.message }
        next.characterJob?.let { render.characterProgress?.text = it.message }
    }
}

/** Cover | Characters | Description, with a dot on the one whose finished result is still waiting. */
private fun ScreenHost.addAiTabBar(
    container: LinearLayout,
    render: AiTabRender,
) {
    val state = render.state
    val tabs = AiControlsTab.entries
    val segments =
        tabs.map { tab ->
            val waiting =
                tab != state.tab &&
                    when (tab) {
                        AiControlsTab.COVER -> state.coverDraft != null
                        AiControlsTab.DESCRIPTION -> state.descriptionDraft != null
                        AiControlsTab.CHARACTERS -> state.charactersUnseen
                    }
            // Text only: three labels with icons do not fit beside the story pane.
            Segment(tab.label, dot = waiting)
        }
    container.addView(
        makeSegmentedControl(app, segments, tabs.indexOf(state.tab)) { index -> render.model.selectTab(tabs[index]) },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(Space.XS)
            bottomMargin = dp(Space.SM)
        },
    )
}

/** One quiet line naming the models a tab uses; tapping it opens AI Settings, where models are chosen. */
internal fun ScreenHost.addAiModelFooter(
    container: LinearLayout,
    vararg models: Pair<String, String>,
) {
    val names = models.joinToString(" · ") { (label, id) -> "$label: $id" }
    container.text("$names · Change in AI Settings", Type.BODY_SMALL, ThemeManager.colors.faint).apply {
        setPadding(dp(Space.XS), dp(Space.MD), dp(Space.XS), dp(Space.SM))
        background = ripple(roundedBg(Color.TRANSPARENT, dp(Space.SM).toFloat()), dp(Space.SM).toFloat(), ThemeManager.colors.onSurface)
        isClickable = true
        setOnClickListener { showAiSettings() }
    }
}

private fun ScreenHost.addAiDescriptionCard(
    container: LinearLayout,
    render: AiTabRender,
) {
    val story = render.story
    val generating = render.state.aiJob?.takeIf { it.kind == StoryOperationKind.AI_DESCRIPTION }
    val colors = ThemeManager.colors
    val hasAi = !story.aiDescription.isNullOrBlank()
    val hasOriginal = !story.description.isNullOrBlank()
    // Without downloaded chapters there is no text to feed the model; archived snapshots are read-only.
    val canGenerate = story.isArchived != true && story.chapters.any { it.downloaded }
    val isBusy = render.state.aiJob != null
    val cardView =
        container.card {
            addAiContextChaptersRow(this, render)
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
                addAiDisplayToggleRow(this, render)
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
                ) { generateAiDescriptionDraft(render) }
                generating?.let { operation ->
                    spacer(Space.MD)
                    render.addProgress(this, operation)
                }
            } else {
                text(
                    "Download at least one chapter before generating an AI description.",
                    Type.BODY_SMALL,
                    colors.onSurfaceVariant,
                ).apply { setPadding(0, dp(Space.SM), 0, 0) }
            }
        }
    container.addView(cardView)
}

/**
 * Applying an AI description already shows it; this switches back to the source's one (or to the AI
 * one again) without a separate on/off setting.
 */
private fun addAiDisplayToggleRow(
    container: LinearLayout,
    render: AiTabRender,
) {
    val story = render.story
    if (story.description.isNullOrBlank()) return
    val showingAi = story.showAiDescription
    container.row {
        button(if (showingAi) "Use source description" else "Use AI description", Btn.TEXT) { render.model.showAiDescription(!showingAi) }
    }
}

/** Draft preview; nothing is persisted until Apply. */
private fun ScreenHost.addAiDraftPreviewCard(
    container: LinearLayout,
    render: AiTabRender,
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
            render.state.costs.description?.let { cost ->
                text(cost, Type.LABEL_MEDIUM, colors.tertiary).apply {
                    setPadding(0, 0, 0, dp(Space.SM))
                }
            }
            row {
                button("Apply", Btn.FILLED, R.drawable.wna_check) { render.model.applyDescription(draft) }
                button("Discard", Btn.TEXT) { render.model.discardDescription() }
            }
        }
    container.addView(cardView)
}

/** Confirms first when an applied or pending synopsis would be replaced: each run is a billable OpenRouter call. */
private fun ScreenHost.generateAiDescriptionDraft(render: AiTabRender) {
    val model = render.state.settings.descriptionModel
    val hasApplied = render.story.aiDescription != null
    val hasPendingDraft = render.state.descriptionDraft != null
    if (!hasApplied && !hasPendingDraft) {
        render.model.startDescription()
        return
    }
    val message =
        "Generate a new AI description with $model? This calls OpenRouter and uses your API credits." +
            (if (hasPendingDraft) " The pending preview will be replaced." else "")
    confirm(message, confirmLabel = "Generate") { render.model.startDescription() }
}
