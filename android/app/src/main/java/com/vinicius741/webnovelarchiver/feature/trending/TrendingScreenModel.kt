package com.vinicius741.webnovelarchiver.feature.trending

import com.vinicius741.webnovelarchiver.data.repository.DownloadUiSnapshot
import com.vinicius741.webnovelarchiver.domain.metrics.PatreonCreatorPlanning
import com.vinicius741.webnovelarchiver.domain.metrics.TrendingPlanning
import com.vinicius741.webnovelarchiver.domain.metrics.TrendingPlanning.Entry
import com.vinicius741.webnovelarchiver.domain.metrics.TrendingPlanning.Signal
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.StoryMetricHistory
import com.vinicius741.webnovelarchiver.navigation.ScreenModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Trending filters: every rising novel, or the ones rising on one signal. */
internal enum class TrendingFilter(
    val label: String,
    val signal: Signal?,
) {
    ALL("All", null),
    PATREON("Patreon", Signal.PATREON),
    READERS("Readers", Signal.AUDIENCE),
    VIEWS("Views", Signal.VIEWS),
    RATING("Rating", Signal.RATING),
}

/** What Trending reads: the published library and each novel's metric history. */
internal interface TrendingData {
    /** The repository's published library (never deep-copied). */
    val downloads: StateFlow<DownloadUiSnapshot>

    suspend fun history(storyId: String): StoryMetricHistory

    fun now(): Long
}

internal data class TrendingUiState(
    /** True until the first plan lands. */
    val loading: Boolean,
    val result: TrendingPlanning.Result,
    val filter: TrendingFilter,
    val storyCount: Int,
) {
    /**
     * The entries the filter shows: all of them hottest first, or those rising on the filter's
     * signal (as lead or support), strongest on that signal first.
     */
    val visible: List<Entry>
        get() {
            val signal = filter.signal ?: return result.entries
            return result.entries
                .mapNotNull { entry -> entry.movement(signal)?.let { entry to it.strength } }
                .sortedByDescending { it.second }
                .map { it.first }
        }

    /** How many entries the chip for [filter] would show. */
    fun count(filter: TrendingFilter): Int {
        val signal = filter.signal ?: return result.entries.size
        return result.entries.count { it.movement(signal) != null }
    }
}

internal fun Entry.movement(signal: Signal): TrendingPlanning.Movement? =
    if (lead.signal == signal) lead else supporting.firstOrNull { it.signal == signal }

/**
 * The Trending screen (`AppRoute.Trending`): novels whose statistics are rising now
 * ([TrendingPlanning]). It reads every novel's metric history off the main thread, caching each
 * history until that novel syncs again, and replans when the library changes while a renderer
 * collects [state]. The filter is screen state and resets when Trending leaves the history.
 */
internal class TrendingScreenModel(
    private val data: TrendingData,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val planning: CoroutineDispatcher = Dispatchers.Default,
) : ScreenModel(scope) {
    private val mutableState =
        MutableStateFlow(
            TrendingUiState(
                loading = true,
                result = TrendingPlanning.Result(emptyList(), caughtUpHidden = 0, measured = 0),
                filter = TrendingFilter.ALL,
                storyCount = data.downloads.value.library.size,
            ),
        )
    val state: StateFlow<TrendingUiState> = mutableState.asStateFlow()

    /** Histories by story id, with the sync fingerprint they were read at. */
    private val histories = mutableMapOf<String, Pair<HistoryKey, StoryMetricHistory>>()

    init {
        this.scope.launch {
            mutableState.subscriptionCount
                .map { it > 0 }
                .distinctUntilChanged()
                .collectLatest { observed -> if (observed) follow() }
        }
    }

    fun selectFilter(filter: TrendingFilter) = mutableState.update { it.copy(filter = filter) }

    private suspend fun follow() {
        data.downloads
            .map { it.libraryVersion }
            .distinctUntilChanged()
            .collectLatest {
                // A download publishes per chapter; one plan covers the burst. The first plan runs at once.
                if (!mutableState.value.loading) delay(REFRESH_COALESCE_MS)
                replan()
            }
    }

    private suspend fun replan() {
        val library = data.downloads.value.library
        val now = data.now()
        val loaded = library.associate { story -> story.id to historyFor(story) }
        val result =
            withContext(planning) {
                TrendingPlanning.plan(candidates(library, loaded), now)
            }
        mutableState.update { it.copy(loading = false, result = result, storyCount = library.size) }
    }

    private suspend fun historyFor(story: Story): StoryMetricHistory {
        val key = HistoryKey(story.lastChapterSyncAt, story.patreonStats?.capturedAt, story.lastUpdated)
        histories[story.id]?.let { (cachedKey, history) -> if (cachedKey == key) return history }
        return data.history(story.id).also { histories[story.id] = key to it }
    }

    private data class HistoryKey(
        val lastSyncAt: Long?,
        val patreonAt: Long?,
        val lastUpdated: Long?,
    )

    private companion object {
        const val REFRESH_COALESCE_MS = 1500L
    }
}

/**
 * One planning candidate per novel. Novels linking one Patreon creator get that creator's merged
 * Patreon history, the same series their Trends tab charts.
 */
internal fun candidates(
    library: List<Story>,
    histories: Map<String, StoryMetricHistory>,
): List<TrendingPlanning.Candidate> {
    val creatorOf = library.associate { it.id to PatreonCreatorPlanning.creatorKey(it.effectivePatreonUrl) }
    val merged =
        library
            .filter { creatorOf[it.id] != null }
            .groupBy { creatorOf.getValue(it.id) }
            .filterValues { it.size > 1 }
            .mapValues { (_, group) ->
                PatreonCreatorPlanning.mergedPatreonHistory(group.first().id, group.mapNotNull { histories[it.id] })
            }
    return library.map { story ->
        val history = histories[story.id] ?: StoryMetricHistory(storyId = story.id)
        val creator = creatorOf[story.id]
        TrendingPlanning.Candidate(
            story = story,
            history = history,
            patreonHistory = creator?.let { merged[it] } ?: history,
            creatorKey = creator,
        )
    }
}
