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
import com.vinicius741.webnovelarchiver.ui.DESCRIPTION_PREVIEW_LENGTH
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.WrapLayout
import com.vinicius741.webnovelarchiver.ui.copyToClipboard
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeBadge
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeFullWidthButton
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.toast
import com.vinicius741.webnovelarchiver.ui.truncateDescription

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
 * copy gestures, expand/collapse, and Listen, then the AI controls row. Returns the "Listen" button
 * (for the description-TTS observer) and the AI-operation progress slot.
 */
private fun ScreenHost.addDetailsDescription(
    infoPanel: LinearLayout,
    story: Story,
    operation: StoryOperationState?,
): DetailsDescriptionViews {
    val activeDescription = AiDescriptionPlanning.activeDescription(story)
    val showingAi = AiDescriptionPlanning.isAiDescriptionActive(story)
    // Without downloaded chapters there is no text to feed the model; archived snapshots are read-only.
    val canGenerate = story.isArchived != true && story.chapters.any { it.downloaded }
    if (activeDescription == null && !canGenerate && !showingAi) return DetailsDescriptionViews(null, null)
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
    val aiOperationSlot = addAiDescriptionControls(descCol, story, operation)
    infoPanel.addView(descCol)
    return DetailsDescriptionViews(listenButton, aiOperationSlot)
}

/** Renders the description text (copy gestures + expand/collapse) and its "Listen" button. */
private fun ScreenHost.addDescriptionTextAndListen(
    descCol: LinearLayout,
    story: Story,
    description: String,
): Button? {
    val canExpand = description.length > DESCRIPTION_PREVIEW_LENGTH
    var expanded = false
    val copyDescription = {
        copyToClipboard(story.title, description)
        toast("Description copied")
    }
    val descText =
        makeText(
            app,
            if (canExpand) truncateDescription(description) else description,
            Type.BODY_MEDIUM,
            ThemeManager.colors.onSurfaceVariant,
        ).apply {
            gravity = Gravity.START
            // Descriptions keep the source's paragraph/line structure (Sources.blockText).
            // Add a touch of inter-line spacing so the \n\n between paragraphs reads as a
            // real gap instead of a single break.
            setLineSpacing(dp(Space.XS).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            // Copy the description on a double-tap (mirrors RN StoryDescription's 300ms
            // double-press → Clipboard.setStringAsync) or on a long press. A ripple gives
            // touch feedback that the text is tappable.
            isClickable = true
            isLongClickable = true
            isFocusable = true
            background = selectableRipple(ThemeManager.colors.onSurface)
            var lastTap = 0L
            setOnClickListener {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastTap < DESCRIPTION_DOUBLE_TAP_COPY_WINDOW_MS) {
                    copyDescription()
                }
                lastTap = now
            }
            setOnLongClickListener {
                copyDescription()
                true
            }
        }
    descCol.addView(descText)
    // Description-TTS engagement lives directly under the text it speaks; the same button becomes
    // Pause / Resume while this story's session is active, and a docked transport bar (added by
    // showDetails) offers prev/next/stop. Hidden behind the truncated preview until expanded —
    // only the full description is worth listening to.
    // makeButton applies a 16dp horizontal padding (Space.LG) meant for chrome buttons. These
    // inline text buttons live flush against the description text, so that padding reads as a
    // stray indent pulling the labels off the screen's content edge. Strip the leading edge
    // (toggle) / trailing edge (Listen) so the labels line up with the description glyphs; keep
    // half on the inner side as a touch pad.
    val padV = dp(Space.SM + 2)
    val padH = dp(Space.LG)
    val listenButton = makeButton(app, "Listen", Btn.TEXT, R.drawable.wna_speaker) { toggleDescriptionTts(story) }
    // Keep the button compact: a default (MATCH_PARENT) width would push the icon to the far
    // edge while the label centers — a wide gap between them.
    listenButton.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    // Docked to the trailing edge: trim the trailing padding so the icon+label reach the
    // content edge like the description text, keep half on the inner side as a touch pad.
    listenButton.setPadding(padH / 2, padV, 0, padV)
    // Group the expand toggle and Listen button on a single row so Listen no longer floats
    // between the description text and the toggle. The toggle (the primary text control) sits
    // at the leading edge; Listen docks to the trailing edge so the two read as a balanced
    // pair of description actions.
    val actionsRow =
        LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
        }
    if (canExpand) {
        listenButton.visibility = View.GONE
        val toggle: Button = makeButton(app, "Read more", Btn.TEXT, 0) {}
        toggle.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        // Start-align the label so its glyphs share the description's leading edge; the
        // trailing padding remains a comfortable touch target.
        toggle.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        toggle.setPadding(0, padV, padH / 2, padV)
        toggle.setOnClickListener {
            expanded = !expanded
            descText.text = if (expanded) description else truncateDescription(description)
            toggle.text = if (expanded) "Read less" else "Read more"
            listenButton.visibility = if (expanded) View.VISIBLE else View.GONE
        }
        actionsRow.addView(toggle)
        // Flexible spacer so Listen docks to the trailing edge; it collapses to nothing while
        // Listen is GONE, leaving "Read more/less" at the leading edge. (Plain View, not
        // android.widget.Space, to avoid clashing with the ui.Space spacing tokens.)
        actionsRow.addView(
            View(app),
            LinearLayout.LayoutParams(0, 1, 1f),
        )
        actionsRow.addView(listenButton)
    } else {
        actionsRow.addView(listenButton)
    }
    descCol.addView(actionsRow)
    return listenButton
}

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

/**
 * Window (ms) within which two taps on the description copy it to the clipboard (300ms
 * double-press gesture).
 */
private const val DESCRIPTION_DOUBLE_TAP_COPY_WINDOW_MS = 300L
