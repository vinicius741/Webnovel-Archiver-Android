package com.vinicius741.webnovelarchiver.feature.updates

import com.vinicius741.webnovelarchiver.data.diagnostics.ActivityCategory
import com.vinicius741.webnovelarchiver.data.diagnostics.ActivityLog
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.StoryActionGuards
import com.vinicius741.webnovelarchiver.domain.story.UpdatesCopy
import com.vinicius741.webnovelarchiver.domain.story.UpdatesPlanning
import com.vinicius741.webnovelarchiver.feature.browser.showSourceAccessBlockedDialog
import com.vinicius741.webnovelarchiver.feature.story.ManualStorySync
import com.vinicius741.webnovelarchiver.feature.story.SyncDownloadAction
import com.vinicius741.webnovelarchiver.feature.story.SyncDownloadPlan
import com.vinicius741.webnovelarchiver.feature.story.clearStoryOperation
import com.vinicius741.webnovelarchiver.feature.story.handleManualSyncDownloads
import com.vinicius741.webnovelarchiver.feature.story.queueDownload
import com.vinicius741.webnovelarchiver.feature.story.setStoryOperation
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.navigation.StoryOperationKind
import com.vinicius741.webnovelarchiver.source.network.SourceAccessBlockedException
import com.vinicius741.webnovelarchiver.sync.StorySyncMode
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Updates sync progress for one activity ([ScreenHost.updatesSyncState]); it outlives the screen's
 * re-renders and navigation. Owned by the activity, so the screen callbacks it holds die with it.
 */
class UpdatesSyncState {
    /** Story id to the sync engine's latest status line, while that novel syncs. */
    internal val syncing: MutableMap<String, String> = linkedMapOf()
    internal var batch: UpdatesBatch? = null

    /** Summary of the last finished "Sync all", shown in the forecast card until the next one. */
    internal var lastResult: String? = null
    internal var filter: UpdatesFilter = UpdatesFilter.ALL

    /** The overview planned in the background for the badge, reused when Updates opens. */
    internal val overviewCache = UpdatesOverviewCache()

    /** A finished run's large-update reviews and blocked source, held until the user is on Updates. */
    internal val pendingReviews = mutableListOf<Pair<Story, SyncDownloadPlan>>()
    internal var pendingBlocked: Story? = null

    /** Set by the rendered Updates screen; null while it is not showing. */
    internal var onChange: (() -> Unit)? = null
    internal var onStatus: ((String, String) -> Unit)? = null

    internal fun changed() {
        onChange?.invoke()
    }
}

/** One "Sync all that may be out" run: novels are checked one after another. */
internal class UpdatesBatch(
    val storyIds: List<String>,
) {
    var checked = 0
    var newChapters = 0
    var novelsWithNew = 0
    var failed = 0
    var stopping = false

    /** The run was cut short (the activity went away) rather than finishing or being stopped. */
    var cancelled = false
    var currentTitle: String? = null
    val total: Int get() = storyIds.size

    /** Large updates wait for the end of the run so their review dialogs do not pile up mid-batch. */
    val deferredReviews = mutableListOf<Pair<Story, SyncDownloadPlan>>()
    var blocked: Story? = null

    fun summary(): String = UpdatesCopy.batchSummary(total, checked, newChapters, novelsWithNew, failed, stopping || cancelled)
}

internal val ScreenHost.updatesState: UpdatesSyncState
    get() = updatesSyncState

/** True while Updates syncs [storyId] (row or "Sync all"); Details content actions wait for it. */
internal fun ScreenHost.isSyncingFromUpdates(storyId: String): Boolean = storyId in updatesSyncState.syncing

private sealed interface UpdatesSyncOutcome {
    data class Synced(
        val newChapters: Int,
    ) : UpdatesSyncOutcome

    data object Blocked : UpdatesSyncOutcome

    data class Failed(
        val message: String,
    ) : UpdatesSyncOutcome
}

/** Syncs one novel from its Updates row, exactly like the Details Sync but without leaving Updates. */
internal fun ScreenHost.syncFromUpdates(story: Story) {
    val state = updatesState
    if (story.id in state.syncing) return
    if (!StoryActionGuards.canSync(story)) return toast(StoryActionGuards.archivedActionMessage("Sync"))
    if (storyOperation?.storyId == story.id) return toast("This novel is busy. Try again when it finishes.")
    scope.launch {
        when (val outcome = syncForUpdates(story, inBatch = false) { synced, plan -> handleManualSyncDownloads(synced, plan) }) {
            is UpdatesSyncOutcome.Synced ->
                toast(
                    if (outcome.newChapters >
                        0
                    ) {
                        UpdatesCopy.newChaptersFound(outcome.newChapters, story.title)
                    } else {
                        "No new chapters for ${story.title}"
                    },
                )
            UpdatesSyncOutcome.Blocked ->
                showSourceAccessBlockedDialog(
                    story.sourceUrl,
                ) { repository.story(story.id)?.let(::syncFromUpdates) }
            is UpdatesSyncOutcome.Failed -> toast(outcome.message)
        }
    }
}

/** Checks every novel that may have a new chapter out, one at a time, until done or stopped. */
internal fun ScreenHost.syncAllMayBeOut() {
    val state = updatesState
    if (state.batch != null) return
    val ids =
        UpdatesPlanning
            .overview(repository.library(), System.currentTimeMillis())
            .entries(UpdatesPlanning.Section.MAY_BE_OUT)
            .map { it.story.id }
    if (ids.isEmpty()) return toast("Nothing looks due right now")
    val batch = UpdatesBatch(ids)
    state.batch = batch
    state.lastResult = null
    ActivityLog.record(ActivityCategory.SYNC, "updates_batch_started", "count" to ids.size, "storyIds" to ids)
    state.changed()
    scope.launch {
        try {
            runBatch(batch)
        } catch (cancelled: CancellationException) {
            batch.cancelled = true
            throw cancelled
        } finally {
            finishBatch(batch)
        }
    }
}

/** Asks the running batch to stop once the novel it is checking now finishes (the card says so). */
internal fun ScreenHost.stopUpdatesBatch() {
    val batch = updatesState.batch ?: return
    batch.stopping = true
    updatesState.changed()
}

/** Queues every chapter of [story] that is not downloaded yet, like Details' "Download remaining". */
internal fun ScreenHost.downloadMissingFromUpdates(story: Story) {
    val latest = repository.story(story.id) ?: return
    val indexes = UpdatesPlanning.missingChapterIndexes(latest)
    if (indexes.isNotEmpty()) queueDownload(latest, indexes)
}

private suspend fun ScreenHost.runBatch(batch: UpdatesBatch) {
    val state = updatesState
    for (id in batch.storyIds) {
        if (batch.stopping) break
        val story = withContext(Dispatchers.IO) { repository.story(id) }
        val skip = story == null || id in state.syncing || storyOperation?.storyId == id || !StoryActionGuards.canSync(story)
        if (story == null || skip) {
            batch.checked += 1
            continue
        }
        batch.currentTitle = story.title
        val outcome =
            syncForUpdates(story, inBatch = true) { synced, plan ->
                if (plan.action ==
                    SyncDownloadAction.REVIEW
                ) {
                    batch.deferredReviews += synced to plan
                } else {
                    handleManualSyncDownloads(synced, plan)
                }
            }
        when (outcome) {
            is UpdatesSyncOutcome.Synced ->
                if (outcome.newChapters > 0) {
                    batch.newChapters += outcome.newChapters
                    batch.novelsWithNew += 1
                }
            UpdatesSyncOutcome.Blocked -> {
                batch.failed += 1
                if (batch.blocked == null) batch.blocked = story
            }
            is UpdatesSyncOutcome.Failed -> batch.failed += 1
        }
        batch.checked += 1
        state.changed()
    }
}

private fun ScreenHost.finishBatch(batch: UpdatesBatch) {
    val state = updatesState
    if (state.batch === batch) state.batch = null
    batch.currentTitle = null
    val summary = batch.summary()
    state.lastResult = summary
    ActivityLog.record(
        ActivityCategory.SYNC,
        "updates_batch_finished",
        "count" to batch.total,
        "checked" to batch.checked,
        "failed" to batch.failed,
        "newChapters" to batch.newChapters,
        "novelsWithNew" to batch.novelsWithNew,
        "stopped" to (batch.stopping || batch.cancelled),
        "cancelled" to batch.cancelled,
    )
    if (app.isFinishing || app.isDestroyed) return
    state.pendingReviews += batch.deferredReviews
    batch.blocked?.let { state.pendingBlocked = it }
    toast(summary)
    // The run can end long after the user moved on (the Reader, say): its dialogs wait for Updates,
    // whose forecast card offers them meanwhile.
    if (navigator.current == AppRoute.Updates) showPendingUpdatesReviews() else state.changed()
}

/** Shows a finished run's held large-update reviews and source check, then forgets them. */
internal fun ScreenHost.showPendingUpdatesReviews() {
    val state = updatesState
    val reviews = state.pendingReviews.toList()
    val blocked = state.pendingBlocked
    state.pendingReviews.clear()
    state.pendingBlocked = null
    state.changed()
    reviews.forEach { (story, plan) ->
        val latest = repository.story(story.id) ?: return@forEach
        val indexes = UpdatesPlanning.pendingReviewIndexes(latest, plan.chapterIds)
        if (indexes.isNotEmpty()) {
            handleManualSyncDownloads(latest, plan.copy(chapterIndexes = indexes, chapterIds = indexes.map { latest.chapters[it].id }))
        }
    }
    blocked?.let { story ->
        showSourceAccessBlockedDialog(story.sourceUrl) { repository.story(story.id)?.let(::syncFromUpdates) }
    }
}

/**
 * Runs the shared manual sync for one novel. A row sync also holds the Details operation slot when
 * it is free, so opening the novel shows the live sync. "Sync all" items do not take that global
 * slot (it would block Cleanup and EPUB on every novel for the whole run) and, like the former bulk
 * sync, skip the Patreon refresh; [isSyncingFromUpdates] keeps Details from starting a second sync.
 */
@Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
private suspend fun ScreenHost.syncForUpdates(
    story: Story,
    inBatch: Boolean,
    onPlan: (Story, SyncDownloadPlan) -> Unit,
): UpdatesSyncOutcome {
    val state = updatesState
    state.syncing[story.id] = STARTING
    val ownsSlot = !inBatch && storyOperation == null
    if (ownsSlot) setStoryOperation(story.id, StoryOperationKind.SYNC, STARTING)
    state.changed()
    try {
        val (synced, plan) =
            ManualStorySync(
                repository,
                syncEngine,
                refreshPatreonStats = !inBatch,
            ).run(story.sourceUrl, story.tabId, StorySyncMode.Default, story.sourceId) { message ->
                app.runOnUiThread {
                    if (story.id !in state.syncing) return@runOnUiThread
                    state.syncing[story.id] = message
                    state.onStatus?.invoke(story.id, message)
                    if (ownsSlot && storyOperation?.storyId == story.id) setStoryOperation(story.id, StoryOperationKind.SYNC, message)
                }
            }
        onPlan(synced, plan)
        return UpdatesSyncOutcome.Synced(plan.chapterIds.size)
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        Timber.w(error, "Updates sync failed for %s", story.id)
        return if (error is SourceAccessBlockedException) {
            UpdatesSyncOutcome.Blocked
        } else {
            UpdatesSyncOutcome.Failed(error.message ?: "Sync failed")
        }
    } finally {
        state.syncing.remove(story.id)
        if (ownsSlot) clearStoryOperation(story.id, StoryOperationKind.SYNC)
        state.changed()
    }
}

private const val STARTING = "Starting..."
