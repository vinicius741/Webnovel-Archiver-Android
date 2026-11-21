package com.vinicius741.webnovelarchiver.feature.details

import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.data.repository.DownloadUiSnapshot
import com.vinicius741.webnovelarchiver.domain.model.ChapterFilterSettings
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.download.DownloadDetailsPlanning
import com.vinicius741.webnovelarchiver.download.DownloadPacingSnapshot
import com.vinicius741.webnovelarchiver.download.DownloadPacingUiPlanning
import com.vinicius741.webnovelarchiver.download.DownloadPacingUiStatus
import com.vinicius741.webnovelarchiver.navigation.ScreenModel
import com.vinicius741.webnovelarchiver.operations.StoryOperationState
import com.vinicius741.webnovelarchiver.operations.StoryOperations
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Everything the story page's tabs draw from the live app state: the story, its download jobs and
 * pacing, and the operations running on it. [Story] is still a mutable model class; treat it as
 * read-only here (it is the model's own copy, replaced whenever the story changes).
 */
data class StoryPageUiState(
    val story: Story?,
    /** The queue's jobs for this story. */
    val jobs: List<DownloadJob> = emptyList(),
    val download: DownloadDetailsPlanning.StoryDownloadSummary = DownloadDetailsPlanning.summarizeStoryDownload(emptyList()),
    val chapterStatuses: Map<String, DownloadJobStatus> = emptyMap(),
    /** Chapters held only by the request delay, not fetching yet. */
    val waitingChapterIds: Set<String> = emptySet(),
    val pacing: DownloadPacingUiStatus? = null,
    /** This story's sync, cleanup or EPUB, if one runs. */
    val content: StoryOperationState? = null,
    /** This story's AI cover or description job, if one runs. */
    val ai: StoryOperationState? = null,
    /** Library and queue versions of the download snapshot this state was read from. */
    val libraryVersion: Long = 0L,
    val queueVersion: Long = 0L,
    /** Bumped by [StoryPageScreenModel.refreshPage] when something outside this state changed. */
    val revision: Int = 0,
) {
    /** A content operation runs on this story, so its content actions are off. */
    val isBusy: Boolean get() = content != null

    /** The library or the queue changed since [other]: the download surfaces need a redraw. */
    fun downloadChangedSince(other: StoryPageUiState): Boolean =
        libraryVersion != other.libraryVersion || queueVersion != other.queueVersion || story !== other.story
}

/** What the story page reads and writes in the repository. */
interface StoryPageData {
    val downloads: StateFlow<DownloadUiSnapshot>

    /** A copy of the story the caller may hold on to. */
    fun story(id: String): Story?

    suspend fun toggleBookmark(
        storyId: String,
        chapterId: String,
    )

    suspend fun saveChapterFilter(mode: String)
}

/** [StoryPageData] backed by the app repository. */
internal class RepositoryStoryPageData(
    private val repository: AppRepository,
) : StoryPageData {
    override val downloads: StateFlow<DownloadUiSnapshot> get() = repository.downloadState

    override fun story(id: String): Story? = repository.story(id)

    override suspend fun toggleBookmark(
        storyId: String,
        chapterId: String,
    ) {
        repository.toggleBookmark(storyId, chapterId)
    }

    override suspend fun saveChapterFilter(mode: String) = repository.chapterFilterSettings.save(ChapterFilterSettings(mode))
}

/**
 * The story page's state, shared by all of one story's tabs (Overview, Chapters, Trends, AI): it
 * lives under the story's key in the screen-model store, so tab switches and folds keep it.
 *
 * Its sources are observed only while a renderer collects [state]. A renderer that starts from
 * [refresh] therefore never draws a stale state, and nothing ticks while the page is off screen.
 */
class StoryPageScreenModel(
    val storyId: String,
    private val data: StoryPageData,
    private val pacingSnapshots: StateFlow<Map<String, DownloadPacingSnapshot>>,
    /** Wakes the model to recount pacing countdowns; emits while collected. */
    private val ticker: Flow<Long>,
    private val nowMillis: () -> Long,
    private val operations: StoryOperations,
    private val providerName: (sourceId: String?, url: String) -> String? = { sourceId, url ->
        SourceRegistry.getProvider(sourceId, url)?.name
    },
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ScreenModel(scope) {
    private var revision = 0
    private var readSnapshot: DownloadUiSnapshot? = null
    private var publishedStory: Story? = null
    private var download = DownloadPart(story = null)
    private val mutableState = MutableStateFlow(compute())

    val state: StateFlow<StoryPageUiState> = mutableState.asStateFlow()

    init {
        this.scope.launch {
            mutableState.subscriptionCount
                .map { it > 0 }
                .distinctUntilChanged()
                .collectLatest { observed ->
                    if (observed) {
                        merge(data.downloads, pacingSnapshots, operations.content, operations.ai, ticker).collect { refresh() }
                    }
                }
        }
    }

    /** Recomputes the state from its sources now and returns it. */
    fun refresh(): StoryPageUiState = compute().also { mutableState.value = it }

    /** Asks the page to redraw its hero for a change this state does not carry (an AI draft). */
    fun refreshPage() {
        revision += 1
        refresh()
    }

    fun toggleBookmark(chapterId: String) {
        scope.launch { data.toggleBookmark(storyId, chapterId) }
    }

    fun saveChapterFilter(mode: String) {
        scope.launch { data.saveChapterFilter(mode) }
    }

    private fun compute(): StoryPageUiState {
        val snapshot = data.downloads.value
        val part = downloadPart(snapshot)
        val now = nowMillis()
        val pacing = pacingSnapshots.value.values
        return StoryPageUiState(
            story = part.story,
            jobs = part.jobs,
            download = part.summary,
            chapterStatuses = part.statuses,
            waitingChapterIds = waitingChapterIds(part.jobs, pacing, now),
            pacing =
                part.story?.let { story ->
                    DownloadPacingUiPlanning.storyStatus(
                        storyId = storyId,
                        providerName = providerName(part.jobs.firstOrNull()?.sourceId, story.sourceUrl),
                        storyJobs = part.jobs,
                        snapshots = pacing,
                        nowMillis = now,
                        allJobs = snapshot.queue,
                    )
                },
            content = operations.contentFor(storyId),
            ai = operations.aiFor(storyId),
            libraryVersion = snapshot.libraryVersion,
            queueVersion = snapshot.queueVersion,
            revision = revision,
        )
    }

    /** The download-derived part, recomputed only when the snapshot changes (not per tick). */
    private fun downloadPart(snapshot: DownloadUiSnapshot): DownloadPart {
        if (snapshot === readSnapshot) return download
        readSnapshot = snapshot
        // The snapshot's stories are shared; copy this one only when it was replaced.
        val published = snapshot.library.firstOrNull { it.id == storyId }
        val story = if (published === publishedStory && download.story != null) download.story else data.story(storyId)
        publishedStory = published
        val jobs = snapshot.queue.filter { it.storyId == storyId }
        download =
            DownloadPart(
                story = story,
                jobs = jobs,
                summary = DownloadDetailsPlanning.summarizeStoryDownload(jobs),
                statuses = DownloadDetailsPlanning.chapterJobStatuses(jobs),
            )
        return download
    }

    private data class DownloadPart(
        val story: Story?,
        val jobs: List<DownloadJob> = emptyList(),
        val summary: DownloadDetailsPlanning.StoryDownloadSummary = DownloadDetailsPlanning.summarizeStoryDownload(emptyList()),
        val statuses: Map<String, DownloadJobStatus> = emptyMap(),
    )
}

/** Chapters held only by the configured request delay, not yet fetching their content. */
internal fun waitingChapterIds(
    jobs: List<DownloadJob>,
    snapshots: Collection<DownloadPacingSnapshot>,
    nowMillis: Long,
): Set<String> {
    if (jobs.isEmpty() || snapshots.isEmpty()) return emptySet()
    val waitingJobIds = DownloadPacingUiPlanning.waitingJobs(snapshots, jobs, nowMillis).keys
    return jobs
        .asSequence()
        .filter { it.id in waitingJobIds }
        .map { it.chapter.id }
        .toSet()
}

/** Whether the live download banner should show. */
internal fun shouldShowDetailsBanner(summary: DownloadDetailsPlanning.StoryDownloadSummary): Boolean =
    summary.total > 0 &&
        (summary.isActive || summary.isPaused || (summary.isFinished && (summary.failed > 0 || summary.cancelled > 0)))
