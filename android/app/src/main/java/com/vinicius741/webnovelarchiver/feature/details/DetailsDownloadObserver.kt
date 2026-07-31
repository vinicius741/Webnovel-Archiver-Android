package com.vinicius741.webnovelarchiver.feature.details

import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.data.repository.DownloadUiSnapshot
import com.vinicius741.webnovelarchiver.download.DownloadPacingUiStatus
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.tickerFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Subscribes the in-place download-refresh loop for the Details screen. Extracted from
 * [DetailsScreen.kt] so that file stays within its size budget. Download state is emitted
 * process-wide by the shared repository; this patches only the chapter rows and banner after a
 * coherent event and never polls disk or rebuilds the screen for progress ticks. If the list is
 * being dragged/flung, events are coalesced until it becomes idle so an adapter update cannot
 * interfere with the gesture.
 */
internal fun ScreenHost.observeDetailsDownload(
    storyId: String,
    bindings: DetailsBindings,
    isBusy: Boolean,
    initialPacingStatus: DownloadPacingUiStatus?,
) {
    if (frame.childCount == 0) return
    val root = bindings.root
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
                    if (bindings.chapters.scrollState != androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) {
                        handler.postDelayed(this, DETAILS_SCROLL_RETRY_MS)
                        return
                    }
                    patchPosted = false
                    val snapshot = pendingSnapshot
                    pendingSnapshot = null
                    refreshDetailsDownload(
                        storyId,
                        bindings,
                        isBusy,
                        snapshot,
                    )
                }
            }
        handler.post(patch)
    }
    // Capture before launching so an event before collector registration is not dropped.
    val initialSnapshot = repository.downloadState.value
    var observedLibraryVersion = initialSnapshot.libraryVersion
    var observedQueueVersion = initialSnapshot.queueVersion
    var observedPacingStatus = initialPacingStatus
    screenObserver =
        scope.launch {
            launch {
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
            launch {
                combine(
                    app.appContainer.downloadPacer.snapshots,
                    tickerFlow(),
                ) { pacing, now -> pacing.values to now }
                    .collect { (pacing, now) ->
                        if (root.parent !== frame) return@collect
                        val hasLiveTimer =
                            observedPacingStatus != null ||
                                pacing.any { it.nextRequestAtMillis > now } ||
                                repository.downloadState.value.queue.any {
                                    it.storyId == storyId && (it.nextRetryAt ?: 0L) > now
                                }
                        if (!hasLiveTimer) return@collect
                        val story = repository.story(storyId) ?: return@collect
                        val queue = repository.queue()
                        val jobsForStory = queue.filter { it.storyId == storyId }
                        val pacingStatus =
                            detailsPacingStatus(
                                storyId = storyId,
                                storySourceUrl = story.sourceUrl,
                                jobsForStory = jobsForStory,
                                snapshots = pacing,
                                nowMillis = now,
                                allJobs = queue,
                            )
                        if (pacingStatus != observedPacingStatus) {
                            observedPacingStatus = pacingStatus
                            refreshDetailsPacingBanner(storyId, bindings, pacing, now)
                        }
                    }
            }
        }
}
