package com.vinicius741.webnovelarchiver.feature.details

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.data.repository.DownloadUiSnapshot
import com.vinicius741.webnovelarchiver.data.repository.getTtsSession
import com.vinicius741.webnovelarchiver.download.DownloadPacingSnapshot
import com.vinicius741.webnovelarchiver.download.DownloadPacingUiStatus
import com.vinicius741.webnovelarchiver.feature.library.showLibrary
import com.vinicius741.webnovelarchiver.feature.reader.readerTtsTransport
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.BackIntercept
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.TtsForegroundService
import com.vinicius741.webnovelarchiver.ui.tickerFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The story page's Overview tab (the hero, then About), or its Chapters tab when [chapters] is set.
 * Live download events patch the hero and the chapter rows in place ([observeDetailsDownload]).
 */
internal fun ScreenHost.showDetails(
    storyId: String,
    chapters: Boolean = false,
) {
    // Drop the previous collector before its views are torn down.
    detachDetailsTtsListener()
    val story = repository.story(storyId) ?: return showLibrary()
    val route = AppRoute.Details(story.id, chapters)
    rerender = { showDetails(storyId, chapters) }
    var chaptersTab: ChaptersTab? = null
    storyScreen(
        route = route,
        story = story,
        // The chapter RecyclerView must be the scroll surface; a ScrollView wrapper defeats row recycling.
        scrollable = !chapters,
        backIntercept =
            BackIntercept(
                isActive = { chaptersTab?.hasQuery?.invoke() == true },
                clear = { chaptersTab?.clearSearch?.invoke() },
            ),
    ) { frame ->
        if (chapters) {
            val tab = buildChaptersTab(this, story, route.stableKey)
            chaptersTab = tab
            frame.chaptersTab = tab
        } else {
            val listen = addDetailsAbout(this, story, frame.twoPane)
            addDescriptionPlayer(frame.footer, story, listen)
        }
    }
}

/** Docked under the Overview, outside its scroller, while the description is read aloud. */
private fun ScreenHost.addDescriptionPlayer(
    footer: LinearLayout,
    story: com.vinicius741.webnovelarchiver.domain.model.Story,
    listenButton: android.widget.Button?,
) {
    var ttsSnapshot = detailsDescriptionSnapshotSeed(repository.getTtsSession(), ttsEngine.playbackState.value, story.id)
    var ttsPlayPause: ImageView? = null
    val transport =
        readerTtsTransport(
            snapshot = ttsSnapshot,
            onPlayPause = { ttsPlayPause = it },
            onPrev = { TtsForegroundService.command(app, TtsForegroundService.ACTION_PREVIOUS) },
            onPlayPauseTap = {
                val action =
                    if (ttsSnapshot?.isPaused !=
                        false
                    ) {
                        TtsForegroundService.ACTION_RESUME_SESSION
                    } else {
                        TtsForegroundService.ACTION_PAUSE
                    }
                TtsForegroundService.command(app, action)
            },
            onNext = { TtsForegroundService.command(app, TtsForegroundService.ACTION_NEXT) },
            onStop = { TtsForegroundService.command(app, TtsForegroundService.ACTION_STOP) },
        )
    transport.visibility = if (ttsSnapshot != null) View.VISIBLE else View.GONE
    footer.addView(transport, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    observeDetailsDescriptionTts(
        story = story,
        listenButton = listenButton,
        transportBar = transport,
        transportPlayPause = ttsPlayPause,
        initialSnapshot = ttsSnapshot,
        onSnapshot = { ttsSnapshot = it },
    )
}

/**
 * Patches the story page's download surfaces (hero actions, banner, coverage, chapter rows) after
 * each coherent download event, never polling disk or rebuilding the page for progress ticks.
 * While the chapter list is dragged or flung, events wait until it is idle. The collector lives as
 * long as [DetailsBindings.root] stays on screen, so any tab that shows the hero can use it.
 */
internal fun ScreenHost.observeDetailsDownload(
    storyId: String,
    bindings: DetailsBindings,
) {
    val root = bindings.root
    if (root.parent !== frame) return
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
                    val scrolling =
                        bindings.chapters
                            ?.list
                            ?.scrollState
                            ?.let { it != androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE } ==
                            true
                    if (scrolling) {
                        handler.postDelayed(this, DETAILS_SCROLL_RETRY_MS)
                        return
                    }
                    patchPosted = false
                    val snapshot = pendingSnapshot
                    pendingSnapshot = null
                    refreshDetailsDownload(storyId, bindings, snapshot)
                }
            }
        handler.post(patch)
    }
    // Capture before launching so an event before collector registration is not dropped.
    val initialSnapshot = repository.downloadState.value
    var observedLibraryVersion = initialSnapshot.libraryVersion
    var observedQueueVersion = initialSnapshot.queueVersion
    var observedPacingStatus = currentPacingStatus(storyId)
    val job =
        scope.launch {
            launch {
                repository.downloadState.collect { snapshot ->
                    if (snapshot.libraryVersion == observedLibraryVersion && snapshot.queueVersion == observedQueueVersion) return@collect
                    observedLibraryVersion = snapshot.libraryVersion
                    observedQueueVersion = snapshot.queueVersion
                    if (root.parent === frame) {
                        pendingSnapshot = snapshot
                        postPatch()
                    }
                }
            }
            launch {
                combine(
                    app.appContainer.downloadPacer.snapshots,
                    tickerFlow(nowMillis = app.appContainer.time::nowMillis),
                ) { pacing, now -> pacing.values to now }
                    .collect { (pacing, now) ->
                        if (root.parent !== frame || bindings.hero == null) return@collect
                        val hasLiveTimer =
                            observedPacingStatus != null ||
                                pacing.any { it.nextRequestAtMillis > now } ||
                                repository.downloadState.value.queue
                                    .any { it.storyId == storyId && (it.nextRetryAt ?: 0L) > now }
                        if (!hasLiveTimer) return@collect
                        val pacingStatus = currentPacingStatus(storyId, pacing, now)
                        if (pacingStatus != observedPacingStatus) {
                            observedPacingStatus = pacingStatus
                            refreshDetailsPacingBanner(storyId, bindings, pacing, now)
                        }
                    }
            }
        }
    // The page owns the collector: it stops when this tree leaves the screen (tab switch, fold, Back).
    root.addOnAttachStateChangeListener(
        object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit

            override fun onViewDetachedFromWindow(v: View) {
                job.cancel()
                v.removeOnAttachStateChangeListener(this)
            }
        },
    )
}

internal fun ScreenHost.currentPacingStatus(
    storyId: String,
    pacing: Collection<DownloadPacingSnapshot> = app.appContainer.downloadPacer.snapshots.value.values,
    now: Long = app.appContainer.time.nowMillis(),
): DownloadPacingUiStatus? {
    val story = repository.story(storyId) ?: return null
    val queue = repository.queue()
    return detailsPacingStatus(
        storyId = storyId,
        storySourceUrl = story.sourceUrl,
        jobsForStory = queue.filter { it.storyId == storyId },
        snapshots = pacing,
        nowMillis = now,
        allJobs = queue,
    )
}
