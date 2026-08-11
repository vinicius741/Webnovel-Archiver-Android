package com.vinicius741.webnovelarchiver.sync

import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.domain.metrics.MetricSnapshotPlanning
import com.vinicius741.webnovelarchiver.domain.model.DownloadStatus
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.source.PatreonStatsFetcher
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.source.SourceUrlKind
import com.vinicius741.webnovelarchiver.source.network.NetworkClient

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
            provider.loadStory(
                url = normalizedUrl,
                preferLatestChapters = existing != null && mode != StorySyncMode.Full,
                network = network,
                progress = status,
            )
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
            if (latestIncoming != null && latestMerge == null) {
                status("Latest chapters did not overlap; running full sync...")
                loaded.loadFullChapterList(status)
            } else if (latestIncoming == null) {
                loaded.chapters
            } else {
                latestIncoming
            }
        if (incoming.isEmpty()) error("Source returned no chapters")
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
}
