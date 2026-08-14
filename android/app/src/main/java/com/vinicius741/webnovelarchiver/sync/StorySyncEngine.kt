package com.vinicius741.webnovelarchiver.sync

import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.domain.metrics.MetricSnapshotPlanning
import com.vinicius741.webnovelarchiver.domain.model.DownloadStatus
import com.vinicius741.webnovelarchiver.domain.model.SourceFailureKind
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.source.PatreonStatsFetcher
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.source.SourceUrlKind
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import com.vinicius741.webnovelarchiver.source.network.NetworkParseException
import kotlinx.coroutines.CancellationException

enum class StorySyncMode {
    Default,
    Full,
}

/**
 * Fetches or syncs a story from its source. Lives in
 * the `core` package so existing imports (`core.StorySyncEngine`) keep resolving.
 */
class StorySyncEngine(
    private val repository: AppRepository,
    private val network: NetworkClient,
) {
    @Suppress("ThrowsCount", "TooGenericExceptionCaught") // Source-boundary failures become typed persisted outcomes.
    suspend fun fetchOrSync(
        url: String,
        tabId: String? = null,
        mode: StorySyncMode = StorySyncMode.Default,
        refreshPatreonStats: Boolean = true,
        status: (String) -> Unit = {},
    ): Story {
        val submittedUrl = url.trim()
        val match =
            SourceRegistry.resolve(submittedUrl, SourceUrlKind.STORY)
                ?: error("Unsupported source URL")
        val provider = match.provider
        val normalizedUrl = match.normalizedUrl
        val storyId = provider.getStoryId(normalizedUrl)
        val existing = repository.story(storyId)
        status("Fetching from ${provider.name}...")
        val loaded =
            try {
                provider.loadStory(
                    url = normalizedUrl,
                    preferLatestChapters = existing != null && mode != StorySyncMode.Full,
                    network = network,
                    progress = status,
                )
            } catch (error: Throwable) {
                throw recordFailure(existing, provider.name, error)
            }
        val metadata = loaded.metadata
        val latestIncoming =
            loaded.chapters.takeIf { loaded.chaptersAreLatestOnly }
        val latestMerge =
            latestIncoming?.let { incoming ->
                StorySyncPlanning.mergeLatestChapters(
                    existing?.chapters.orEmpty(),
                    incoming,
                    provider,
                    existing?.lastReadChapterId,
                )
            }
        val incoming =
            try {
                if (latestIncoming != null && latestMerge == null) {
                    status("Latest chapters did not overlap; running full sync...")
                    loaded.loadFullChapterList(status)
                } else if (latestIncoming == null) {
                    loaded.chapters
                } else {
                    latestIncoming
                }
            } catch (error: Throwable) {
                throw recordFailure(existing, provider.name, error)
            }
        if (incoming.isEmpty()) {
            throw recordFailure(existing, provider.name, NetworkParseException("Source returned no chapters"))
        }
        val syncedAt = System.currentTimeMillis()
        val sourcePublicationStatus =
            StorySyncPlanning.sourceDeclaredStatus(metadata.publicationStatus, existing?.publicationStatus)
        val patreonUrl = metadata.patreonUrl
        val refreshedPatreonStats =
            if (refreshPatreonStats) {
                patreonUrl?.let { creatorUrl ->
                    status("Refreshing Patreon statistics...")
                    runCatching { PatreonStatsFetcher(network).fetch(creatorUrl) }.getOrNull()
                }
            } else {
                null
            }
        val merge =
            latestMerge
                ?: StorySyncPlanning.mergeChapters(
                    existing?.chapters ?: emptyList(),
                    incoming,
                    provider,
                    existing?.lastReadChapterId,
                )
        val pendingNewChapterIds =
            if (existing == null) {
                null
            } else {
                StorySyncPlanning.buildPendingNewChapterIds(existing.pendingNewChapterIds, merge.newChapterIds, merge.chapters)
            }
        val story =
            Story(
                id = storyId,
                title = metadata.title,
                author = metadata.author,
                coverUrl = metadata.coverUrl ?: existing?.coverUrl,
                description = metadata.description,
                // The AI synopsis is local-only state: the source knows nothing about it, so a sync
                // must carry it forward rather than let the fresh Story reset it.
                aiDescription = existing?.aiDescription,
                showAiDescription = existing?.showAiDescription ?: false,
                sourceUrl = metadata.canonicalUrl ?: normalizedUrl,
                sourceId = provider.id,
                status =
                    if (existing ==
                        null
                    ) {
                        DownloadStatus.idle
                    } else if (merge.newChapterIds.isNotEmpty()) {
                        DownloadStatus.partial
                    } else {
                        existing.status
                    },
                chapters = merge.chapters.toMutableList(),
                tags = metadata.tags,
                score = metadata.score,
                sourceMetadata = metadata.sourceMetadata,
                lastReadChapterId = merge.lastReadChapterId,
                epubPath = existing?.epubPath,
                epubPaths = existing?.epubPaths,
                epubStale = if (StorySyncPlanning.shouldMarkEpubStale(existing, merge.chapters.size)) true else existing?.epubStale,
                epubConfig = StorySyncPlanning.updateEpubConfigForSync(existing, merge.chapters.size),
                pendingNewChapterIds = pendingNewChapterIds,
                tabId = tabId ?: existing?.tabId,
                lastUpdated = syncedAt,
                lastChapterSyncAt = syncedAt,
                patreonUrl = patreonUrl,
                patreonStats = refreshedPatreonStats ?: existing?.patreonStats?.takeIf { existing.patreonUrl == patreonUrl },
                publicationStatus =
                    StorySyncPlanning.publicationStatusAfterSync(
                        sourcePublicationStatus,
                        StorySyncPlanning.latestPublishedAt(incoming),
                        syncedAt,
                    ),
                sourceSyncState = SourceSyncFailurePlanning.afterSuccess(syncedAt),
            )
        // The repository owns the final read/merge/write/publish transaction. This keeps the
        // concurrent-download/bookmark fence, archive write, metric snapshot, and cached state
        // publication on one lock and removes the former second mutation path in the sync engine.
        val persisted =
            repository.commitSyncedStory(
                story = story,
                archiveSource = existing?.takeIf { merge.removedChapters.isNotEmpty() },
                metricSnapshot =
                    MetricSnapshotPlanning.fromStory(
                        story,
                        patreonRefreshed = refreshedPatreonStats != null,
                        capturedAt = syncedAt,
                    ),
            ) { current -> StorySyncMergePlanning.foldConcurrentChanges(story, current, provider) }
        return persisted
    }

    private suspend fun recordFailure(
        existing: Story?,
        sourceName: String,
        error: Throwable,
    ): Throwable {
        if (error is CancellationException) return error
        val failure = SourceSyncFailurePlanning.classify(error)
        if (existing != null) {
            val checkedAt = System.currentTimeMillis()
            repository.updateStory(existing.id) { latest ->
                latest?.copy(
                    sourceSyncState = SourceSyncFailurePlanning.afterFailure(latest.sourceSyncState, failure, checkedAt),
                )
            }
        }
        return if (failure.kind == SourceFailureKind.not_found && existing != null) {
            StorySourceUnavailableException(
                sourceName = sourceName,
                statusCode = requireNotNull(failure.httpStatus),
                cause = error,
            )
        } else {
            error
        }
    }
}
