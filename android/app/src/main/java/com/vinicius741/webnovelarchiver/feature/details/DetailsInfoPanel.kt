package com.vinicius741.webnovelarchiver.feature.details

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.ai.AiDescriptionPlanning
import com.vinicius741.webnovelarchiver.domain.model.SourceMetric
import com.vinicius741.webnovelarchiver.domain.model.SourceMetricKind
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.SourceMetadataPlanning
import com.vinicius741.webnovelarchiver.domain.story.StoryActionGuards
import com.vinicius741.webnovelarchiver.feature.story.StoryTab
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.navigation.StoryOperationKind
import com.vinicius741.webnovelarchiver.navigation.StoryOperationState
import com.vinicius741.webnovelarchiver.source.PatreonSession
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.GridLayout
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.WrapLayout
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeBadge
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeSectionHeader
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.strokeBg
import com.vinicius741.webnovelarchiver.ui.tintedIcon

/*
 * The Overview tab under the hero ("About" beside the hero pane on the inner screen): engagement
 * stat cards, content warnings, the Patreon card, the description with Read more and Listen, tags,
 * and a short details list.
 */

/** Builds the About part into [container]; returns the description's Listen button for the player observer. */
internal fun ScreenHost.addDetailsAbout(
    container: LinearLayout,
    story: Story,
    twoPane: Boolean,
): Button? {
    val isBusy = storyOperation?.storyId == story.id
    addStatCards(container, story, twoPane)
    addWarningsAndUpdated(container, story)
    if (!story.effectivePatreonUrl.isNullOrBlank()) {
        container.addView(
            buildPatreonStatsCard(
                stats = story.patreonStats,
                patreonUrl = story.effectivePatreonUrl,
                earlyAccess =
                    PatreonEarlyAccessPlanning.display(
                        story.patreonEarlyAccess,
                        story.chapters.count { it.isPatreonCopy },
                        PatreonSession.isPresent(),
                        PatreonSession.storedAt,
                    ),
                onShowTrends = { switchStoryTab(story.id, StoryTab.TRENDS, AppRoute.Trends(story.id, FOCUS_PATREON_USD)) },
                onEarlyAccess = { showPatreonEarlyAccessPicker(story) },
                onEarlyAccessPrompt = { action -> handleEarlyAccessPrompt(story, action) },
                earlyAccessEnabled = !isBusy,
                onEditLink = { showPatreonLinkDialog(story) }.takeIf { !isBusy && StoryActionGuards.canModifyStory(story) },
            ),
        )
    } else if (StoryActionGuards.canModifyStory(story)) {
        container.addView(buildNoPatreonCard(story, isBusy))
    }
    val aiOperation = aiOperation?.takeIf { it.storyId == story.id && it.kind == StoryOperationKind.AI_DESCRIPTION }
    val listen = addDescription(container, story, aiOperation)
    addTags(container, story)
    addDetailsList(container, story)
    return listen
}

/** Invites adding the author's Patreon, which tracks members and early access. */
private fun ScreenHost.buildNoPatreonCard(
    story: Story,
    isBusy: Boolean,
): View {
    val colors = ThemeManager.colors
    val radius = dp(ThemeManager.shapes.cardRadius).toFloat()
    return LinearLayout(app).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(Spacing.LG), dp(Spacing.MD + 2), dp(Spacing.LG), dp(Spacing.MD + 2))
        background = ripple(strokeBg(colors.elevation1, radius, colors.line, dp(1)), radius, colors.onSurface)
        isClickable = !isBusy
        isFocusable = !isBusy
        addView(
            ImageView(app).apply {
                setImageDrawable(app.tintedIcon(R.drawable.wna_star, colors.onSurfaceVariant))
            },
            LinearLayout.LayoutParams(dp(22), dp(22)),
        )
        addView(
            LinearLayout(app).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(Spacing.MD + 2), 0, dp(Spacing.MD), 0)
                addView(makeText(app, "No Patreon linked", Type.TITLE_SMALL, colors.onSurface))
                addView(
                    makeText(app, "Add the author's Patreon to track members and early access.", Type.BODY_SMALL, colors.onSurfaceVariant),
                )
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            ImageView(app).apply {
                setImageDrawable(app.tintedIcon(R.drawable.wna_add, colors.primary))
            },
            LinearLayout.LayoutParams(dp(22), dp(22)),
        )
        contentDescription = "Add Patreon link"
        if (!isBusy) setOnClickListener { showPatreonLinkDialog(story) }
        layoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(Spacing.LG)
            }
    }
}

/**
 * The active synopsis (source or AI) under an About header with Listen; the slim AI progress
 * block stays here so backing out of the AI tab mid-generation still shows the job running.
 */
private fun ScreenHost.addDescription(
    container: LinearLayout,
    story: Story,
    operation: StoryOperationState?,
): Button? {
    val description = AiDescriptionPlanning.activeDescription(story)
    if (description == null && operation == null) return null
    val colors = ThemeManager.colors
    val header =
        LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(makeSectionHeader(app, "About").apply { setPadding(dp(Spacing.XS), 0, dp(Spacing.SM), 0) })
            if (AiDescriptionPlanning.isAiDescriptionActive(story)) {
                addView(
                    makeBadge(
                        app,
                        "AI",
                        colors.primaryContainer,
                        colors.onPrimaryContainer,
                    ).withStartIcon(R.drawable.wna_auto_awesome, colors.onPrimaryContainer),
                )
            }
            addView(View(app), LinearLayout.LayoutParams(0, 1, 1f))
        }
    val listen =
        description?.let {
            makeButton(app, "Listen", Btn.TEXT, R.drawable.wna_speaker) { toggleDescriptionTts(story) }.also { button ->
                button.minHeight = dp(40)
                button.minimumHeight = dp(40)
                header.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
    container.addView(
        header,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(Spacing.LG)
        },
    )
    description?.let { addDescriptionText(container, story, it) }
    if (operation != null) {
        makeStoryOperationSlot(app, operation).also {
            detailsAiOperationSlot = it
            container.addView(it)
        }
    }
    return listen
}

private fun ScreenHost.addTags(
    container: LinearLayout,
    story: Story,
) {
    val tags = story.tags?.filter(String::isNotBlank)?.takeIf { it.isNotEmpty() } ?: return
    val colors = ThemeManager.colors
    container.addView(makeSectionHeader(app, "Tags"))
    container.addView(
        WrapLayout(app).apply {
            horizontalSpacingDp = Spacing.SM
            verticalSpacingDp = Spacing.SM
            val radius = dp(ThemeManager.shapes.chipRadius).toFloat()
            tags.forEach { tag ->
                addView(
                    makeText(app, tag, Type.LABEL_LARGE, colors.onSurface).apply {
                        setPadding(dp(Spacing.MD), dp(6), dp(Spacing.MD), dp(6))
                        background = strokeBg(Color.TRANSPARENT, radius, colors.outline, dp(1))
                    },
                )
            }
        },
    )
}

/** Source, type, when it was added and last synced, and its library tab. */
private fun ScreenHost.addDetailsList(
    container: LinearLayout,
    story: Story,
) {
    val colors = ThemeManager.colors
    val providerName = SourceRegistry.getProvider(story.sourceId, story.sourceUrl)?.name ?: "—"
    val tabName =
        story.tabId?.let { id ->
            repository.tabs
                .get()
                .firstOrNull { it.id == id }
                ?.name
        } ?: "Unassigned"
    val rows =
        listOf(
            Triple(R.drawable.wna_globe, "Source", providerName),
            Triple(R.drawable.wna_list, "Type", story.sourceMetadata.sourceType?.takeIf { it.isNotBlank() } ?: "—"),
            Triple(R.drawable.wna_event, "Added", story.dateAdded?.let(::formatSourceDate) ?: "—"),
            Triple(R.drawable.wna_refresh, "Last synced", story.lastChapterSyncAt?.let { formatAgo(it) } ?: "Never"),
            Triple(R.drawable.wna_tab, "Tab", tabName),
        )
    container.addView(makeSectionHeader(app, "Details"))
    val radius = dp(ThemeManager.shapes.cardRadius).toFloat()
    val card =
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            background = strokeBg(colors.elevation1, radius, colors.line, dp(1))
        }
    rows.forEachIndexed { index, (icon, label, value) ->
        if (index >
            0
        ) {
            card.addView(
                View(app).apply { setBackgroundColor(colors.line) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)),
            )
        }
        card.addView(
            LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(DETAIL_ROW_DP)
                setPadding(dp(Spacing.LG), 0, dp(Spacing.LG), 0)
                addView(
                    ImageView(app).apply { setImageDrawable(app.tintedIcon(icon, colors.onSurfaceVariant)) },
                    LinearLayout.LayoutParams(dp(20), dp(20)),
                )
                addView(
                    makeText(app, label, Type.BODY_MEDIUM, colors.onSurface).apply { setPadding(dp(Spacing.LG), 0, dp(Spacing.SM), 0) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(makeText(app, value, Type.BODY_MEDIUM, colors.onSurfaceVariant).apply { maxLines = 1 })
            },
        )
    }
    container.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
}

/** Stable container whose children are swapped per tick by [renderStoryOperationProgress] without tearing down the page. */
internal fun makeStoryOperationSlot(
    context: android.content.Context,
    operation: StoryOperationState,
): LinearLayout =
    LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, context.dp(Spacing.SM), 0, 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        renderStoryOperationProgress(this, operation)
    }

private const val DETAIL_ROW_DP = 52
