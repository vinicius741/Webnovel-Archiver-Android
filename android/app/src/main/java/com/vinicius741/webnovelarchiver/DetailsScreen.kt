package com.vinicius741.webnovelarchiver

import android.content.Intent
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.vinicius741.webnovelarchiver.core.Chapter
import com.vinicius741.webnovelarchiver.core.ChapterFilterSettings
import com.vinicius741.webnovelarchiver.core.DownloadDetailsPlanning
import com.vinicius741.webnovelarchiver.core.EpubConfig
import com.vinicius741.webnovelarchiver.core.SourceRegistry
import com.vinicius741.webnovelarchiver.core.Story
import com.vinicius741.webnovelarchiver.core.StoryActionGuards
import com.vinicius741.webnovelarchiver.core.StoryBookmarkPlanning
import com.vinicius741.webnovelarchiver.tts.TtsForegroundService
import com.vinicius741.webnovelarchiver.ui.*

internal fun ScreenHost.showDetails(storyId: String) {
    val story = storage.getStory(storyId) ?: return showLibrary()
    val screenKey = "${story.title}|by ${story.author}"
    val previousListState = if (frame.tag == screenKey) {
        findDetailsChapterList(frame)?.layoutManager?.onSaveInstanceState()
    } else {
        null
    }
    // Stable reference to the download banner slot. Captured into the refresh closure so the loop
    // patches it in place even when the header is scrolled off-screen (the slot detaches from the
    // window but the reference stays valid). Allocated lazily by the banner block below.
    var bannerSlot: ViewGroup? = null
    activeStory = story
    // Re-render on fold/unfold/rotation so the two-pane ↔ single-scroll layout can switch live.
    rerender = { showDetails(storyId) }
    val layout = currentScreenLayout()
    val operation = storyOperation?.takeIf { it.storyId == story.id }
    val isBusy = operation != null
    // Live download feedback: reduce this story's queue jobs once per render. The banner + the
    // per-chapter spinners are driven from this, and the auto-refresh loop below re-runs
    // showDetails while [downloadSummary.isActive] so the counts/climb in real time.
    val jobsForStory = storage.getQueue().filter { it.storyId == story.id }
    val downloadSummary = DownloadDetailsPlanning.summarizeStoryDownload(jobsForStory)
    val chapterStatuses = DownloadDetailsPlanning.chapterJobStatuses(jobsForStory)
    // Show the banner while work is in flight/paused, or if the last batch ended with failures so
    // the user sees the error summary. An all-completed batch is dismissed (the static header
    // progress + per-row "Available Offline" already reflect the result).
    val showDownloadBanner = shouldShowDetailsBanner(downloadSummary)
    screen(
        title = story.title,
        subtitle = "by ${story.author}",
        onBack = { showLibrary() },
        actions = listOf(AppBarAction(R.drawable.wna_more_vert, "More options") { showDetailsOverflow(story) }),
        // The chapter RecyclerView must be the scrolling surface. Wrapping it in a ScrollView makes
        // Android measure and inflate every chapter row, defeating recycling for large novels.
        scrollable = false,
    ) {
        // ---- Info panel: header + actions + description + tags ----
        val infoPanel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        infoPanel.addView(buildDetailsHeader(story))

        if (story.isArchived == true) {
            infoPanel.addView(makeText(context, "Archived snapshot: sync and downloads disabled", Type.LABEL_MEDIUM, ThemeManager.colors.tertiary).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(Space.SM), 0, dp(Space.XS))
            })
        }
        if (StoryActionGuards.canSync(story)) {
            // "Syncing..." label while a SYNC operation is in flight mirrors RN's
            // `{syncing ? "Syncing..." : "Sync Chapters"}`. The inline progress block is added below.
            val syncLabel = if (operation?.kind == StoryOperationKind.SYNC) "Syncing..." else "Sync Chapters"
            infoPanel.addView(makeFullWidthButton(context, syncLabel, Btn.FILLED, R.drawable.wna_refresh, dp(Space.SM + 2), enabled = !isBusy) { syncStory(story) })
        }
        if (operation?.kind == StoryOperationKind.SYNC) {
            infoPanel.addView(makeStoryOperationProgress(context, operation, indeterminate = true))
        }
        if (StoryActionGuards.canQueueDownloads(story)) {
            val remainingChapters = story.chapters.count { !it.downloaded }
            if (remainingChapters > 0) {
                val downloadLabel = if (remainingChapters == story.chapters.size) "Download All" else "Download Remaining ($remainingChapters)"
                // Disable while a download is already running for this story (and during any blocking
                // operation) so the user can't enqueue a duplicate batch — mirrors RN's disabled={downloading}.
                infoPanel.addView(makeFullWidthButton(context, downloadLabel, Btn.FILLED, R.drawable.wna_download, dp(Space.SM + 2), enabled = !isBusy && !downloadSummary.isActive) {
                    queueDownload(story, story.chapters.mapIndexedNotNull { index, chapter -> if (!chapter.downloaded) index else null })
                })
            }
        }
        if (showDownloadBanner) {
            // The live download banner lives in a stable slot view held by [bannerSlot] (a local
            // captured into the refresh closure below). refreshDetailsDownload() swaps its child in
            // place rather than rebuilding the screen. The slot is always allocated when shown so we
            // have a direct reference even while the header is scrolled off-screen and the slot is
            // detached from the window — patching a detached view is safe and shows on reattach.
            bannerSlot = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(makeDownloadProgressBanner(context, downloadSummary) { showQueue() })
            }
            infoPanel.addView(bannerSlot!!)
        }
        if (operation?.kind == StoryOperationKind.CLEANUP) {
            infoPanel.addView(makeStoryOperationProgress(context, operation, indeterminate = true))
        }
        val hasEpub = (!story.epubPaths.isNullOrEmpty()) || !story.epubPath.isNullOrBlank()
        // D2: Generate EPUB is the primary action — promote it to a full-width button so its visual
        // weight matches its usage.
        val generateLabel = if (operation?.kind == StoryOperationKind.EPUB) "Generating..." else "Generate EPUB"
        infoPanel.addView(makeFullWidthButton(context, generateLabel, Btn.TONAL, R.drawable.wna_menu_book, dp(Space.SM + 2), enabled = story.downloadedChapters > 0 && !isBusy) {
            val config = story.epubConfig ?: EpubConfig(
                maxChaptersPerEpub = storage.getSettings().maxChaptersPerEpub,
                rangeStart = 1,
                rangeEnd = story.chapters.size,
                startAfterBookmark = false,
            )
            generateConfiguredEpub(story, config)
        })
        if (operation?.kind == StoryOperationKind.EPUB) {
            infoPanel.addView(makeStoryOperationProgress(context, operation, indeterminate = operation.progress == null))
        }
        // Read EPUB is now a full-width outlined button so it aligns with the other primary actions.
        infoPanel.addView(makeFullWidthButton(context, "Read EPUB", Btn.OUTLINED, R.drawable.wna_book_open, dp(Space.SM + 2), enabled = hasEpub && !isBusy) {
            openEpubForStory(story)
        })
        // D6: make the stale notice actionable with an inline Regenerate button.
        if (story.epubStale == true && hasEpub) {
            infoPanel.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(makeText(context, "EPUB out of date", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant))
                val regenerateButton = makeButton(context, "Regenerate", Btn.TEXT, R.drawable.wna_refresh) {
                    val config = story.epubConfig ?: EpubConfig(
                        maxChaptersPerEpub = storage.getSettings().maxChaptersPerEpub,
                        rangeStart = 1,
                        rangeEnd = story.chapters.size,
                        startAfterBookmark = false,
                    )
                    generateConfiguredEpub(story, config)
                }
                if (isBusy) disableButton(regenerateButton)
                addView(regenerateButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(Space.SM) })
            })
        }

        // ---- Description (inline expand/collapse, mirrors RN StoryDescription) ----
        story.description?.takeIf { it.isNotBlank() }?.let { description ->
            val canExpand = description.length > DESCRIPTION_PREVIEW_LENGTH
            var expanded = false
            val descCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.START
                setPadding(0, dp(Space.SM), 0, dp(Space.XS))
            }
            val descText = makeText(context, if (canExpand) truncateDescription(description) else description, Type.BODY_MEDIUM, ThemeManager.colors.onSurfaceVariant).apply {
                gravity = Gravity.START
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            descCol.addView(descText)
            if (canExpand) {
                val toggle: Button = makeButton(context, "Read more", Btn.TEXT, 0) {}
                toggle.setOnClickListener {
                    expanded = !expanded
                    descText.text = if (expanded) description else truncateDescription(description)
                    toggle.text = if (expanded) "Show less" else "Read more"
                }
                descCol.addView(toggle)
            }
            infoPanel.addView(descCol)
        }

        // ---- Tags (new; were missing on native) ----
        story.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
            infoPanel.addView(WrapLayout(context).apply {
                horizontalSpacingDp = Space.SM
                verticalSpacingDp = Space.SM
                setPadding(0, dp(Space.SM), 0, dp(Space.XS))
                tags.forEach { tag ->
                    addView(makeBadge(context, tag, ThemeManager.colors.surfaceVariant, ThemeManager.colors.onSurfaceVariant))
                }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
        }

        // ---- Chapter filter (search + chips) ----
        val chapterControls = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val search = makeSearchField(context, "Search chapters")
        chapterControls.addView(search)
        val chipsContainer = WrapLayout(context).apply {
            horizontalSpacingDp = Space.SM
            verticalSpacingDp = Space.SM
            setPadding(0, dp(Space.SM), 0, dp(Space.SM))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        chapterControls.addView(chipsContainer)

        // ---- Chapter List (S1: RecyclerView so novels with hundreds/thousands of chapters recycle
        // views instead of inflating one row each on every render/filter tick) ----
        val chaptersContainer = androidx.recyclerview.widget.RecyclerView(context).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
            setHasFixedSize(false)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        // Compact keeps one continuous scroll by exposing details and controls as the list's first
        // item. Two-pane keeps controls fixed above the independently scrolling chapter list.
        val listHeader: View? = if (!layout.isTwoPane) {
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                var infoExpanded = true
                val collapseToggle = makeButton(context, "Hide details", Btn.TEXT, R.drawable.wna_chevron_down) {}
                collapseToggle.setOnClickListener {
                    infoExpanded = !infoExpanded
                    infoPanel.visibility = if (infoExpanded) View.VISIBLE else View.GONE
                    collapseToggle.text = if (infoExpanded) "Hide details" else "Show details"
                }
                addView(collapseToggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(Space.XS) })
                addView(infoPanel)
                addView(makeDivider(context))
                addView(chapterControls)
            }
        } else {
            null
        }

        val hasBookmark = story.lastReadChapterId != null && story.chapters.any { it.id == story.lastReadChapterId }
        var chapterFilter = storage.getChapterFilterSettings().filterMode
        var chapterQuery = ""

        // Chips are rebuilt on every pick so the active one re-highlights (the original bug was
        // that chips were built once and never reflected the tapped selection).
        var pick: (String) -> Unit = {}
        pick = { mode ->
            chapterFilter = mode
            storage.saveChapterFilterSettings(ChapterFilterSettings(mode))
            renderFilterChips(chipsContainer, chapterFilter, hasBookmark, pick)
            renderChapterList(story, chaptersContainer, chapterQuery, chapterFilter, chapterStatuses, listHeader)
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                chapterQuery = s?.toString().orEmpty()
                renderChapterList(story, chaptersContainer, chapterQuery, chapterFilter, chapterStatuses, listHeader)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        renderFilterChips(chipsContainer, chapterFilter, hasBookmark, pick)
        renderChapterList(story, chaptersContainer, chapterQuery, chapterFilter, chapterStatuses, listHeader)

        if (layout.isTwoPane) {
            // Two-pane: info scrolls on the left, chapter list scrolls on the right. The info
            // pane stays pinned at a fixed width (RN StoryDetailsLayout: 280–440dp) while the chapter
            // list takes the remaining space, each with its own scroll surface. No divider is drawn
            // between the panes — a marginEnd on the info pane keeps the columns from touching.
            val leftScroll = scroll(infoPanel)
            val rightPane = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(chapterControls)
                addView(chaptersContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            }
            val shell = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(leftScroll, LinearLayout.LayoutParams(dp(DETAILS_TWO_PANE_LEFT_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    marginEnd = dp(DETAILS_TWO_PANE_GAP_DP)
                })
                addView(rightPane, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            }
            addView(shell, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } else {
            addView(chaptersContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        previousListState?.let { state ->
            chaptersContainer.post { chaptersContainer.layoutManager?.onRestoreInstanceState(state) }
        }
    }
    // Real-time refresh: while this story has active downloads, patch the chapter rows + banner in
    // place on a short cadence so the progress bar climbs and chapter rows flip to spinners /
    // "Available Offline" as the foreground service completes them. This uses
    // [refreshDetailsDownload] instead of showDetails() so the whole view tree is NOT torn down on
    // every tick — that earlier path caused a visible flicker (blank frame → scroll snap-back) on
    // each refresh. Storage is the single source of truth (R3 single-owner already serializes queue
    // writes), so a fresh re-read each tick is correct and needs no service↔activity callback. Tag-
    // guarded so the loop stops itself if the user navigates away or the batch finishes.
    if (downloadSummary.isActive && frame.childCount > 0) {
        val root = frame.getChildAt(0)
        root.tag = DETAILS_DOWNLOAD_TAG
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val refresh = object : Runnable {
            override fun run() {
                if ((root.tag as? String) != DETAILS_DOWNLOAD_TAG || root.parent !== frame) return
                val chapterList = findDetailsChapterList(root)
                // Defer the patch if a touch/fling gesture is mid-flight so the row rebind doesn't
                // interrupt the user's scroll. Once idle, patch in place.
                if (chapterList?.scrollState != androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) {
                    handler.postDelayed(this, DETAILS_SCROLL_RETRY_MS)
                    return
                }
                if (refreshDetailsDownload(storyId, bannerSlot)) {
                    handler.postDelayed(this, DETAILS_REFRESH_MS)
                }
            }
        }
        handler.postDelayed(refresh, DETAILS_REFRESH_MS)
    }
}

/** Auto-refresh cadence (ms) for the Details screen while a download is active. Short enough that
 *  the progress bar + spinners feel live, long enough to avoid thrashing the view tree. */
private const val DETAILS_REFRESH_MS = 1200L
/** Avoid replacing the RecyclerView hierarchy while a touch/fling gesture is still active. */
private const val DETAILS_SCROLL_RETRY_MS = 250L
/** Tag stamped on the Details root view so the download refresher can detect navigation away. */
private const val DETAILS_DOWNLOAD_TAG = "details-download"

/**
 * Decides whether the live download banner should be shown for a story's current queue summary.
 * Shared by [showDetails] (initial render) and [refreshDetailsDownload] (in-place refresh) so the
 * banner appears/disappears by the same rule on the periodic refresh tick.
 */
private fun shouldShowDetailsBanner(summary: DownloadDetailsPlanning.StoryDownloadSummary): Boolean =
    summary.total > 0 &&
        (summary.isActive || summary.isPaused || (summary.isFinished && (summary.failed > 0 || summary.cancelled > 0)))

/**
 * Handles the periodic Details download refresh *without* rebuilding the screen: reads the latest
 * queue + story, then patches the chapter adapter (per-row status flip to spinner/dot/"Available
 * Offline") and swaps the banner slot's contents in place. This replaces the old
 * `showDetails(storyId)` full-screen re-render — tearing down the whole view tree every ~1.2s while
 * downloading caused a visible flicker (blank frame while the new tree inflated, then scroll
 * snapped back and was restored).
 *
 * [bannerSlot] is a direct reference to the slot view captured at [showDetails] render time, NOT a
 * tree lookup. In compact layout the slot lives inside the RecyclerView's header item, which the
 * LayoutManager recycles (detaches from the window) once the user scrolls past it. A tree walk
 * (`findViewByTag`) would miss the recycled/detached slot and trigger a full rebuild on every tick
 * — the exact flicker this fixes. The direct reference stays valid while detached: patching it is
 * safe and the change shows when the header scrolls back into view.
 *
 * Returns true while the story still has live download work (so the loop should keep refreshing);
 * false once the batch is fully terminal, so the loop can stop (the next navigation will rebuild).
 */
private fun ScreenHost.refreshDetailsDownload(storyId: String, bannerSlot: ViewGroup?): Boolean {
    val story = storage.getStory(storyId) ?: return false
    val jobsForStory = storage.getQueue().filter { it.storyId == storyId }
    val summary = DownloadDetailsPlanning.summarizeStoryDownload(jobsForStory)
    val chapterStatuses = DownloadDetailsPlanning.chapterJobStatuses(jobsForStory)

    // Patch the chapter rows in place via the adapter's update(), which reuses view holders
    // (notifyDataSetChanged) instead of inflating a new tree.
    val chapterList = findDetailsChapterList(frame)
    val adapter = chapterList?.adapter?.let { it as? ChapterListAdapter ?: (it as? androidx.recyclerview.widget.ConcatAdapter)?.adapters?.filterIsInstance<ChapterListAdapter>()?.singleOrNull() }
    if (adapter != null) {
        val query = adapter.currentQuery()
        val filter = adapter.currentFilter()
        val filtered = filterDetailsChapters(story, query, filter)
        val isEmptyState = filtered.isEmpty()
        val displayed = if (isEmptyState) listOf(-1 to Chapter(title = "No chapters match this view.")) else filtered
        adapter.update(displayed, story, isEmptyState, query, filter, chapterStatuses)
    }

    // Patch the banner slot in place. The slot reference is stable across header recycling; if the
    // batch is no longer eligible for a banner, just empty the slot (its parent — or the recycled
    // header — keeps it). No showDetails() fallback: the next navigation/render rebuilds naturally.
    if (bannerSlot != null) {
        if (shouldShowDetailsBanner(summary)) {
            bannerSlot.removeAllViews()
            bannerSlot.addView(makeDownloadProgressBanner(app, summary) { showQueue() })
        } else {
            bannerSlot.removeAllViews()
        }
    }

    return summary.isActive || summary.isPaused
}

private fun findDetailsChapterList(root: View): androidx.recyclerview.widget.RecyclerView? {
    if (root is androidx.recyclerview.widget.RecyclerView) return root
    if (root is ViewGroup) {
        for (index in 0 until root.childCount) {
            findDetailsChapterList(root.getChildAt(index))?.let { return it }
        }
    }
    return null
}

private fun makeStoryOperationProgress(context: Context, operation: StoryOperationState, indeterminate: Boolean): LinearLayout {
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, 0, 0, context.dp(Space.MD))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        if (indeterminate || operation.progress == null) {
            addView(ProgressBar(context).apply {
                indeterminateTintList = ColorStateList.valueOf(ThemeManager.colors.primary)
                layoutParams = LinearLayout.LayoutParams(context.dp(28), context.dp(28)).apply {
                    bottomMargin = context.dp(Space.SM)
                }
            })
        } else {
            addView(makeProgress(context, operation.progress).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(6)).apply {
                    bottomMargin = context.dp(Space.SM)
                }
            })
        }

        addView(makeText(context, operation.message, Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
    }
}

/**
 * Live download progress banner for the Details info panel — the native counterpart of the RN
 * `StoryActions` progress block. Shows a determinate bar (fraction of jobs completed) plus a status
 * headline ("Downloading: <chapter> (7/20)", "Queued (…)", "Paused (…)", or the finished summary)
 * and a "Go to Downloads" link. The bar is indeterminate only while the batch is queued with zero
 * progress; once anything completes it goes determinate so the user sees real movement.
 */
private fun makeDownloadProgressBanner(
    context: Context,
    summary: DownloadDetailsPlanning.StoryDownloadSummary,
    onViewDownloads: () -> Unit,
): LinearLayout {
    val headline = DownloadDetailsPlanning.headline(summary)
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, context.dp(Space.XS), 0, context.dp(Space.MD))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        // Indeterminate while purely queued (no completions yet); determinate once work finishes so
        // the bar visibly advances as chapters complete.
        if (summary.completed == 0 && summary.isActive) {
            addView(ProgressBar(context).apply {
                indeterminateTintList = ColorStateList.valueOf(ThemeManager.colors.primary)
                layoutParams = LinearLayout.LayoutParams(context.dp(24), context.dp(24)).apply {
                    bottomMargin = context.dp(Space.XS)
                }
            })
        } else {
            addView(makeProgress(context, summary.progress).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(6)).apply {
                    bottomMargin = context.dp(Space.XS)
                }
            })
        }
        addView(makeText(context, headline, Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        addView(makeButton(context, "Go to Downloads", Btn.TEXT, R.drawable.wna_list) { onViewDownloads() }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(2)
            }
        })
    }
}

/** Fixed width (dp) of the left info pane in the two-pane details layout, within the RN range. */
private const val DETAILS_TWO_PANE_LEFT_WIDTH_DP = 360

/** Gap (dp) between the info pane and the chapter list in the two-pane layout, replacing the old
 *  1dp divider with clear whitespace. */
private const val DETAILS_TWO_PANE_GAP_DP = Space.MD

/**
 * Overflow menu behind the app-bar "more" icon. Holds the secondary/tertiary story actions that
 * don't warrant a primary button: opening the source site, the two advanced download-selection
 * flows, and text cleanup. Mirrors the React Native StoryMenu (Download Range / EPUB Settings /
 * Apply Text Cleanup) plus Open Source.
 */
internal fun ScreenHost.showDetailsOverflow(story: Story) {
    val isBusy = storyOperation?.storyId == story.id
    val options = mutableListOf<Pair<String, () -> Unit>>()
    options += "Open Source" to {
        runCatching { app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(story.sourceUrl))) }
            .onFailure { toast("No app available to open source") }
    }
    if (StoryActionGuards.canQueueDownloads(story)) {
        options += "Select Chapters" to {
            if (isBusy) toast("Please wait for the current operation to finish") else showChapterSelection(story.id)
        }
        options += "Download Range" to {
            if (isBusy) toast("Please wait for the current operation to finish") else showDownloadRangeDialog(story)
        }
    }
    options += "EPUB Settings" to {
        if (isBusy) toast("Please wait for the current operation to finish") else showEpubConfigDialog(story)
    }
    options += "Apply Text Cleanup" to {
        if (isBusy) toast("Please wait for the current operation to finish") else applyCleanup(story)
    }
    options += "Delete Novel" to {
        if (isBusy) {
            toast("Please wait for the current operation to finish")
        } else {
            confirm("Delete \"${story.title}\"? This action cannot be undone.") {
                storage.deleteStory(story.id)
                showLibrary()
            }
        }
    }
    showStyledOptionsDialog("More options", options)
}

internal fun ScreenHost.showChapterSelection(storyId: String, initialSelectedIds: Set<String> = emptySet()) {
    val story = storage.getStory(storyId) ?: return showLibrary()
    if (!StoryActionGuards.canQueueDownloads(story)) {
        toast(StoryActionGuards.archivedActionMessage("Downloading"))
        return showDetails(story.id)
    }
    val selectedIds = initialSelectedIds.toMutableSet()
    val downloadable = story.chapters.filter { !it.downloaded }
    screen(title = "Select Chapters", subtitle = story.title, onBack = { showDetails(story.id) }) {
        var refreshBulkActions: () -> Unit = {}
        // X3: select-all / deselect-all affordance for fast bulk selection.
        flow {
            button("Select All", Btn.TEXT, R.drawable.wna_check, enabled = downloadable.isNotEmpty()) {
                selectedIds.clear()
                selectedIds.addAll(downloadable.map { it.id })
                showChapterSelection(story.id, selectedIds)
            }
            button("Deselect All", Btn.TEXT, R.drawable.wna_close, enabled = downloadable.isNotEmpty()) {
                selectedIds.clear()
                showChapterSelection(story.id, selectedIds)
            }
        }
        addView(scroll(LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            // X1: reuse the card-style selectable row instead of bare CheckBoxes.
            if (downloadable.isEmpty()) {
                addView(makeEmptyState(context, "All chapters are already downloaded.", R.drawable.wna_check))
            } else {
                downloadable.forEach { chapter ->
                    val displayIndex = story.chapters.indexOfFirst { it.id == chapter.id } + 1
                    addView(makeSelectableCardRow(
                        context,
                        title = "$displayIndex. ${sanitizeTitle(chapter.title)}",
                        subtitle = if (chapter.downloaded) "Available Offline" else null,
                        selected = selectedIds.contains(chapter.id),
                    ) { checked ->
                        if (checked) selectedIds.add(chapter.id) else selectedIds.remove(chapter.id)
                        refreshBulkActions()
                    })
                }
            }
        }), verticalFill())
        // X2: the primary bulk action is a full-width CTA docked at the bottom, next to the items.
        val downloadButton = fullButton("Download ${selectedIds.size} Selected", Btn.FILLED, R.drawable.wna_download, enabled = downloadable.isNotEmpty(), bottomMarginDp = 0) {
            val selectedIndexes = story.chapters.mapIndexedNotNull { index, chapter ->
                if (selectedIds.contains(chapter.id) && !chapter.downloaded) index else null
            }
            if (selectedIndexes.isEmpty()) {
                toast("No undownloaded chapters selected")
            } else {
                queueDownload(story, selectedIndexes)
            }
        }
        refreshBulkActions = {
            downloadButton.text = "Download ${selectedIds.size} Selected"
        }
        refreshBulkActions()
    }
}

internal fun ScreenHost.renderChapterList(
    story: Story,
    list: androidx.recyclerview.widget.RecyclerView,
    query: String,
    filter: String,
    chapterStatuses: Map<String, com.vinicius741.webnovelarchiver.core.DownloadJobStatus> = emptyMap(),
    header: View? = null,
) {
    val displayedChapters = filterDetailsChapters(story, query, filter)
    val isEmptyState = displayedChapters.isEmpty()
    val existingChapterAdapter = when (val adapter = list.adapter) {
        is ChapterListAdapter -> adapter
        is androidx.recyclerview.widget.ConcatAdapter -> adapter.adapters.filterIsInstance<ChapterListAdapter>().singleOrNull()
        else -> null
    }
    if (existingChapterAdapter != null) {
        existingChapterAdapter.update(
            if (isEmptyState) listOf(-1 to Chapter(title = "No chapters match this view.")) else displayedChapters,
            story,
            isEmptyState,
            query,
            filter,
            chapterStatuses,
        )
        return
    }
    val initialChapters = if (isEmptyState) listOf(-1 to Chapter(title = "No chapters match this view.")) else displayedChapters
    val chapterAdapter = ChapterListAdapter(this, initialChapters, story, isEmptyState, list, query, filter, chapterStatuses)
    list.adapter = if (header == null) chapterAdapter else androidx.recyclerview.widget.ConcatAdapter(DetailsHeaderAdapter(header), chapterAdapter)
}

/**
 * Pure chapter filter for the Details list — shared by [renderChapterList] (initial + filter/search)
 * and the in-place download refresh so both apply the identical view. Returns the matching
 * `(chapterIndex, chapter)` pairs, or an empty list when nothing matches (the caller decides
 * whether to substitute the "No chapters match" empty state).
 */
private fun filterDetailsChapters(
    story: Story,
    query: String,
    filter: String,
): List<Pair<Int, Chapter>> {
    val bookmarkIndex = story.lastReadChapterId?.let { id -> story.chapters.indexOfFirst { it.id == id } } ?: -1
    return story.chapters
        .mapIndexed { index, chapter -> index to chapter }
        .filter { (_, chapter) -> chapter.title.contains(query, ignoreCase = true) }
        .filter { (index, chapter) ->
            when (filter) {
                "hideNonDownloaded" -> chapter.downloaded
                "hideAboveBookmark" -> bookmarkIndex < 0 || index >= bookmarkIndex
                else -> true
            }
        }
}


/** Per-row overflow: Read, conditional Download, Mark as Read ⇄ Clear, Read Aloud (TTS). */
internal fun ScreenHost.showChapterActions(
    story: Story,
    chapter: Chapter,
    index: Int,
    list: androidx.recyclerview.widget.RecyclerView,
    query: String,
    filter: String,
) {
    val options = mutableListOf<Pair<String, () -> Unit>>()
    options += "Read" to { showReader(story.id, chapter.id) }
    if (!chapter.downloaded && StoryActionGuards.canQueueDownloads(story)) {
        options += "Download" to {
            queueDownload(story, listOf(index))
        }
    }
    val isBookmarked = story.lastReadChapterId == chapter.id
    options += (if (isBookmarked) "Clear Bookmark" else "Mark as Read") to {
        val updated = StoryBookmarkPlanning.withBookmark(story, chapter.id, toggleExisting = true)
        storage.addOrUpdateStory(updated)
        renderChapterList(updated, list, query, filter)
    }
    options += "Read Aloud (TTS)" to { TtsForegroundService.start(app, story.id, chapter.id) }
    showStyledOptionsDialog(sanitizeTitle(chapter.title), options)
}

/**
 * Rebuilds the filter chip group so the active mode re-highlights on every pick. The "From Bookmark"
 * chip is disabled when there is no valid bookmark, mirroring RN's `disabled={!hasBookmark}`.
 */
internal fun ScreenHost.renderFilterChips(
    container: ViewGroup,
    current: String,
    hasBookmark: Boolean,
    onPick: (String) -> Unit,
) {
    container.removeAllViews()
    container.addView(makeChip(app, "All", current == "all") { onPick("all") })
    container.addView(makeChip(app, "Downloaded", current == "hideNonDownloaded") { onPick("hideNonDownloaded") })
    val bookmarkChip = makeChip(app, "From Bookmark", current == "hideAboveBookmark") { onPick("hideAboveBookmark") }
    if (!hasBookmark) {
        bookmarkChip.isEnabled = false
        bookmarkChip.alpha = 0.4f
    }
    container.addView(bookmarkChip)
}

/** Centered story header — cover, title, author, source/archived chips, and a compact
 *  "Saved / Chapters" progress summary. Mirrors the RN `StoryHeader`; D5 collapsed the three
 *  duplicate stat pills (Score/Chapters/Saved) — Score already shows in the tags row and the
 *  chapter total is implied by the list — into one progress summary. */
private fun ScreenHost.buildDetailsHeader(story: Story): LinearLayout {
    val col = LinearLayout(app).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(Space.XS), 0, dp(Space.SM))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    val cover = coverImage(story, widthDp = 150, heightDp = 225, tapToOpen = true)
    (cover.layoutParams as? LinearLayout.LayoutParams)?.setMargins(0, 0, 0, dp(Space.LG))
    col.addView(cover)
    col.addView(makeText(app, story.title, Type.HEADLINE, ThemeManager.colors.onSurface).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    })
    col.addView(makeText(app, story.author, Type.TITLE_MEDIUM, ThemeManager.colors.secondary).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        setPadding(0, dp(2), 0, dp(Space.MD))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    })
    val provider = SourceRegistry.getProvider(story.sourceUrl)
    if (provider != null || story.isArchived == true) {
        val badgeRow = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        provider?.let {
            badgeRow.addView(makeBadge(app, it.name, ThemeManager.colors.secondaryContainer, ThemeManager.colors.onSecondaryContainer))
        }
        if (story.isArchived == true) {
            if (provider != null) {
                val spacer = View(app).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(Space.SM), 0)
                }
                badgeRow.addView(spacer)
            }
            badgeRow.addView(makeBadge(app, "Archived", ThemeManager.colors.tertiaryContainer, ThemeManager.colors.onTertiaryContainer))
        }
        col.addView(badgeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    if (story.totalChapters > 0) {
        col.addView(makeProgressSummary(app, story.downloadedChapters, story.totalChapters).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(Space.XS)
                bottomMargin = dp(Space.XS)
            }
        })
    }
    return col
}
