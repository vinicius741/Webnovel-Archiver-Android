package com.vinicius741.webnovelarchiver.feature.updates

import com.vinicius741.webnovelarchiver.data.diagnostics.ActivityCategory
import com.vinicius741.webnovelarchiver.data.diagnostics.ActivityLog
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.StoryActionGuards
import com.vinicius741.webnovelarchiver.domain.story.UpdatesCopy
import com.vinicius741.webnovelarchiver.domain.story.UpdatesPlanning
import com.vinicius741.webnovelarchiver.operations.ManualStorySyncResult
import com.vinicius741.webnovelarchiver.operations.StoryOperations
import com.vinicius741.webnovelarchiver.operations.SyncDownloadAction
import com.vinicius741.webnovelarchiver.operations.SyncDownloadPlan
import com.vinicius741.webnovelarchiver.source.network.SourceAccessBlockedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/** What the Updates syncs need from the app: the sync itself, the library, and the dialogs and toasts a result shows. */
internal interface UpdatesSyncHost {
    /** The shared sync from Updates ([com.vinicius741.webnovelarchiver.operations.StoryWork.syncFromUpdates]). */
    suspend fun sync(
        story: Story,
        inBatch: Boolean,
    ): ManualStorySyncResult

    /** A copy of the story as published now, or null when it is gone. */
    fun story(id: String): Story?

    /** Queues the sync's new chapters, or asks first for a large update (the story page's path). */
    fun handleDownloads(
        story: Story,
        plan: SyncDownloadPlan,
    )

    /** Explains a source that asks for a check; retrying syncs the novel again. */
    fun showBlocked(story: Story)

    fun toast(message: String)

    /** Whether Updates is the screen on top (held dialogs wait for it). */
    fun onUpdates(): Boolean

    /** False once the activity is finishing, when a finished run shows nothing. */
    fun canShow(): Boolean
}

/** A running "Sync all": how far it got, for the forecast card. */
internal data class UpdatesBatchProgress(
    val checked: Int,
    val total: Int,
    val currentTitle: String?,
    val stopping: Boolean,
)

/** The Updates work that outlives the screen: the running batch, its last summary and its held dialogs. */
internal data class UpdatesSyncsState(
    val batch: UpdatesBatchProgress? = null,
    /** Summary of the last finished "Sync all", shown in the forecast card until the next one. */
    val lastResult: String? = null,
    /** Large-update reviews a finished run holds until the user is on Updates. */
    val pendingReviews: Int = 0,
    /** A finished run hit a source that asks for a check. */
    val pendingBlocked: Boolean = false,
)

/**
 * The Updates syncs of one activity: a row's sync and "Sync all that may be out", which checks
 * novels one at a time. They run in the activity's [scope], not the Updates screen model's, so a
 * run keeps going while the user is elsewhere (the Reader, Downloads) and ends with the activity,
 * as before. Each novel syncs through [UpdatesSyncHost.sync], which publishes its progress to
 * [StoryOperations.updatesSyncs]; the batch, its summary and the dialogs it holds are [state].
 * Also keeps the forecast [overviewCache] that the navigation badge plans and Updates reuses.
 */
class UpdatesSyncs internal constructor(
    private val host: UpdatesSyncHost,
    private val operations: StoryOperations,
    private val scope: CoroutineScope,
) {
    /** The overview planned in the background for the badge, reused when Updates opens. */
    internal val overviewCache = UpdatesOverviewCache()

    private val mutableState = MutableStateFlow(UpdatesSyncsState())
    internal val state: StateFlow<UpdatesSyncsState> = mutableState.asStateFlow()

    private var batch: Batch? = null
    private val pendingReviews = mutableListOf<Pair<Story, SyncDownloadPlan>>()
    private var pendingBlocked: Story? = null

    /** Syncs one novel from its Updates row, exactly like the story page's Sync but without leaving Updates. */
    fun sync(story: Story) {
        if (operations.isSyncingFromUpdates(story.id)) return
        if (!StoryActionGuards.canSync(story)) return host.toast(StoryActionGuards.archivedActionMessage("Sync"))
        if (operations.contentFor(story.id) != null) return host.toast("This novel is busy. Try again when it finishes.")
        // Listed before this returns, so a second tap is ignored.
        operations.startUpdatesSync(story.id, STARTING)
        scope.launch {
            when (val outcome = syncOne(story, inBatch = false) { synced, plan -> host.handleDownloads(synced, plan) }) {
                is Outcome.Synced ->
                    host.toast(
                        if (outcome.newChapters > 0) {
                            UpdatesCopy.newChaptersFound(outcome.newChapters, story.title)
                        } else {
                            "No new chapters for ${story.title}"
                        },
                    )
                Outcome.Blocked -> host.showBlocked(story)
                is Outcome.Failed -> host.toast(outcome.message)
            }
        }
    }

    /** Checks [storyIds] (the novels that may have a new chapter out), one at a time, until done or stopped. */
    fun syncAll(storyIds: List<String>) {
        if (batch != null) return
        if (storyIds.isEmpty()) return host.toast("Nothing looks due right now")
        val run = Batch(storyIds)
        batch = run
        ActivityLog.record(ActivityCategory.SYNC, "updates_batch_started", "count" to storyIds.size, "storyIds" to storyIds)
        publish(lastResult = null)
        scope.launch {
            try {
                runBatch(run)
            } catch (cancelled: CancellationException) {
                run.cancelled = true
                throw cancelled
            } finally {
                finishBatch(run)
            }
        }
    }

    /** Asks the running batch to stop once the novel it is checking now finishes (the card says so). */
    fun stop() {
        val run = batch ?: return
        run.stopping = true
        publish()
    }

    /** Shows a finished run's held large-update reviews and source check, then forgets them. */
    fun showPendingReviews() {
        val reviews = pendingReviews.toList()
        val blocked = pendingBlocked
        pendingReviews.clear()
        pendingBlocked = null
        publish()
        reviews.forEach { (story, plan) ->
            val latest = host.story(story.id) ?: return@forEach
            val indexes = UpdatesPlanning.pendingReviewIndexes(latest, plan.chapterIds)
            if (indexes.isNotEmpty()) {
                host.handleDownloads(latest, plan.copy(chapterIndexes = indexes, chapterIds = indexes.map { latest.chapters[it].id }))
            }
        }
        blocked?.let(host::showBlocked)
    }

    private suspend fun runBatch(run: Batch) {
        for (id in run.storyIds) {
            if (run.stopping) break
            val story = host.story(id)
            val skip =
                story == null ||
                    operations.isSyncingFromUpdates(id) ||
                    operations.contentFor(id) != null ||
                    !StoryActionGuards.canSync(story)
            if (story == null || skip) {
                run.checked += 1
                continue
            }
            run.currentTitle = story.title
            publish()
            operations.startUpdatesSync(story.id, STARTING)
            val outcome =
                syncOne(story, inBatch = true) { synced, plan ->
                    if (plan.action ==
                        SyncDownloadAction.REVIEW
                    ) {
                        run.deferredReviews += synced to plan
                    } else {
                        host.handleDownloads(synced, plan)
                    }
                }
            when (outcome) {
                is Outcome.Synced ->
                    if (outcome.newChapters > 0) {
                        run.newChapters += outcome.newChapters
                        run.novelsWithNew += 1
                    }
                Outcome.Blocked -> {
                    run.failed += 1
                    if (run.blocked == null) run.blocked = story
                }
                is Outcome.Failed -> run.failed += 1
            }
            run.checked += 1
            publish()
        }
    }

    private fun finishBatch(run: Batch) {
        if (batch === run) batch = null
        run.currentTitle = null
        val summary = run.summary()
        ActivityLog.record(
            ActivityCategory.SYNC,
            "updates_batch_finished",
            "count" to run.total,
            "checked" to run.checked,
            "failed" to run.failed,
            "newChapters" to run.newChapters,
            "novelsWithNew" to run.novelsWithNew,
            "stopped" to (run.stopping || run.cancelled),
            "cancelled" to run.cancelled,
        )
        if (!host.canShow()) return
        pendingReviews += run.deferredReviews
        run.blocked?.let { pendingBlocked = it }
        publish(lastResult = summary)
        host.toast(summary)
        // The run can end long after the user moved on (the Reader, say): its dialogs wait for
        // Updates, whose forecast card offers them meanwhile.
        if (host.onUpdates()) showPendingReviews()
    }

    /** Runs the shared sync for one novel already listed as syncing; the sync unlists it when it ends. */
    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
    private suspend fun syncOne(
        story: Story,
        inBatch: Boolean,
        onPlan: (Story, SyncDownloadPlan) -> Unit,
    ): Outcome {
        try {
            val (synced, plan) = host.sync(story, inBatch)
            onPlan(synced, plan)
            return Outcome.Synced(plan.chapterIds.size)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Timber.w(error, "Updates sync failed for %s", story.id)
            return if (error is SourceAccessBlockedException) Outcome.Blocked else Outcome.Failed(error.message ?: "Sync failed")
        } finally {
            operations.endUpdatesSync(story.id)
        }
    }

    private fun publish(lastResult: String? = state.value.lastResult) {
        mutableState.value =
            UpdatesSyncsState(
                batch = batch?.let { UpdatesBatchProgress(it.checked, it.total, it.currentTitle, it.stopping) },
                lastResult = lastResult,
                pendingReviews = pendingReviews.size,
                pendingBlocked = pendingBlocked != null,
            )
    }

    private sealed interface Outcome {
        data class Synced(
            val newChapters: Int,
        ) : Outcome

        data object Blocked : Outcome

        data class Failed(
            val message: String,
        ) : Outcome
    }

    /** One "Sync all that may be out" run. */
    private class Batch(
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

    private companion object {
        const val STARTING = "Starting..."
    }
}
