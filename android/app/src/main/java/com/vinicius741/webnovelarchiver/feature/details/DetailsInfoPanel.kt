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

/**
 * Info-panel builder for the Details screen (Maintainability M1: split out of DetailsScreen.kt).
 * Assembles the single vertical column above the chapter list: header, primary actions (sync /
 * download / generate EPUB / read EPUB), the live download banner slot, Patreon card, expandable
 * description, and tags. Returns the panel plus the stable views the download-refresh loop in
 * [showDetails] patches in place after a progress event.
 *
 * @param operation the in-flight story operation, if any (drives the "Syncing..." / "Generating..."
 *   labels and the progress blocks).
 * @param downloadSummary reduced snapshot of this story's queue jobs for the download action + banner.
 */
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
    // Keep source-native facts in the same top summary area as score/progress. Placing them after a
    // long synopsis made unscored sources look as though no metadata had been captured at all.
    buildSourceMetadataFlow(story)?.let(infoPanel::addView)

    // Mutable slots the caller patches after download / story-operation progress events; non-null
    // only when rendered.
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
        // "Syncing..." label while a SYNC operation is in flight mirrors RN's
        // `{syncing ? "Syncing..." : "Sync Chapters"}`. The inline progress block is added below.
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
        // The live download banner lives in a stable slot view held by [bannerSlot]. The download
        // refresh loop swaps its child in place rather than rebuilding the screen. The slot is always
        // allocated when shown so we have a direct reference even while the header is scrolled
        // off-screen and the slot is detached from the window — patching a detached view is safe and
        // shows on reattach.
        bannerSlot =
            LinearLayout(app).apply {
                orientation = LinearLayout.VERTICAL
                addView(makeDownloadProgressBanner(app, downloadSummary, pacingStatus) { showQueue() })
            }
        infoPanel.addView(bannerSlot!!)
    }
    if (operation?.kind == StoryOperationKind.CLEANUP) {
        // Stable slot: cleanup progress ticks update message/bar in place rather than calling
        // showDetails() per chapter (which rebuilt the whole tree and flickered).
        operationSlot = makeStoryOperationSlot(app, operation)
        infoPanel.addView(operationSlot!!)
    }
    val hasEpub = (!story.epubPaths.isNullOrEmpty()) || !story.epubPath.isNullOrBlank()
    // D2: Generate EPUB is the primary action — promote it to a full-width button so its visual
    // weight matches its usage.
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
    // Read EPUB is now a full-width outlined button so it aligns with the other primary actions.
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
    // D6: make the stale notice actionable with an inline Regenerate button.
    if (story.epubStale == true && hasEpub) {
        infoPanel.addView(buildStaleEpubNotice(story, isBusy))
    }

    // Render the Patreon card whenever the story has a Patreon URL, even if the public stats
    // could not be fetched: a link-only card surfaces that the creator has a Patreon, instead of
    // silently showing nothing (which would be indistinguishable from having no Patreon).
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

/**
 * Stable container for an in-flight story-operation progress block. Children are swapped by
 * [renderStoryOperationProgress] on subsequent ticks without tearing down Details.
 */
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
    /**
     * In-flight story-operation progress slot (sync / cleanup / EPUB). Non-null only while an
     * operation for this story is active. Held as a direct reference so progress ticks can patch
     * the message/bar without rebuilding Details (see [renderStoryOperationProgress]).
     */
    val operationSlot: LinearLayout?,
    /**
     * Description "Listen" button, non-null when the story has a description. Patched in place by
     * the description-TTS observer ([observeDetailsDescriptionTts]) as playback state changes.
     */
    val descriptionTtsButton: Button?,
)

/**
 * Renders the active synopsis (source or AI — see [AiDescriptionPlanning.activeDescription]) with
 * copy gestures, expand/collapse, and Listen. AI actions live on the AI Controls screen; the only AI
 * UI left here is a slim progress block while an AI description is generating (so a user who backs
 * out of AI Controls mid-generation still sees why the Details buttons are disabled). Returns the
 * "Listen" button (for the description-TTS observer) and that progress slot.
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

/** Tag chips row (new on native; were missing). */
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
