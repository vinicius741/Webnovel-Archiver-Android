package com.vinicius741.webnovelarchiver.feature.updates

import com.vinicius741.webnovelarchiver.data.repository.DownloadUiSnapshot
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.UpdatesPlanning
import com.vinicius741.webnovelarchiver.domain.story.UpdatesPlanning.Overview
import com.vinicius741.webnovelarchiver.domain.story.UpdatesPlanning.Section
import com.vinicius741.webnovelarchiver.navigation.ScreenModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Updates filters: the overview, or one section in full. */
internal enum class UpdatesFilter(
    val label: String,
    val section: Section?,
) {
    ALL("All", null),
    MAY_BE_OUT("May be out", Section.MAY_BE_OUT),
    TO_DOWNLOAD("To download", Section.TO_DOWNLOAD),
    EXPECTED("Expected", Section.EXPECTED_SOON),
    LATE("Late", Section.RUNNING_LATE),
    SYNCED("Synced", Section.RECENTLY_SYNCED),
}

/** What Updates reads and starts: the published library, the forecast cache, the Updates syncs, and downloads. */
internal interface UpdatesData {
    /** The repository's published library and queue (never deep-copied). */
    val downloads: StateFlow<DownloadUiSnapshot>

    /** The "Sync all" batch, its summary and its held dialogs ([UpdatesSyncs.state]). */
    val syncs: StateFlow<UpdatesSyncsState>

    /** Novels syncing from Updates, to their latest status line. */
    val syncing: StateFlow<Map<String, String>>

    fun now(): Long

    /** The overview last planned for exactly these stories, however old, or null. */
    fun plannedOverview(library: List<Story>): Overview?

    /** The fresh overview for [library] at [now], planning it when needed; slow, so call it off the main thread. */
    fun overview(
        library: List<Story>,
        now: Long,
    ): Overview

    /** A copy of the story the caller may change. */
    fun story(id: String): Story?

    fun sync(story: Story)

    fun syncAll(storyIds: List<String>)

    fun stopBatch()

    fun showPendingReviews()

    /** Queues [indexes] of [story] for download (the download service and its permission prompt). */
    fun queueDownload(
        story: Story,
        indexes: List<Int>,
    )
}

internal data class UpdatesUiState(
    val storyCount: Int,
    val overview: Overview,
    /** When [overview] was planned; row status lines read the forecasts at this time. */
    val plannedAt: Long,
    val filter: UpdatesFilter,
    /** Novels syncing from Updates, to their latest status line. */
    val syncing: Map<String, String>,
    val syncs: UpdatesSyncsState,
    val jobsByStory: Map<String, List<DownloadJob>>,
) {
    val mayBeOut: Int get() = overview.count(Section.MAY_BE_OUT)
}

/**
 * The Updates destination (`AppRoute.Updates`): release forecasts for every novel
 * ([UpdatesPlanning]), the filter, and the live Updates syncs. Forecasting is planned off the main
 * thread (reusing the plan the navigation badge keeps in [UpdatesSyncs.overviewCache]) when the
 * library or queue changes, coalescing bursts; sync progress and the batch apply at once. It
 * follows its sources only while a renderer collects [state]. Syncs run in [UpdatesSyncs], so a
 * "Sync all" outlives this model.
 */
internal class UpdatesScreenModel(
    private val data: UpdatesData,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val planning: CoroutineDispatcher = Dispatchers.Default,
) : ScreenModel(scope) {
    private val mutableState = MutableStateFlow(initialState())
    val state: StateFlow<UpdatesUiState> = mutableState.asStateFlow()

    init {
        this.scope.launch {
            mutableState.subscriptionCount
                .map { it > 0 }
                .distinctUntilChanged()
                .collectLatest { observed -> if (observed) follow() }
        }
    }

    fun selectFilter(filter: UpdatesFilter) = mutableState.update { it.copy(filter = filter) }

    fun sync(storyId: String) {
        data.story(storyId)?.let(data::sync)
    }

    /** Checks every novel the forecast shows as May be out. */
    fun syncAll() =
        data.syncAll(
            state.value.overview
                .entries(Section.MAY_BE_OUT)
                .map { it.story.id },
        )

    fun stopBatch() = data.stopBatch()

    fun showPendingReviews() = data.showPendingReviews()

    /** Queues every chapter of the novel that is not downloaded yet, like the story page's "Download remaining". */
    fun downloadMissing(storyId: String) {
        val latest = data.story(storyId) ?: return
        val indexes = UpdatesPlanning.missingChapterIndexes(latest)
        if (indexes.isNotEmpty()) data.queueDownload(latest, indexes)
    }

    /** Queues the missing chapters of every novel in To download. */
    fun downloadAllMissing() =
        state.value.overview
            .entries(Section.TO_DOWNLOAD)
            .forEach { downloadMissing(it.story.id) }

    private suspend fun follow() =
        coroutineScope {
            launch {
                combine(data.syncs, data.syncing, ::Pair).collect { (syncs, syncing) ->
                    mutableState.update { it.copy(syncs = syncs, syncing = syncing) }
                }
            }
            // The first render may have shown an older plan; forecasts drift with the clock.
            replan()
            data.downloads
                .map { it.libraryVersion to it.queueVersion }
                .distinctUntilChanged()
                .drop(1)
                .conflate()
                .collect {
                    // A download publishes per chapter; one plan covers the burst.
                    delay(REFRESH_COALESCE_MS)
                    replan()
                }
        }

    private suspend fun replan() {
        val snapshot = data.downloads.value
        val now = data.now()
        val (overview, jobs) = withContext(planning) { data.overview(snapshot.library, now) to snapshot.queue.groupBy { it.storyId } }
        mutableState.update {
            it.copy(storyCount = snapshot.library.size, overview = overview, plannedAt = now, jobsByStory = jobs)
        }
    }

    /** The first render's state: the badge's plan for this library when there is one, so opening Updates does not forecast. */
    private fun initialState(): UpdatesUiState {
        val snapshot = data.downloads.value
        val now = data.now()
        return UpdatesUiState(
            storyCount = snapshot.library.size,
            overview = data.plannedOverview(snapshot.library) ?: data.overview(snapshot.library, now),
            plannedAt = now,
            filter = UpdatesFilter.ALL,
            syncing = data.syncing.value,
            syncs = data.syncs.value,
            jobsByStory = snapshot.queue.groupBy { it.storyId },
        )
    }

    private companion object {
        const val REFRESH_COALESCE_MS = 400L
    }
}
