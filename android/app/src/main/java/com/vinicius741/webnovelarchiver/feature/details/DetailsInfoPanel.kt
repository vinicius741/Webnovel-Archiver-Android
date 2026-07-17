package com.vinicius741.webnovelarchiver.feature.details

import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.EpubConfig
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.StoryActionGuards
import com.vinicius741.webnovelarchiver.download.DownloadDetailsPlanning
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
import com.vinicius741.webnovelarchiver.ui.disableButton
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
internal fun ScreenHost.buildDetailsInfoPanel(
    story: Story,
    operation: StoryOperationState?,
    downloadSummary: DownloadDetailsPlanning.StoryDownloadSummary,
): DetailsInfoPanel {
    val isBusy = operation != null
    val infoPanel = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
    val header = buildDetailsHeader(story)
    infoPanel.addView(header.view)

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
    if (StoryActionGuards.canSync(story)) {
        // "Syncing..." label while a SYNC operation is in flight mirrors RN's
        // `{syncing ? "Syncing..." : "Sync Chapters"}`. The inline progress block is added below.
        val syncLabel = if (operation?.kind == StoryOperationKind.SYNC) "Syncing..." else "Sync Chapters"
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
                addView(makeDownloadProgressBanner(app, downloadSummary) { showQueue() })
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

    addDetailsDescription(infoPanel, story)
    addDetailsTags(infoPanel, story)

    return DetailsInfoPanel(infoPanel, header.progressSummary, bannerSlot, downloadActionSlot, operationSlot)
}

/**
 * Stable container for an in-flight story-operation progress block. Children are swapped by
 * [renderStoryOperationProgress] on subsequent ticks without tearing down Details.
 */
private fun makeStoryOperationSlot(
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
)

/**
 * Inline expand/collapse description (mirrors RN StoryDescription). Copy on double-tap or long-press.
 */
private fun ScreenHost.addDetailsDescription(
    infoPanel: LinearLayout,
    story: Story,
) {
    story.description?.takeIf { it.isNotBlank() }?.let { description ->
        val canExpand = description.length > DESCRIPTION_PREVIEW_LENGTH
        var expanded = false
        val descCol =
            LinearLayout(app).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.START
                setPadding(0, dp(Space.SM), 0, dp(Space.XS))
            }
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
        if (canExpand) {
            val toggle: Button = makeButton(app, "Read more", Btn.TEXT, 0) {}
            toggle.setOnClickListener {
                expanded = !expanded
                descText.text = if (expanded) description else truncateDescription(description)
                toggle.text = if (expanded) "Show less" else "Read more"
            }
            descCol.addView(toggle)
        }
        infoPanel.addView(descCol)
    }
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
                setPadding(0, dp(Space.SM), 0, dp(Space.XS))
                tags.forEach { tag ->
                    addView(makeBadge(app, tag, ThemeManager.colors.surfaceVariant, ThemeManager.colors.onSurfaceVariant))
                }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            },
        )
    }
}

/** "EPUB out of date" notice with an inline Regenerate button (D6). */
private fun ScreenHost.buildStaleEpubNotice(
    story: Story,
    isBusy: Boolean,
): LinearLayout =
    LinearLayout(app).apply {
        orientation = LinearLayout.VERTICAL
        // Center every child horizontally so the stale label (match_parent) and the
        // wrap-content Regenerate button line up on the same screen-center axis.
        gravity = Gravity.CENTER_HORIZONTAL
        addView(
            // LABEL_MEDIUM keeps BODY_SMALL's 12f size but renders bold, matching the
            // bold Regenerate button beneath it.
            makeText(app, "EPUB out of date", Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant).apply {
                // Fill the panel width so the text is truly centered across the screen,
                // not just within a wrap-content label.
                gravity = Gravity.CENTER
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
            },
        )
        val regenerateButton =
            makeButton(app, "Regenerate", Btn.TEXT, R.drawable.wna_refresh) {
                val config =
                    story.epubConfig ?: EpubConfig(
                        maxChaptersPerEpub = repository.getSettings().maxChaptersPerEpub,
                        rangeStart = 1,
                        rangeEnd = story.chapters.size,
                        startAtBookmark = false,
                    )
                generateConfiguredEpub(story, config)
            }
        if (isBusy) disableButton(regenerateButton)
        addView(
            regenerateButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin =
                    dp(Space.XS)
            },
        )
    }

/**
 * Window (ms) within which two taps on the description copy it to the clipboard (300ms
 * double-press gesture).
 */
private const val DESCRIPTION_DOUBLE_TAP_COPY_WINDOW_MS = 300L
