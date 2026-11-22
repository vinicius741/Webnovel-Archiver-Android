package com.vinicius741.webnovelarchiver.feature.downloads

import com.vinicius741.webnovelarchiver.data.repository.DownloadUiSnapshot
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.download.DownloadCounts
import com.vinicius741.webnovelarchiver.download.DownloadManagerPlanning
import com.vinicius741.webnovelarchiver.download.DownloadPacingSnapshot
import com.vinicius741.webnovelarchiver.download.DownloadQueueSectionPlanning
import com.vinicius741.webnovelarchiver.download.GlobalQueueAction
import com.vinicius741.webnovelarchiver.download.QueueAppBarPlan
import com.vinicius741.webnovelarchiver.download.SourceCheckOutcome
import com.vinicius741.webnovelarchiver.navigation.ScreenModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** One change the Downloads screen makes to the queue; [QueueData.apply] hands it to the download engine. */
internal sealed interface QueueControl {
    /** Resume and retry start chapters, so the download service must be running afterwards. */
    val startsDownloads: Boolean get() = false

    data object PauseAll : QueueControl

    data object ResumeAll : QueueControl {
        override val startsDownloads get() = true
    }

    data object RetryFailed : QueueControl {
        override val startsDownloads get() = true
    }

    data object ClearFinished : QueueControl

    data object CancelAll : QueueControl

    data class PauseStory(
        val storyId: String,
    ) : QueueControl

    data class ResumeStory(
        val storyId: String,
    ) : QueueControl {
        override val startsDownloads get() = true
    }

    data class RetryStory(
        val storyId: String,
    ) : QueueControl {
        override val startsDownloads get() = true
    }

    data class CancelStory(
        val storyId: String,
    ) : QueueControl

    data class RemoveStory(
        val storyId: String,
    ) : QueueControl

    /** Moves the novel to the front of the queue ("Download now", or a drop onto Downloading now). */
    data class DownloadNow(
        val storyId: String,
    ) : QueueControl

    data class MoveToBottom(
        val storyId: String,
    ) : QueueControl

    /** The up-next novels in their new order after a drag. */
    data class Reorder(
        val storyIds: List<String>,
    ) : QueueControl

    data class PauseJob(
        val jobId: String,
    ) : QueueControl

    data class ResumeJob(
        val jobId: String,
    ) : QueueControl {
        override val startsDownloads get() = true
    }

    data class RetryJob(
        val jobId: String,
    ) : QueueControl {
        override val startsDownloads get() = true
    }

    data class CancelJob(
        val jobId: String,
    ) : QueueControl

    data class RemoveJob(
        val jobId: String,
    ) : QueueControl

    data class PauseSource(
        val providerId: String,
    ) : QueueControl
}

/** What the Downloads screen reads and changes: the published queue, pacing, site checks, and queue controls. */
internal interface QueueData {
    /** The repository's published library and queue (never deep-copied). */
    val downloads: StateFlow<DownloadUiSnapshot>
    val pacing: StateFlow<Map<String, DownloadPacingSnapshot>>
    val checkOutcomes: StateFlow<Map<String, SourceCheckOutcome>>

    fun nowMillis(): Long

    /** Emits the time every second while collected, for countdown rows. */
    fun ticker(): Flow<Long>

    fun providerIdForJob(job: DownloadJob): String?

    /** Whether the site's circuit asks for a human check; safe to call from any thread. */
    fun isSourceBlocked(providerId: String): Boolean

    /** Applies [control] to the queue; the repository moves the durable work to its I/O dispatcher. */
    suspend fun apply(control: QueueControl)

    /** Starts the download service (asking for the notification permission first when needed). */
    fun startDownloads()

    /** Opens the site check at [url]; the solver resumes the site's chapters itself once it passes. */
    fun startSourceCheck(
        providerId: String,
        url: String,
    )
}

/** What the Downloads screen shows: the list rows, the app-bar subtitle and actions. */
internal data class QueueUiState(
    val items: List<QueueItem>,
    val subtitle: String,
    val appBar: QueueAppBarPlan,
    val empty: Boolean,
) {
    /** The app bar's actions; a change rebuilds the screen, everything else is patched. */
    val appBarKey: Pair<GlobalQueueAction?, Boolean> get() = appBar.toggle to appBar.overflow.isEmpty()
}

/** A novel's menu: its chapter counts and how many novels are up next, read at tap time. */
internal data class QueueStoryMenu(
    val counts: DownloadCounts,
    val upNextCount: Int,
)

/**
 * The Downloads screen (`AppRoute.Queue`): the published queue planned into sections
 * ([DownloadQueueSectionPlanning]) and flattened into rows ([buildQueueItems]) with the request
 * pacing, the site-check outcomes and the user's expand/collapse choices. It follows its sources,
 * with a one-second ticker for countdowns, only while a renderer collects [state], and plans off the
 * main thread. Queue controls run in [scope] and finish even if the screen goes away mid-save.
 */
internal class QueueScreenModel(
    private val data: QueueData,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val planning: CoroutineDispatcher = Dispatchers.Default,
) : ScreenModel(scope) {
    /** Novels whose chapters the user opened or closed (and [FINISHED_SECTION_KEY]); absent means closed. */
    private val choices = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** The queue version when a drag was dropped: renders wait for the saved order instead of snapping back. */
    private var reorderHold: ReorderHold? = null

    /** What [state] was last planned from (main thread). */
    private var planned: Planned? = null

    private val messageChannel = Channel<String>(MESSAGE_BUFFER, BufferOverflow.DROP_OLDEST)
    private val mutableState = MutableStateFlow(plan(currentInputs()).also { planned = it }.state)

    val state: StateFlow<QueueUiState> = mutableState.asStateFlow()

    /** One-off messages for the user (failed saves), buffered until a renderer shows them. */
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    init {
        this.scope.launch {
            mutableState.subscriptionCount
                .map { it > 0 }
                .distinctUntilChanged()
                .collectLatest { observed -> if (observed) follow() }
        }
    }

    /** Brings the state up to date for a new render (Back to the screen, a fold), planning on the calling thread. */
    fun onRender(): QueueUiState {
        planIfChanged(currentInputs(), planned)?.let(::publish)
        return state.value
    }

    // Expand and collapse

    fun toggleStory(storyId: String) = choose(storyId) { it != true }

    fun setStoryExpanded(
        storyId: String,
        expanded: Boolean,
    ) = choose(storyId) { expanded }

    fun toggleFinished() = choose(FINISHED_SECTION_KEY) { it != true }

    /** The rendered list follows the choice through [follow]; without a renderer, the next render plans it. */
    private fun choose(
        key: String,
        next: (Boolean?) -> Boolean,
    ) {
        choices.value += key to next(choices.value[key])
    }

    // Queue controls

    /** Runs [control]; a failed save is logged and reported, not crashed on. */
    fun run(control: QueueControl) {
        scope.launch {
            try {
                // The change was confirmed on screen, so leaving the screen mid-save must not drop it.
                withContext(NonCancellable) {
                    data.apply(control)
                    // Start downloads only after the resumed/retried jobs have been persisted.
                    if (control.startsDownloads) data.startDownloads()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (
                @Suppress("TooGenericExceptionCaught") error: Exception,
            ) {
                Timber.w(error, "Queue control failed: %s", control)
                messageChannel.trySend("Couldn't save that change")
            }
        }
    }

    /**
     * A drag ended: [downloadNow] when the card was dropped among Downloading now (that novel moves
     * to the front), else the up-next novels in their new order. The list keeps the dragged order
     * until the saved queue arrives, or for a few seconds.
     */
    fun reorder(
        upNextOrder: List<String>,
        downloadNow: String?,
    ) {
        reorderHold = ReorderHold(data.downloads.value.queueVersion, data.nowMillis())
        run(if (downloadNow != null) QueueControl.DownloadNow(downloadNow) else QueueControl.Reorder(upNextOrder))
    }

    /** Opens a blocked site's check from its card or a chapter's menu. */
    fun verifySite(
        providerId: String,
        url: String,
    ) = data.startSourceCheck(providerId, url)

    // Reads at tap time, so a menu never offers an action the queue has already outgrown

    /** The whole-queue actions the app bar's overflow offers now. */
    fun overflow(): List<GlobalQueueAction> {
        val plan = sectionPlan(data.downloads.value.queue)
        return DownloadManagerPlanning.appBar(plan.counts, plan.failedChapters).overflow
    }

    fun storyMenu(storyId: String): QueueStoryMenu {
        val queue = data.downloads.value.queue
        return QueueStoryMenu(DownloadCounts.from(queue.filter { it.storyId == storyId }), sectionPlan(queue).upNext.size)
    }

    fun job(jobId: String): DownloadJob? =
        data.downloads.value.queue
            .firstOrNull { it.id == jobId }

    fun providerOf(job: DownloadJob): String? = data.providerIdForJob(job)

    /** The novel as published, for its card's cover. */
    fun story(storyId: String): Story? =
        data.downloads.value.library
            .firstOrNull { it.id == storyId }

    // Planning

    private suspend fun follow() {
        combine(data.downloads, data.pacing, data.checkOutcomes, data.ticker(), choices) { snapshot, pacing, outcomes, now, open ->
            Inputs(snapshot.queue, snapshot.queueVersion, pacing, outcomes, now, open)
        }.conflate()
            .collect { inputs ->
                val hold = reorderHold
                if (hold != null) {
                    if (inputs.queueVersion == hold.queueVersion && inputs.now - hold.atMillis < REORDER_HOLD_MILLIS) return@collect
                    reorderHold = null
                }
                val previous = planned
                val next = withContext(planning) { planIfChanged(inputs, previous) } ?: return@collect
                publish(next)
            }
    }

    private fun publish(next: Planned) {
        planned = next
        mutableState.value = next.state
    }

    /**
     * Plans [inputs] unless nothing a row shows can differ from [previous]: the queue, the site
     * checks, the blocked sites (a circuit opens or closes without a queue write) and the choices
     * are the same, and no countdown is running or just ended.
     */
    private fun planIfChanged(
        inputs: Inputs,
        previous: Planned?,
    ): Planned? {
        val blocked = blockedProviders(inputs.queue)
        val timed = hasTimedStatus(inputs)
        val unchanged =
            previous != null &&
                previous.queueVersion == inputs.queueVersion &&
                previous.outcomes == inputs.outcomes &&
                previous.blocked == blocked &&
                previous.choices == inputs.choices &&
                !timed &&
                !previous.timed
        return if (unchanged) null else plan(inputs, blocked, timed)
    }

    private fun plan(
        inputs: Inputs,
        blocked: Set<String> = blockedProviders(inputs.queue),
        timed: Boolean = hasTimedStatus(inputs),
    ): Planned {
        val plan = sectionPlan(inputs.queue)
        val items =
            if (inputs.queue.isEmpty()) {
                emptyList()
            } else {
                val cards = DownloadQueueSectionPlanning.checkCards(plan.blockedSites, inputs.outcomes, inputs.now)
                buildQueueItems(plan, cards, inputs.queue, inputs.pacing.values, inputs.now, inputs.choices)
            }
        val state =
            QueueUiState(
                items = items,
                subtitle = QueueSummaryPlanning.subtitle(plan),
                appBar = DownloadManagerPlanning.appBar(plan.counts, plan.failedChapters),
                empty = inputs.queue.isEmpty(),
            )
        return Planned(state, inputs.queueVersion, inputs.outcomes, blocked, inputs.choices, timed)
    }

    private fun sectionPlan(queue: List<DownloadJob>) =
        DownloadQueueSectionPlanning.plan(queue, data::providerIdForJob, data::isSourceBlocked)

    private fun blockedProviders(queue: List<DownloadJob>): Set<String> =
        DownloadQueueSectionPlanning
            .blockedSites(queue, data::providerIdForJob, data::isSourceBlocked)
            .mapTo(HashSet()) { it.providerId }

    private fun currentInputs(): Inputs {
        val snapshot = data.downloads.value
        return Inputs(snapshot.queue, snapshot.queueVersion, data.pacing.value, data.checkOutcomes.value, data.nowMillis(), choices.value)
    }

    private class Inputs(
        val queue: List<DownloadJob>,
        val queueVersion: Long,
        val pacing: Map<String, DownloadPacingSnapshot>,
        val outcomes: Map<String, SourceCheckOutcome>,
        val now: Long,
        val choices: Map<String, Boolean>,
    )

    private class Planned(
        val state: QueueUiState,
        val queueVersion: Long,
        val outcomes: Map<String, SourceCheckOutcome>,
        val blocked: Set<String>,
        val choices: Map<String, Boolean>,
        /** A countdown was showing, so the next tick plans again even when it ended. */
        val timed: Boolean,
    )

    private class ReorderHold(
        val queueVersion: Long,
        val atMillis: Long,
    )

    private companion object {
        const val REORDER_HOLD_MILLIS = 3_000L
        const val MESSAGE_BUFFER = 8

        fun hasTimedStatus(inputs: Inputs): Boolean =
            inputs.pacing.values.any { it.nextRequestAtMillis > inputs.now } ||
                inputs.queue.any { (it.nextRetryAt ?: 0L) > inputs.now } ||
                inputs.outcomes.values.any {
                    it.verified && inputs.now - it.atMillis < DownloadQueueSectionPlanning.VERIFIED_CARD_MILLIS + 1_000L
                }
    }
}
