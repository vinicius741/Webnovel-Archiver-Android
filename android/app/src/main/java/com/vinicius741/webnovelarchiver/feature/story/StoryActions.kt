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
    if (!StoryActionGuards.canQueueDownloads(story)) {
        toast(StoryActionGuards.archivedActionMessage("Downloading"))
        return
    }
    val screenTagAtEnqueue = frame.tag
    val servicePrepared = runCatching { DownloadForegroundService.prepare(app.applicationContext) }
    if (servicePrepared.isFailure) {
        toast(servicePrepared.exceptionOrNull()?.message ?: "Could not start downloads")
        return
    }
    // Queue planning and durable JSON writes scale with the number of chapters, so keep them off
    // the main thread. Process scope ensures persistence and service handoff survive Activity
    // recreation after the user initiated the operation.
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
// E1: route non-cancellation failures through the user-facing error path.
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
    // Surface the very first status before any work begins so the caller can flip its UI to a
    // loading state immediately, instead of leaving the user with no feedback until the network
    // request resolves. Callers that render inline (Add Story) rely on this to disable the button.
    app.runOnUiThread { onStatus("Starting...") }
    scope.launch {
        try {
            val existingBeforeSync =
                withContext(Dispatchers.IO) {
                    SourceRegistry.getProvider(url)?.let { provider ->
                        runCatching { repository.getStory(provider.getStoryId(url)) }.getOrNull()
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
            repository.publishDownloadState(setOf(story.id), queueChanged = false)
            if (existingBeforeSync != null && !story.pendingNewChapterIds.isNullOrEmpty()) {
                val pending = story.pendingNewChapterIds.orEmpty().toSet()
                val indexes =
                    story.chapters.mapIndexedNotNull { index, chapter ->
                        if (chapter.id in pending && !chapter.downloaded) index else null
                    }
                if (indexes.isNotEmpty()) {
                    queueDownload(story, indexes)
                }
            }
            onDone(story)
        } catch (error: Throwable) {
            // E1: structured-concurrency safety — never swallow cancellation. A scope cancellation
            // (Activity destroyed / lifecycleScope cancelled) must propagate, not become an onError.
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
 * Sync an *existing* story in place — mirrors the RN `StoryActions` pattern: instead of navigating
 * away to a full-screen "Working" page, it drives the Details screen's inline operation UI (the same
 * `StoryOperationState` mechanism EPUB/CLEANUP use) so the button flips to "Syncing..." and a spinner
 * + status line appears right where the user tapped. Status callbacks from [StorySyncEngine.fetchOrSync]
 * ("Fetching from…", "Parsing chapters…") update that line live without leaving the screen.
 *
 * This intentionally does **not** reuse [syncStory]`s `(url, tabId)` overload, whose first line
 * navigates to a `screen(title = "Working")` — that full-screen flow is reserved for brand-new
 * fetches (Library "Fetch Story", Browser "Add") where no Details screen exists yet.
 */
@Suppress(
    "TooGenericExceptionCaught",
    "InstanceOfCheckForException",
)
// E1: route non-cancellation failures through the user-facing error path.
internal fun ScreenHost.syncStory(
    story: Story,
    mode: StorySyncMode = StorySyncMode.Default,
) {
    if (!StoryActionGuards.canSync(story)) {
        toast(StoryActionGuards.archivedActionMessage("Sync"))
        return
    }
    // Make sure we're on the Details screen so the inline spinner is visible — Sync can also be
    // triggered from the Library's story-action dialog, where activeStory may differ or be null.
    if (activeStory?.id != story.id) showDetails(story.id)
    setStoryOperation(story.id, StoryOperationKind.SYNC, "Starting...")
    scope.launch {
        try {
            // Re-read the pre-sync state so the new-chapter auto-download logic below matches the
            // url-overload path exactly (it keys off pendingNewChapterIds set during the merge).
            val existingBeforeSync =
                withContext(Dispatchers.IO) {
                    SourceRegistry.getProvider(story.sourceUrl)?.let { provider ->
                        runCatching { repository.getStory(provider.getStoryId(story.sourceUrl)) }.getOrNull()
                    }
                }
            val synced =
                withContext(Dispatchers.IO) {
                    syncEngine.fetchOrSync(story.sourceUrl, story.tabId, mode) { msg ->
                        app.runOnUiThread { setStoryOperation(story.id, StoryOperationKind.SYNC, msg) }
                    }
                }
            repository.publishDownloadState(setOf(synced.id), queueChanged = false)
            if (existingBeforeSync != null && !synced.pendingNewChapterIds.isNullOrEmpty()) {
                val pending = synced.pendingNewChapterIds.orEmpty().toSet()
                val indexes =
                    synced.chapters.mapIndexedNotNull { index, chapter ->
                        if (chapter.id in pending && !chapter.downloaded) index else null
                    }
                if (indexes.isNotEmpty()) {
                    queueDownload(synced, indexes)
                }
            }
            clearStoryOperation(synced.id, StoryOperationKind.SYNC, rerender = false)
            showDetails(synced.id)
        } catch (error: Throwable) {
            // E1: structured-concurrency safety — never swallow cancellation.
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
