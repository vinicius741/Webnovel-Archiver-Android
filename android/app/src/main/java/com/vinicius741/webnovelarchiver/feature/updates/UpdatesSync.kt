package com.vinicius741.webnovelarchiver.feature.updates

import androidx.appcompat.app.AppCompatActivity
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
import java.util.WeakHashMap

/** Updates sync progress for one activity; it outlives the screen's re-renders and navigation. */
internal class UpdatesSyncState {
    /** Story id to the sync engine's latest status line, while that novel syncs. */
    val syncing: MutableMap<String, String> = linkedMapOf()
    var batch: UpdatesBatch? = null

    /** Summary of the last finished "Sync all", shown in the forecast card until the next one. */
    var lastResult: String? = null
    var filter: UpdatesFilter = UpdatesFilter.ALL

    /** Set by the rendered Updates screen; null while it is not showing. */
    var onChange: (() -> Unit)? = null
    var onStatus: ((String, String) -> Unit)? = null

    fun changed() {
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
    var currentTitle: String? = null
    val total: Int get() = storyIds.size

    /** Large updates wait for the end of the run so their review dialogs do not pile up mid-batch. */
    val deferredReviews = mutableListOf<Pair<Story, SyncDownloadPlan>>()
    var blocked: Story? = null

    fun summary(): String = UpdatesCopy.batchSummary(total, checked, newChapters, novelsWithNew, failed, stopping)
}

private val updatesStates = WeakHashMap<AppCompatActivity, UpdatesSyncState>()

internal val ScreenHost.updatesState: UpdatesSyncState
    get() = updatesStates.getOrPut(app) { UpdatesSyncState() }

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
        when (val outcome = syncForUpdates(story) { synced, plan -> handleManualSyncDownloads(synced, plan) }) {
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
        } finally {
            finishBatch(batch)
        }
    }
}

/** Asks the running batch to stop once the novel it is checking now finishes. */
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
            syncForUpdates(story) { synced, plan ->
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
        "stopped" to batch.stopping,
    )
    if (app.isFinishing || app.isDestroyed) return
    state.changed()
    toast(summary)
    batch.deferredReviews.forEach { (story, plan) -> handleManualSyncDownloads(story, plan) }
    batch.blocked?.let { blocked ->
        showSourceAccessBlockedDialog(blocked.sourceUrl) { repository.story(blocked.id)?.let(::syncFromUpdates) }
    }
}

/**
 * Runs the shared manual sync for one novel. While it runs, the novel also holds the Details
 * operation slot when that slot is free, so opening it shows the live sync and blocks a second one.
 */
@Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
private suspend fun ScreenHost.syncForUpdates(
    story: Story,
    onPlan: (Story, SyncDownloadPlan) -> Unit,
): UpdatesSyncOutcome {
    val state = updatesState
    state.syncing[story.id] = STARTING
    val ownsSlot = storyOperation == null
    if (ownsSlot) setStoryOperation(story.id, StoryOperationKind.SYNC, STARTING)
    state.changed()
    try {
        val (synced, plan) =
            ManualStorySync(repository, syncEngine).run(story.sourceUrl, story.tabId, StorySyncMode.Default, story.sourceId) { message ->
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
