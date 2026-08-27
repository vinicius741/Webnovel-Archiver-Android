package com.vinicius741.webnovelarchiver.feature.details

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.widget.doAfterTextChanged
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.domain.model.ChapterFilterSettings
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.download.DownloadDetailsPlanning
import com.vinicius741.webnovelarchiver.feature.library.showLibrary
import com.vinicius741.webnovelarchiver.feature.reader.readerTtsTransport
import com.vinicius741.webnovelarchiver.feature.story.queueDownload
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.TtsForegroundService
import com.vinicius741.webnovelarchiver.ui.AppBarAction
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.currentScreenLayout
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeDivider
import com.vinicius741.webnovelarchiver.ui.makeFullWidthButton
import com.vinicius741.webnovelarchiver.ui.makeSearchField
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.scroll
import kotlinx.coroutines.launch

/**
 * Details screen orchestrator. Wires story + download state, builds the info panel
 * list ([renderChapterList] / [renderFilterChips]), selects the single-pane vs two-pane layout, and
 * subscribes the in-place download-refresh loop. The info-panel and chapter-list building blocks
 * live in [DetailsInfoPanel.kt] and [DetailsChapterList.kt].
 */
internal fun ScreenHost.showDetails(storyId: String) {
    // Drop the previous description-TTS collector before its views are torn down or replaced.
    detachDetailsTtsListener()
    // Seed from the repository's cached library rather than re-parsing the story JSON on each
    // render. The downloaded-flow observer below patches this in place afterward.
    val story = repository.story(storyId) ?: return showLibrary()
    val screenKey = AppRoute.Details(story.id).stableKey
    val previousListState =
        if (frame.tag == screenKey) {
            findDetailsChapterList(frame)?.layoutManager?.onSaveInstanceState()
        } else {
            null
        }
    activeStory = story
    // Re-render on fold/unfold/rotation so the two-pane ↔ single-scroll layout can switch live.
    rerender = { showDetails(storyId) }
    val layout = currentScreenLayout()
    val operation = storyOperation?.takeIf { it.storyId == story.id }
    val isBusy = operation != null
    // Live download feedback: reduce this story's queue jobs once for the initial render from the
    // cached queue. Later repository events patch the banner and chapter rows in place below.
    val queue = repository.queue()
    val jobsForStory = queue.filter { it.storyId == story.id }
    val downloadSummary = DownloadDetailsPlanning.summarizeStoryDownload(jobsForStory)
    val chapterStatuses = DownloadDetailsPlanning.chapterJobStatuses(jobsForStory)
    val initialPacingSnapshots = app.appContainer.downloadPacer.snapshots.value.values
    val waitingChapterIds = waitingChapterIds(jobsForStory, initialPacingSnapshots, System.currentTimeMillis())
    val initialPacingStatus =
        detailsPacingStatus(
            storyId = story.id,
            storySourceUrl = story.sourceUrl,
            jobsForStory = jobsForStory,
            snapshots = initialPacingSnapshots,
            nowMillis = System.currentTimeMillis(),
            allJobs = queue,
        )
    // Stable references captured into the refresh closure below so the loop patches the header
    // progress, banner, and download action in place even when the header is scrolled off-screen
    // (the views detach but the references stay valid). Assigned synchronously inside screen { ... }.
    var headerProgressSummary: View? = null
    var bannerSlot: ViewGroup? = null
    var downloadActionSlot: LinearLayout? = null
    var chaptersBinding: androidx.recyclerview.widget.RecyclerView? = null
    // Cleared before rebuild so a stale slot cannot be patched after the tree is replaced.
    detailsOperationSlot = null
    screen(
        route = AppRoute.Details(story.id),
        title = story.title,
        subtitle = "by ${story.author}",
        onBack = { showLibrary() },
        actions = listOf(AppBarAction(R.drawable.wna_more_vert, "More options") { showDetailsOverflow(story) }),
        // The chapter RecyclerView must be the scrolling surface. Wrapping it in a ScrollView makes
        // Android measure and inflate every chapter row, defeating recycling for large novels.
        scrollable = false,
    ) {
        val panel = buildDetailsInfoPanel(story, operation, downloadSummary, initialPacingStatus)
        val infoPanel = panel.view
        headerProgressSummary = panel.headerProgressSummary
        bannerSlot = panel.bannerSlot
        downloadActionSlot = panel.downloadActionSlot
        // Direct ref for setStoryOperation in-place ticks (cleanup/EPUB/sync). Must not be a tree
        // walk: in compact layout the slot lives in the RecyclerView header and can detach.
        detailsOperationSlot = panel.operationSlot

        // ---- Chapter filter (search + chips) ----
        val chapterControls =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(Space.SM), 0, 0)
            }
        val search = makeSearchField(context, "Search chapters")
        chapterControls.addView(search)
        val chipsContainer =
            com.vinicius741.webnovelarchiver.ui.WrapLayout(context).apply {
                horizontalSpacingDp = Space.SM
                verticalSpacingDp = Space.SM
                setPadding(0, dp(Space.MD), 0, dp(Space.MD))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        chapterControls.addView(chipsContainer)

        // ---- Chapter list: a RecyclerView so novels with hundreds/thousands of chapters recycle
        // views instead of inflating one row each on every render/filter tick ----
        val chaptersContainer =
            androidx.recyclerview.widget.RecyclerView(context).apply {
                layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
                setHasFixedSize(false)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            }
        chaptersBinding = chaptersContainer

        // Compact keeps one continuous scroll by exposing details and controls as the list's first
        // item. Two-pane keeps controls fixed above the independently scrolling chapter list.
        val listHeader: View? =
            if (!layout.isTwoPane) {
                buildCompactListHeader(infoPanel, chapterControls)
            } else {
                null
            }

        var chapterFilter = repository.getChapterFilterSettings().filterMode
        var chapterQuery = ""

        // Chips are rebuilt on every pick so the active one re-highlights. The "From Bookmark"
        // chip also carries the live (N) count of chapters remaining from the bookmark.
        var pick: (String) -> Unit = {}
        pick = { mode ->
            chapterFilter = mode
            scope.launch { repository.saveChapterFilterSettings(ChapterFilterSettings(mode)) }
            renderFilterChips(chipsContainer, chapterFilter, fromBookmarkCount(story), pick)
            renderChapterList(
                story,
                chaptersContainer,
                chapterQuery,
                chapterFilter,
                chipsContainer,
                pick,
                chapterStatuses,
                waitingChapterIds,
                listHeader,
            )
        }
        search.doAfterTextChanged {
            chapterQuery = it?.toString().orEmpty()
            renderChapterList(
                story,
                chaptersContainer,
                chapterQuery,
                chapterFilter,
                chipsContainer,
                pick,
                chapterStatuses,
                waitingChapterIds,
                listHeader,
            )
        }
        renderFilterChips(chipsContainer, chapterFilter, fromBookmarkCount(story), pick)
        renderChapterList(
            story,
            chaptersContainer,
            chapterQuery,
            chapterFilter,
            chipsContainer,
            pick,
            chapterStatuses,
            waitingChapterIds,
            listHeader,
        )

        if (layout.isTwoPane) {
            // Two-pane: info scrolls on the left, chapter list scrolls on the right. The info
            // pane stays pinned at a fixed width while the chapter
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
            // Weighted height (not MATCH_PARENT) so the docked description-TTS transport added
            // below still fits when visible.
            addView(shell, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            addView(chaptersContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        // ---- Description TTS transport ----
        // Docked below the scrolling surfaces (never inside them) so pause/resume/stop stay
        // reachable no matter how far the info panel has scrolled. Revealed live while this
        // story's description session is active; chapter playback keeps using the reader transport
        // and the system media controls, so only description snapshots drive this bar.
        var ttsSnapshot = detailsDescriptionSnapshotSeed(repository.getTtsSession(), ttsEngine.playbackState.value, story.id)
        var ttsPlayPause: ImageView? = null
        val ttsTransport =
            readerTtsTransport(
                snapshot = ttsSnapshot,
                onPlayPause = { ttsPlayPause = it },
                onPrev = { TtsForegroundService.command(app, TtsForegroundService.ACTION_PREVIOUS) },
                onPlayPauseTap = {
                    val action =
                        if (ttsSnapshot?.isPaused != false) {
                            TtsForegroundService.ACTION_RESUME_SESSION
                        } else {
                            TtsForegroundService.ACTION_PAUSE
                        }
                    TtsForegroundService.command(app, action)
                },
                onNext = { TtsForegroundService.command(app, TtsForegroundService.ACTION_NEXT) },
                onStop = { TtsForegroundService.command(app, TtsForegroundService.ACTION_STOP) },
            )
        ttsTransport.visibility = if (ttsSnapshot != null) View.VISIBLE else View.GONE
        addView(ttsTransport, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        observeDetailsDescriptionTts(
            story = story,
            listenButton = panel.descriptionTtsButton,
            transportBar = ttsTransport,
            transportPlayPause = ttsPlayPause,
            initialSnapshot = ttsSnapshot,
            onSnapshot = { ttsSnapshot = it },
        )

        previousListState?.let { state ->
            chaptersContainer.post { chaptersContainer.layoutManager?.onRestoreInstanceState(state) }
        }
    }
    val root = frame.getChildAt(0)
    val bindings =
        DetailsBindings(
            root = root,
            chapters = requireNotNull(chaptersBinding),
            headerProgressSummary = headerProgressSummary,
            bannerSlot = bannerSlot,
            downloadActionSlot = downloadActionSlot,
        )
    observeDetailsDownload(storyId, bindings, isBusy, initialPacingStatus)
}

/**
 * Single-pane list header: a "Hide/Show details" toggle above the info panel + chapter controls,
 * exposed as the RecyclerView's first item via a [DetailsHeaderAdapter] so everything shares one
 * continuous scroll surface.
 */
private fun ScreenHost.buildCompactListHeader(
    infoPanel: LinearLayout,
    chapterControls: LinearLayout,
): LinearLayout =
    LinearLayout(app).apply {
        orientation = LinearLayout.VERTICAL
        var infoExpanded = true
        val collapseToggle = makeButton(app, "Hide details", Btn.TEXT, R.drawable.wna_chevron_down) {}
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
        addView(makeDivider(app))
        addView(chapterControls)
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
    if (
        remainingChapters == 0 ||
        !com.vinicius741.webnovelarchiver.domain.story.StoryActionGuards
            .canQueueDownloads(story)
    ) {
        return
    }
    val enabled = !isBusy && !summary.isActive
    val label = if (remainingChapters == story.chapters.size) "Download All" else "Download Remaining ($remainingChapters)"
    slot.addView(
        makeFullWidthButton(
            app,
            label,
            Btn.FILLED,
            R.drawable.wna_download,
            dp(Space.SM + 2),
            enabled = enabled,
        ) {
            val latest = repository.story(story.id) ?: return@makeFullWidthButton
            queueDownload(
                latest,
                latest.chapters.mapIndexedNotNull { index, chapter -> if (!chapter.downloaded) index else null },
            )
        },
    )
    // Manual counterpart of the bulk download above; hidden together with it by the guard returns.
    slot.addView(
        makeFullWidthButton(app, "Select Chapters", Btn.TEXT, R.drawable.wna_list) {
            showChapterSelection(story.id)
        },
    )
}

/** Fixed width (dp) of the left info pane in the two-pane details layout. */
private const val DETAILS_TWO_PANE_LEFT_WIDTH_DP = 360

/** Gap (dp) of clear whitespace between the info pane and the chapter list in the two-pane layout. */
private const val DETAILS_TWO_PANE_GAP_DP = Space.MD
