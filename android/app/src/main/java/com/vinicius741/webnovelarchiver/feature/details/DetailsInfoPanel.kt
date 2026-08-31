package com.vinicius741.webnovelarchiver.feature.details

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.ai.AiDescriptionPlanning
import com.vinicius741.webnovelarchiver.domain.model.EpubConfig
import com.vinicius741.webnovelarchiver.domain.model.SourceAvailability
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.StoryActionGuards
import com.vinicius741.webnovelarchiver.download.DownloadDetailsPlanning
import com.vinicius741.webnovelarchiver.download.DownloadPacingUiStatus
import com.vinicius741.webnovelarchiver.feature.downloads.showQueue
import com.vinicius741.webnovelarchiver.feature.story.generateConfiguredEpub
import com.vinicius741.webnovelarchiver.feature.story.openEpubForStory
import com.vinicius741.webnovelarchiver.feature.story.syncStory
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.navigation.StoryOperationKind
import com.vinicius741.webnovelarchiver.navigation.StoryOperationState
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.WrapLayout
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeBadge
import com.vinicius741.webnovelarchiver.ui.makeFullWidthButton
import com.vinicius741.webnovelarchiver.ui.makeText

/** Builds the info panel and returns the stable views the download-refresh loop patches in place after progress events. */
@Suppress("CyclomaticComplexMethod") // One linear UI builder intentionally reflects mutually exclusive story states.
internal fun ScreenHost.buildDetailsInfoPanel(
    story: Story,
    operation: StoryOperationState?,
    downloadSummary: DownloadDetailsPlanning.StoryDownloadSummary,
    pacingStatus: DownloadPacingUiStatus? = null,
): DetailsInfoPanel {
    val isBusy = operation != null
    val infoPanel = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
    val header = buildDetailsHeader(story)
    infoPanel.addView(header.view)
    // Source metadata stays in the top summary area; after a long synopsis it looked uncaptured.
    buildSourceMetadataFlow(story)?.let(infoPanel::addView)

    // Mutable slots the caller patches after progress events; non-null only when rendered.
    var bannerSlot: LinearLayout? = null
    var downloadActionSlot: LinearLayout? = null
    var operationSlot: LinearLayout? = null

    if (story.isArchived == true) {
        infoPanel.addView(
            makeText(app, "Archived snapshot: sync and downloads disabled", Type.LABEL_MEDIUM, ThemeManager.colors.tertiary).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(Space.SM), 0, dp(Space.XS))
            },
        )
    }
    if (story.isArchived != true && story.sourceSyncState.availability != SourceAvailability.available) {
        infoPanel.addView(buildSourceAvailabilityNotice(story))
    }
    if (StoryActionGuards.canSync(story)) {
        val syncLabel =
            when {
                operation?.kind == StoryOperationKind.SYNC -> "Checking Source..."
                story.sourceSyncState.availability != SourceAvailability.available -> "Check Source Again"
                else -> "Sync Chapters"
            }
        infoPanel.addView(
            makeFullWidthButton(
                app,
                syncLabel,
                Btn.FILLED,
                R.drawable.wna_refresh,
                dp(Space.SM + 2),
                enabled = !isBusy,
            ) {
                syncStory(story)
            },
        )
    }
    if (operation?.kind == StoryOperationKind.SYNC) {
        operationSlot = makeStoryOperationSlot(app, operation)
        infoPanel.addView(operationSlot!!)
    }
    if (StoryActionGuards.canQueueDownloads(story)) {
        downloadActionSlot = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
        renderDetailsDownloadAction(downloadActionSlot!!, story, downloadSummary, isBusy)
        infoPanel.addView(downloadActionSlot!!)
    }
    if (shouldShowDetailsBanner(downloadSummary)) {
        // Stable slot: the refresh loop swaps the banner child in place instead of rebuilding the
        // screen. Patching a detached slot view is safe and shows on reattach.
        bannerSlot =
            LinearLayout(app).apply {
                orientation = LinearLayout.VERTICAL
                addView(makeDownloadProgressBanner(app, downloadSummary, pacingStatus) { showQueue() })
            }
        infoPanel.addView(bannerSlot!!)
    }
    if (operation?.kind == StoryOperationKind.CLEANUP) {
        // Stable slot: progress ticks patch in place; per-chapter showDetails calls flickered.
        operationSlot = makeStoryOperationSlot(app, operation)
        infoPanel.addView(operationSlot!!)
    }
    val hasEpub = (!story.epubPaths.isNullOrEmpty()) || !story.epubPath.isNullOrBlank()
    val generateLabel = if (operation?.kind == StoryOperationKind.EPUB) "Generating..." else "Generate EPUB"
    infoPanel.addView(
        makeFullWidthButton(
            app,
            generateLabel,
            Btn.TONAL,
            R.drawable.wna_menu_book,
            dp(Space.SM + 2),
            enabled =
                story.downloadedChapters > 0 && !isBusy,
        ) {
            val config =
                story.epubConfig ?: EpubConfig(
                    maxChaptersPerEpub = repository.getSettings().maxChaptersPerEpub,
                    rangeStart = 1,
                    rangeEnd = story.chapters.size,
                    startAtBookmark = false,
                )
            generateConfiguredEpub(story, config)
        },
    )
    if (operation?.kind == StoryOperationKind.EPUB) {
        operationSlot = makeStoryOperationSlot(app, operation)
        infoPanel.addView(operationSlot!!)
    }
    infoPanel.addView(
        makeFullWidthButton(
            app,
            "Read EPUB",
            Btn.OUTLINED,
            R.drawable.wna_book_open,
            dp(Space.SM + 2),
            enabled =
                hasEpub && !isBusy,
        ) {
            openEpubForStory(story)
        },
    )
    if (story.epubStale == true && hasEpub) {
        infoPanel.addView(buildStaleEpubNotice(story, isBusy))
    }

    // Render the card for any Patreon URL, even without public stats: a link-only card surfaces the
    // creator's Patreon instead of showing nothing, which reads as having none.
    if (!story.patreonUrl.isNullOrBlank()) {
        infoPanel.addView(buildPatreonStatsCard(story.patreonStats, story.patreonUrl) { showTrends(story.id, FOCUS_PATREON_USD) })
    }

    val descriptionViews = addDetailsDescription(infoPanel, story, operation)
    if (operation?.kind == StoryOperationKind.AI_DESCRIPTION) {
        operationSlot = descriptionViews.aiOperationSlot
    }
    addDetailsTags(infoPanel, story)

    return DetailsInfoPanel(infoPanel, header.progressSummary, bannerSlot, downloadActionSlot, operationSlot, descriptionViews.listenButton)
}

/** Stable container whose children are swapped per tick by [renderStoryOperationProgress] without tearing down Details. */
internal fun makeStoryOperationSlot(
    context: android.content.Context,
    operation: StoryOperationState,
): LinearLayout =
    LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        renderStoryOperationProgress(this, operation)
    }

/** Result of [buildDetailsInfoPanel]: the panel plus the stable views the refresh loop patches. */
internal data class DetailsInfoPanel(
    val view: LinearLayout,
    /** Header's downloaded / total summary, patched as each chapter finishes. */
    val headerProgressSummary: android.view.View?,
    /** Live download banner slot, non-null only when the banner is shown. */
    val bannerSlot: LinearLayout?,
    /** "Download Remaining" action slot, non-null only when downloads can be queued. */
    val downloadActionSlot: LinearLayout?,
    /** In-flight operation progress slot, patched in place per tick; null when no operation runs. */
    val operationSlot: LinearLayout?,
    /** Description "Listen" button, patched in place by the description-TTS observer. */
    val descriptionTtsButton: Button?,
)

/**
 * Renders the active synopsis, source or AI, with copy, expand, and Listen. The slim AI progress
 * block stays here so backing out of AI Controls mid-generation still shows why the Details
 * buttons are disabled.
 */
private fun ScreenHost.addDetailsDescription(
    infoPanel: LinearLayout,
    story: Story,
    operation: StoryOperationState?,
): DetailsDescriptionViews {
    val activeDescription = AiDescriptionPlanning.activeDescription(story)
    val showingAi = AiDescriptionPlanning.isAiDescriptionActive(story)
    val generating = operation?.kind == StoryOperationKind.AI_DESCRIPTION
    if (activeDescription == null && !generating) return DetailsDescriptionViews(null, null)
    val descCol =
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(0, dp(Space.MD), 0, dp(Space.SM))
        }
    if (showingAi) {
        val aiBadge = makeBadge(app, "AI-generated", ThemeManager.colors.tertiaryContainer, ThemeManager.colors.onTertiaryContainer)
        aiBadge.layoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(Space.SM)
            }
        descCol.addView(aiBadge)
    }
    val listenButton = activeDescription?.let { addDescriptionTextAndListen(descCol, story, it) }
    val aiOperationSlot =
        if (generating && operation != null) {
            makeStoryOperationSlot(app, operation).also(descCol::addView)
        } else {
            null
        }
    infoPanel.addView(descCol)
    return DetailsDescriptionViews(listenButton, aiOperationSlot)
}

/** Views [addDetailsDescription] hands back to the Details screen builder. */
internal data class DetailsDescriptionViews(
    /** Description "Listen" button for the TTS observer; null when no description is displayed. */
    val listenButton: Button?,
    /** AI-generation progress slot, non-null only while an AI_DESCRIPTION operation is active. */
    val aiOperationSlot: LinearLayout?,
)

private fun ScreenHost.addDetailsTags(
    infoPanel: LinearLayout,
    story: Story,
) {
    story.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
        infoPanel.addView(
            WrapLayout(app).apply {
                horizontalSpacingDp = Space.SM
                verticalSpacingDp = Space.SM
                setPadding(0, dp(Space.MD), 0, dp(Space.MD))
                tags.forEach { tag ->
                    addView(makeBadge(app, tag, ThemeManager.colors.surfaceVariant, ThemeManager.colors.onSurfaceVariant))
                }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            },
        )
    }
}
