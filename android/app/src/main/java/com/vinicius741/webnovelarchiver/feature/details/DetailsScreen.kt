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

internal fun ScreenHost.showDetails(storyId: String) {
    // Drop the previous collector before its views are torn down.
    detachDetailsTtsListener()
    // Seed from the cached library; the download observer below patches this in place afterward.
    val story = repository.story(storyId) ?: return showLibrary()
    val screenKey = AppRoute.Details(story.id).stableKey
    val previousListState =
        if (frame.tag == screenKey) {
            findDetailsChapterList(frame)?.layoutManager?.onSaveInstanceState()
        } else {
            null
        }
    activeStory = story
    // Re-render on fold/unfold/rotation so the layout switches live.
    rerender = { showDetails(storyId) }
    val layout = currentScreenLayout()
    val operation = storyOperation?.takeIf { it.storyId == story.id }
    val isBusy = operation != null
    // The cached queue drives the initial render; later events patch rows in place.
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
    // Stable refs for the refresh closure: the views can detach when scrolled off-screen.
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
        // The RecyclerView must be the scroll surface; a ScrollView wrapper defeats row recycling.
        scrollable = false,
    ) {
        val panel = buildDetailsInfoPanel(story, operation, downloadSummary, initialPacingStatus)
        val infoPanel = panel.view
        headerProgressSummary = panel.headerProgressSummary
        bannerSlot = panel.bannerSlot
        downloadActionSlot = panel.downloadActionSlot
        // Direct ref, not a tree walk: in compact layout the slot can detach inside the RecyclerView header.
        detailsOperationSlot = panel.operationSlot

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

        val chaptersContainer =
            androidx.recyclerview.widget.RecyclerView(context).apply {
                layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
                setHasFixedSize(false)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            }
        chaptersBinding = chaptersContainer

        // Compact: details + controls ride as the list header; two-pane keeps them above the list.
        val listHeader: View? =
            if (!layout.isTwoPane) {
                buildCompactListHeader(infoPanel, chapterControls)
            } else {
                null
            }

        var chapterFilter = repository.getChapterFilterSettings().filterMode
        var chapterQuery = ""

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
            // Weighted (not MATCH_PARENT) so the docked TTS transport below still fits.
            addView(shell, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            addView(chaptersContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        // Docked outside the scrollers so controls stay reachable; only description sessions drive this bar.
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
    slot.addView(
        makeFullWidthButton(app, "Select Chapters", Btn.TEXT, R.drawable.wna_list) {
            showChapterSelection(story.id)
        },
    )
}

/** Left info-pane width (dp) in the two-pane layout. */
private const val DETAILS_TWO_PANE_LEFT_WIDTH_DP = 360

/** Gap (dp) between the two panes. */
private const val DETAILS_TWO_PANE_GAP_DP = Space.MD
