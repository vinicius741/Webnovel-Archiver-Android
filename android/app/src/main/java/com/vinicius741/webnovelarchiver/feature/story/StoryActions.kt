package com.vinicius741.webnovelarchiver.feature.story

import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.StoryActionGuards
import com.vinicius741.webnovelarchiver.download.DownloadForegroundService
import com.vinicius741.webnovelarchiver.feature.browser.showSourceAccessBlockedDialog
import com.vinicius741.webnovelarchiver.feature.details.showDetails
import com.vinicius741.webnovelarchiver.feature.library.showLibrary
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.navigation.StoryOperationKind
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.source.network.SourceAccessBlockedException
import com.vinicius741.webnovelarchiver.sync.StorySyncEngine
import com.vinicius741.webnovelarchiver.sync.StorySyncMode
import com.vinicius741.webnovelarchiver.ui.centerLoading
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

internal fun ScreenHost.queueDownload(
    story: Story,
    indexes: List<Int>,
) {
    if (!StoryActionGuards.canModifyStory(story)) {
        toast(StoryActionGuards.archivedActionMessage("Downloading"))
        return
    }
    requestNotificationPermissionForDownload()
    val screenTagAtEnqueue = frame.tag
    val servicePrepared = runCatching { DownloadForegroundService.prepare(app.applicationContext) }
    if (servicePrepared.isFailure) {
        toast(servicePrepared.exceptionOrNull()?.message ?: "Could not start downloads")
        return
    }
    // Planning + durable JSON writes scale with chapter count — keep them off the main thread.
    // Process scope keeps persistence and service handoff alive across Activity recreation.
    app.appContainer.applicationScope.launch {
        val result =
            runCatching {
                downloadEngine.queue(story, indexes, startNow = false)
                DownloadForegroundService.startPrepared(app.applicationContext)
            }
        if (result.isFailure) {
            runCatching { DownloadForegroundService.abortPrepare(app.applicationContext) }
        }
        app.runOnUiThread {
            if (app.isFinishing || app.isDestroyed) return@runOnUiThread
            result
                .onSuccess {
                    val detailsScreenKey = "${story.title}|by ${story.author}"
                    if (
                        activeStory?.id == story.id &&
                        (frame.tag == screenTagAtEnqueue || frame.tag == detailsScreenKey)
                    ) {
                        showDetails(story.id)
                    }
                }.onFailure { error ->
                    toast(error.message ?: "Could not queue downloads")
                }
        }
    }
}

@Suppress(
    "TooGenericExceptionCaught",
    "InstanceOfCheckForException",
)
// Route non-cancellation failures through the user-facing error path.
internal fun ScreenHost.syncStory(
    url: String,
    tabId: String?,
    mode: StorySyncMode = StorySyncMode.Default,
    onStatus: (String) -> Unit = { msg ->
        // Default: full-screen "Working" loader, used by the Browser import flow (no form to block).
        app.runOnUiThread { screen(route = AppRoute.Working, title = "Working", onBack = null) { centerLoading(msg) } }
    },
    onDone: (Story) -> Unit = { story -> showDetails(story.id) },
    onError: (Throwable) -> Unit = { error ->
        toast(error.message ?: "Sync failed")
        showLibrary()
    },
) {
    if (url.isBlank()) return toast("Enter a URL")
    // Emit the first status before any work so callers can flip to a loading state immediately;
    // inline-rendering callers (Add Story) rely on this to disable their button.
    app.runOnUiThread { onStatus("Starting...") }
    scope.launch {
        try {
            val existingBeforeSync =
                withContext(Dispatchers.IO) {
                    SourceRegistry.getProvider(url)?.let { provider ->
                        runCatching { repository.story(provider.getStoryId(url)) }.getOrNull()
                    }
                }
            val story =
                withContext(Dispatchers.IO) {
                    syncEngine.fetchOrSync(
                        url,
                        tabId,
                        mode,
                    ) { msg -> app.runOnUiThread { onStatus(msg) } }
                }
            val downloadPlan = SyncDownloadPlanning.plan(existingBeforeSync, story)
            onDone(story)
            handleManualSyncDownloads(story, downloadPlan)
        } catch (error: Throwable) {
            // Never swallow cancellation — scope cancellation must propagate, not become onError.
            if (error is CancellationException) throw error
            Timber.w(error, "Sync failed for %s", url)
            if (error is SourceAccessBlockedException) {
                showLibrary()
                showSourceAccessBlockedDialog(url) {
                    syncStory(url, tabId, mode, onStatus, onDone, onError)
                }
                return@launch
            }
            onError(error)
        }
    }
}

/**
 * Syncs an existing story in place, driving the Details screen's inline operation UI (button flips
 * to "Syncing..." + a live status line) instead of navigating to a full-screen "Working" page —
 * that flow is reserved for brand-new fetches where no Details screen exists yet.
 */
@Suppress(
    "TooGenericExceptionCaught",
    "InstanceOfCheckForException",
)
// Route non-cancellation failures through the user-facing error path.
internal fun ScreenHost.syncStory(
    story: Story,
    mode: StorySyncMode = StorySyncMode.Default,
) {
    if (!StoryActionGuards.canModifyStory(story)) {
        toast(StoryActionGuards.archivedActionMessage("Sync"))
        return
    }
    // Ensure Details is showing so the inline spinner is visible (Sync can be triggered from the
    // Library dialog, where activeStory may differ or be null).
    if (activeStory?.id != story.id) showDetails(story.id)
    setStoryOperation(story.id, StoryOperationKind.SYNC, "Starting...")
    scope.launch {
        try {
            // Pre-sync state lets download planning distinguish chapters discovered by this sync
            // from an older cancelled/failed backlog.
            val existingBeforeSync =
                withContext(Dispatchers.IO) {
                    SourceRegistry.getProvider(story.sourceId, story.sourceUrl)?.let { provider ->
                        runCatching { repository.story(provider.getStoryId(story.sourceUrl)) }.getOrNull()
                    }
                }
            val synced =
                withContext(Dispatchers.IO) {
                    syncEngine.fetchOrSync(story.sourceUrl, story.tabId, mode) { msg ->
                        app.runOnUiThread { setStoryOperation(story.id, StoryOperationKind.SYNC, msg) }
                    }
                }
            val downloadPlan = SyncDownloadPlanning.plan(existingBeforeSync, synced)
            clearStoryOperation(synced.id, StoryOperationKind.SYNC, rerender = false)
            showDetails(synced.id)
            handleManualSyncDownloads(synced, downloadPlan)
        } catch (error: Throwable) {
            // Never swallow cancellation.
            if (error is CancellationException) throw error
            Timber.w(error, "In-place sync failed for %s", story.id)
            clearStoryOperation(story.id, StoryOperationKind.SYNC, rerender = false)
            if (error is SourceAccessBlockedException) {
                showDetails(story.id)
                showSourceAccessBlockedDialog(story.sourceUrl) {
                    syncStory(story, mode)
                }
                return@launch
            }
            toast(error.message ?: "Sync failed")
            // Stay on Details (not the Library) so the user sees the result in context.
            showDetails(story.id)
        }
    }
}
