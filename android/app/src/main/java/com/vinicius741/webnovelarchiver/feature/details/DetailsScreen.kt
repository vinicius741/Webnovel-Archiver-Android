package com.vinicius741.webnovelarchiver.feature.details

import android.content.Intent
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.data.repository.DownloadUiSnapshot
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.ChapterFilterSettings
import com.vinicius741.webnovelarchiver.domain.model.EpubConfig
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.StoryActionGuards
import com.vinicius741.webnovelarchiver.domain.story.StoryBookmarkPlanning
import com.vinicius741.webnovelarchiver.download.DownloadDetailsPlanning
import com.vinicius741.webnovelarchiver.feature.downloads.showQueue
import com.vinicius741.webnovelarchiver.feature.library.showLibrary
import com.vinicius741.webnovelarchiver.feature.story.applyCleanup
import com.vinicius741.webnovelarchiver.feature.story.generateConfiguredEpub
import com.vinicius741.webnovelarchiver.feature.story.openEpubForStory
import com.vinicius741.webnovelarchiver.feature.story.queueDownload
import com.vinicius741.webnovelarchiver.feature.story.showEpubConfigDialog
import com.vinicius741.webnovelarchiver.feature.story.syncStory
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.navigation.StoryOperationKind
import com.vinicius741.webnovelarchiver.ui.AppBarAction
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.DESCRIPTION_PREVIEW_LENGTH
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.WrapLayout
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.copyToClipboard
import com.vinicius741.webnovelarchiver.ui.currentScreenLayout
import com.vinicius741.webnovelarchiver.ui.disableButton
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeBadge
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeChip
import com.vinicius741.webnovelarchiver.ui.makeDivider
import com.vinicius741.webnovelarchiver.ui.makeFullWidthButton
import com.vinicius741.webnovelarchiver.ui.makeSearchField
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.scroll
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.showStyledOptionsDialog
import com.vinicius741.webnovelarchiver.ui.size
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.toast
import com.vinicius741.webnovelarchiver.ui.truncateDescription
import kotlinx.coroutines.launch

internal fun ScreenHost.showDetails(storyId: String) {
    val story = storage.getStory(storyId) ?: return showLibrary()
    val screenKey = "${story.title}|by ${story.author}"
    val previousListState =
        if (frame.tag == screenKey) {
            findDetailsChapterList(frame)?.layoutManager?.onSaveInstanceState()
        } else {
            null
        }
    // Stable reference to the download banner slot. Captured into the refresh closure so the loop
    // patches it in place even when the header is scrolled off-screen (the slot detaches from the
    // window but the reference stays valid). Allocated lazily by the banner block below.
    var bannerSlot: ViewGroup? = null
    var downloadActionSlot: LinearLayout? = null
    activeStory = story
    // Re-render on fold/unfold/rotation so the two-pane ↔ single-scroll layout can switch live.
    rerender = { showDetails(storyId) }
    val layout = currentScreenLayout()
    val operation = storyOperation?.takeIf { it.storyId == story.id }
    val isBusy = operation != null
    // Live download feedback: reduce this story's queue jobs once for the initial render. Later
    // repository events patch the banner and chapter rows in place below.
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
            infoPanel.addView(
                makeText(context, "Archived snapshot: sync and downloads disabled", Type.LABEL_MEDIUM, ThemeManager.colors.tertiary).apply {
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
                    context,
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
            infoPanel.addView(makeStoryOperationProgress(context, operation, indeterminate = true))
        }
        if (StoryActionGuards.canQueueDownloads(story)) {
            downloadActionSlot = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            renderDetailsDownloadAction(downloadActionSlot!!, story, downloadSummary, isBusy)
            infoPanel.addView(downloadActionSlot!!)
        }
        if (showDownloadBanner) {
            // The live download banner lives in a stable slot view held by [bannerSlot] (a local
            // captured into the refresh closure below). refreshDetailsDownload() swaps its child in
            // place rather than rebuilding the screen. The slot is always allocated when shown so we
            // have a direct reference even while the header is scrolled off-screen and the slot is
            // detached from the window — patching a detached view is safe and shows on reattach.
            bannerSlot =
                LinearLayout(context).apply {
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
        infoPanel.addView(
            makeFullWidthButton(
                context,
                generateLabel,
                Btn.TONAL,
                R.drawable.wna_menu_book,
                dp(Space.SM + 2),
                enabled =
                    story.downloadedChapters > 0 && !isBusy,
            ) {
                val config =
                    story.epubConfig ?: EpubConfig(
                        maxChaptersPerEpub = storage.getSettings().maxChaptersPerEpub,
                        rangeStart = 1,
                        rangeEnd = story.chapters.size,
                        startAtBookmark = false,
                    )
                generateConfiguredEpub(story, config)
            },
        )
        if (operation?.kind == StoryOperationKind.EPUB) {
            infoPanel.addView(makeStoryOperationProgress(context, operation, indeterminate = operation.progress == null))
        }
        // Read EPUB is now a full-width outlined button so it aligns with the other primary actions.
        infoPanel.addView(
            makeFullWidthButton(
                context,
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
            infoPanel.addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    // Center every child horizontally so the stale label (match_parent) and the
                    // wrap-content Regenerate button line up on the same screen-center axis.
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(
                        // LABEL_MEDIUM keeps BODY_SMALL's 12f size but renders bold, matching the
                        // bold Regenerate button beneath it.
                        makeText(context, "EPUB out of date", Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant).apply {
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
                        makeButton(context, "Regenerate", Btn.TEXT, R.drawable.wna_refresh) {
                            val config =
                                story.epubConfig ?: EpubConfig(
                                    maxChaptersPerEpub = storage.getSettings().maxChaptersPerEpub,
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
                },
            )
        }

        // Render the Patreon card whenever the story has a Patreon URL, even if the public stats
        // could not be fetched: a link-only card surfaces that the creator has a Patreon, instead of
        // silently showing nothing (which would be indistinguishable from having no Patreon).
        if (!story.patreonUrl.isNullOrBlank()) {
            infoPanel.addView(buildPatreonStatsCard(story.patreonStats, story.patreonUrl))
        }

        // ---- Description (inline expand/collapse, mirrors RN StoryDescription) ----
        story.description?.takeIf { it.isNotBlank() }?.let { description ->
            val canExpand = description.length > DESCRIPTION_PREVIEW_LENGTH
            var expanded = false
            val descCol =
                LinearLayout(context).apply {
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
                    context,
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
            infoPanel.addView(
                WrapLayout(context).apply {
                    horizontalSpacingDp = Space.SM
                    verticalSpacingDp = Space.SM
                    setPadding(0, dp(Space.SM), 0, dp(Space.XS))
                    tags.forEach { tag ->
                        addView(makeBadge(context, tag, ThemeManager.colors.surfaceVariant, ThemeManager.colors.onSurfaceVariant))
                    }
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                },
            )
        }

        // ---- Chapter filter (search + chips) ----
        val chapterControls = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val search = makeSearchField(context, "Search chapters")
        chapterControls.addView(search)
        val chipsContainer =
            WrapLayout(context).apply {
                horizontalSpacingDp = Space.SM
                verticalSpacingDp = Space.SM
                setPadding(0, dp(Space.SM), 0, dp(Space.SM))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        chapterControls.addView(chipsContainer)

        // ---- Chapter List (S1: RecyclerView so novels with hundreds/thousands of chapters recycle
        // views instead of inflating one row each on every render/filter tick) ----
        val chaptersContainer =
            androidx.recyclerview.widget.RecyclerView(context).apply {
                layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
                setHasFixedSize(false)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            }

        // Compact keeps one continuous scroll by exposing details and controls as the list's first
        // item. Two-pane keeps controls fixed above the independently scrolling chapter list.
        val listHeader: View? =
            if (!layout.isTwoPane) {
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    var infoExpanded = true
                    val collapseToggle = makeButton(context, "Hide details", Btn.TEXT, R.drawable.wna_chevron_down) {}
                    collapseToggle.setOnClickListener {
                        infoExpanded = !infoExpanded
                        infoPanel.visibility = if (infoExpanded) View.VISIBLE else View.GONE
                        collapseToggle.text = if (infoExpanded) "Hide details" else "Show details"
                    }
                    addView(
                        collapseToggle,
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            bottomMargin =
                                dp(Space.XS)
                        },
                    )
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
        search.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) {
                    chapterQuery = s?.toString().orEmpty()
                    renderChapterList(story, chaptersContainer, chapterQuery, chapterFilter, chapterStatuses, listHeader)
                }

                override fun afterTextChanged(s: Editable?) = Unit
            },
        )
        renderFilterChips(chipsContainer, chapterFilter, hasBookmark, pick)
        renderChapterList(story, chaptersContainer, chapterQuery, chapterFilter, chapterStatuses, listHeader)

        if (layout.isTwoPane) {
            // Two-pane: info scrolls on the left, chapter list scrolls on the right. The info
            // pane stays pinned at a fixed width (RN StoryDetailsLayout: 280–440dp) while the chapter
            // list takes the remaining space, each with its own scroll surface. No divider is drawn
            // between the panes — a marginEnd on the info pane keeps the columns from touching.
            val leftScroll = scroll(infoPanel)
            val rightPane =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(chapterControls)
                    addView(chaptersContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                }
            val shell =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(
                        leftScroll,
                        LinearLayout.LayoutParams(dp(DETAILS_TWO_PANE_LEFT_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                            marginEnd = dp(DETAILS_TWO_PANE_GAP_DP)
                        },
                    )
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
    // Download state is emitted process-wide by the shared repository. Patch only the chapter rows
    // and banner after a coherent event; never poll disk or rebuild this screen for progress ticks.
    // If the list is being dragged/flung, coalesce events until it becomes idle so an adapter update
    // cannot interfere with the gesture.
    if (frame.childCount > 0) {
        val root = frame.getChildAt(0)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var patchPosted = false
        var pendingSnapshot: DownloadUiSnapshot? = null

        fun postPatch() {
            if (patchPosted) return
            patchPosted = true
            val patch =
                object : Runnable {
                    override fun run() {
                        if (root.parent !== frame) return
                        val chapterList = findDetailsChapterList(root)
                        if (chapterList?.scrollState != androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) {
                            handler.postDelayed(this, DETAILS_SCROLL_RETRY_MS)
                            return
                        }
                        patchPosted = false
                        val snapshot = pendingSnapshot
                        pendingSnapshot = null
                        refreshDetailsDownload(storyId, bannerSlot, downloadActionSlot, isBusy, snapshot)
                    }
                }
            handler.post(patch)
        }
        // Capture before launching so an event before collector registration is not dropped.
        val initialSnapshot = repository.downloadState.value
        var observedLibraryVersion = initialSnapshot.libraryVersion
        var observedQueueVersion = initialSnapshot.queueVersion
        screenObserver =
            scope.launch {
                repository.downloadState.collect { snapshot ->
                    if (
                        snapshot.libraryVersion == observedLibraryVersion &&
                        snapshot.queueVersion == observedQueueVersion
                    ) {
                        return@collect
                    }
                    observedLibraryVersion = snapshot.libraryVersion
                    observedQueueVersion = snapshot.queueVersion
                    if (root.parent === frame) {
                        pendingSnapshot = snapshot
                        postPatch()
                    }
                }
            }
    }
}

/** Re-derives only the Details download action after a progress event. */
internal fun ScreenHost.renderDetailsDownloadAction(
    slot: LinearLayout,
    story: Story,
    summary: DownloadDetailsPlanning.StoryDownloadSummary,
    isBusy: Boolean,
) {
    slot.removeAllViews()
    val remainingChapters = story.chapters.count { !it.downloaded }
    if (remainingChapters == 0 || !StoryActionGuards.canQueueDownloads(story)) return
    val label = if (remainingChapters == story.chapters.size) "Download All" else "Download Remaining ($remainingChapters)"
    slot.addView(
        makeFullWidthButton(
            app,
            label,
            Btn.FILLED,
            R.drawable.wna_download,
            dp(Space.SM + 2),
            enabled = !isBusy && !summary.isActive,
        ) {
            val latest = storage.getStory(story.id) ?: return@makeFullWidthButton
            queueDownload(
                latest,
                latest.chapters.mapIndexedNotNull { index, chapter -> if (!chapter.downloaded) index else null },
            )
        },
    )
}

/** Fixed width (dp) of the left info pane in the two-pane details layout, within the RN range. */
private const val DETAILS_TWO_PANE_LEFT_WIDTH_DP = 360

/** Gap (dp) between the info pane and the chapter list in the two-pane layout, replacing the old
 *  1dp divider with clear whitespace. */
private const val DETAILS_TWO_PANE_GAP_DP = Space.MD

/**
 * Window (ms) within which two taps on the description copy it to the clipboard, matching the legacy
 * RN StoryDescription's 300ms double-press → expo-clipboard behaviour.
 */
private const val DESCRIPTION_DOUBLE_TAP_COPY_WINDOW_MS = 300L

/**
 * Overflow menu behind the app-bar "more" icon. Holds the secondary/tertiary story actions that
 * don't warrant a primary button: opening the source site, chapter selection, EPUB settings, and
 * text cleanup.
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
    }
    options += "EPUB Settings" to {
        if (isBusy) toast("Please wait for the current operation to finish") else showEpubConfigDialog(story)
    }
    options += "Legacy EPUBs" to {
        if (isBusy) toast("Please wait for the current operation to finish") else showLegacyEpubs(story.id)
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

internal fun ScreenHost.renderChapterList(
    story: Story,
    list: androidx.recyclerview.widget.RecyclerView,
    query: String,
    filter: String,
    chapterStatuses: Map<String, com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus> = emptyMap(),
    header: View? = null,
) {
    val displayedChapters = filterDetailsChapters(story, query, filter)
    val isEmptyState = displayedChapters.isEmpty()
    val existingChapterAdapter =
        when (val adapter = list.adapter) {
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
    list.adapter =
        if (header == null) chapterAdapter else androidx.recyclerview.widget.ConcatAdapter(DetailsHeaderAdapter(header), chapterAdapter)
}

/**
 * Pure chapter filter for the Details list — shared by [renderChapterList] (initial + filter/search)
 * and the in-place download refresh so both apply the identical view. Returns the matching
 * `(chapterIndex, chapter)` pairs, or an empty list when nothing matches (the caller decides
 * whether to substitute the "No chapters match" empty state).
 */
internal fun filterDetailsChapters(
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

/**
 * Toggles the bookmark on [chapter] for [story] from the per-row bookmark button (which replaced the
 * old three-dot chapter overflow). Persists the new [Story.lastReadChapterId] and re-renders the list
 * so the tapped row's icon flips between the empty outline and the filled, primary-tinted bookmark.
 */
internal fun ScreenHost.toggleChapterBookmark(
    story: Story,
    chapter: Chapter,
    list: androidx.recyclerview.widget.RecyclerView,
    query: String,
    filter: String,
) {
    val updated = StoryBookmarkPlanning.withBookmark(story, chapter.id, toggleExisting = true)
    storage.addOrUpdateStory(updated)
    renderChapterList(updated, list, query, filter)
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
