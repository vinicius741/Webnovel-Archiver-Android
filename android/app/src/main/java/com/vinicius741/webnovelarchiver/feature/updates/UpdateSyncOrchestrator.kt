package com.vinicius741.webnovelarchiver.feature.updates

import android.view.View
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.feature.story.queueDownload
import com.vinicius741.webnovelarchiver.navigation.InFlightStorySync
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.navigation.UpdateTrackerScreenState
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

private const val UPDATE_SYNC_CONCURRENCY = 3

@Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
internal fun ScreenHost.syncFollowedUpdates(onProgress: () -> Unit) {
    val state = updateTrackerScreenState
    if (state.syncing) return toast("Sync already running")
    val stories = repository.library()
    val toSync = UpdateTrackerPlanning.syncableFollowedStories(stories, repository.getUpdateFollowedStoryIds())
    if (toSync.isEmpty()) return toast("Choose at least one syncable novel")
    state.reset(toSync.size)
    onProgress()
    val renderedRoot = frame.getChildAt(0)
    scope.launch {
        UpdateTrackerPlanning.syncBatches(toSync, UPDATE_SYNC_CONCURRENCY).forEach { batch ->
            coroutineScope {
                batch.map { story -> launch { syncStory(story, state, renderedRoot, onProgress) } }.joinAll()
            }
        }
        withContext(Dispatchers.Main) {
            state.finish()
            repository.publishDownloadState(toSync.mapTo(linkedSetOf()) { it.id }, queueChanged = true)
            if (renderedRoot.parent === frame) showUpdates()
        }
    }
}

@Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
private suspend fun ScreenHost.syncStory(
    story: Story,
    state: UpdateTrackerScreenState,
    renderedRoot: View,
    onProgress: () -> Unit,
) {
    state.inFlight[story.id] = InFlightStorySync(story.title)
    try {
        if (renderedRoot.parent === frame) onProgress()
        val before = withContext(Dispatchers.IO) { repository.getStory(story.id) }
        val synced =
            withContext(Dispatchers.IO) {
                syncEngine.fetchOrSync(story.sourceUrl, story.tabId, refreshPatreonStats = false) { message ->
                    app.runOnUiThread {
                        state.inFlight[story.id]?.status = message
                        if (renderedRoot.parent === frame) onProgress()
                    }
                }
            }
        repository.publishDownloadState(setOf(story.id), queueChanged = false)
        state.syncedUpdatedChapterIds[story.id] = synced.pendingNewChapterIds.orEmpty().distinct()
        queuePendingNewDownloads(before, synced)
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        Timber.w(error, "Batch update sync failed for %s", story.id)
        state.errors[story.id] = error.message ?: "Sync failed"
    } finally {
        state.inFlight.remove(story.id)
        state.completed += 1
        if (renderedRoot.parent === frame) onProgress()
    }
}

private fun ScreenHost.queuePendingNewDownloads(
    before: Story?,
    synced: Story,
) {
    if (before == null || synced.pendingNewChapterIds.isNullOrEmpty()) return
    val pending = synced.pendingNewChapterIds.orEmpty().toSet()
    val indexes =
        synced.chapters.mapIndexedNotNull { index, chapter ->
            index.takeIf { chapter.id in pending && !chapter.downloaded }
        }
    if (indexes.isNotEmpty()) queueDownload(synced, indexes)
}

internal fun UpdateTrackerScreenState.progressText(): String {
    if (!syncing) return "Ready to check followed novels."
    val active = (completed + inFlight.size).coerceAtMost(total)
    val current = inFlight.values.firstOrNull()
    return if (current == null) "Syncing $active/$total..." else "Syncing $active/$total: ${current.title} · ${current.status}"
}
