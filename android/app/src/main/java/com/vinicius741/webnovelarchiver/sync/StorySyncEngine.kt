package com.vinicius741.webnovelarchiver.sync

import com.vinicius741.webnovelarchiver.data.storage.AppStorage
import com.vinicius741.webnovelarchiver.domain.archive.ArchiveSnapshotPlanning
import com.vinicius741.webnovelarchiver.domain.metrics.MetricSnapshotPlanning
import com.vinicius741.webnovelarchiver.domain.model.DownloadStatus
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.source.PatreonStatsFetcher
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.source.SourceUrlValidation
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import com.vinicius741.webnovelarchiver.ui.size
import timber.log.Timber

enum class StorySyncMode {
    Default,
    Full,
}

/**
 * Fetches or syncs a story from its source (Maintainability M1: split out of Engines.kt). Lives in
 * the `core` package so existing imports (`core.StorySyncEngine`) keep resolving.
 */
class StorySyncEngine(
    private val storage: AppStorage,
    private val network: NetworkClient,
) {
    suspend fun fetchOrSync(
        url: String,
        tabId: String? = null,
        mode: StorySyncMode = StorySyncMode.Default,
        refreshPatreonStats: Boolean = true,
        status: (String) -> Unit = {},
    ): Story {
        val provider = SourceRegistry.getProvider(url) ?: error("Unsupported source URL")
        if (!SourceUrlValidation.isImportableStoryUrl(url)) error("Unsupported source URL")
        val storyId = provider.getStoryId(url)
        val existing = storage.getStory(storyId)
        status("Fetching from ${provider.name}...")
        val html = network.fetch(url)
        val metadata = provider.parseMetadata(html)
        status("Parsing chapters...")
        val latestIncoming =
            if (existing != null && mode != StorySyncMode.Full && provider.supportsLatestChapterSync) {
                provider.getLatestChapterList(html, url, network, status)
            } else {
                null
            }
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
                provider.getChapterList(html, url, network, status)
            } else if (latestIncoming == null) {
                provider.getChapterList(html, url, network, status)
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
        if (existing != null && merge.removedChapters.isNotEmpty()) createArchive(existing)
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
                sourceUrl = metadata.canonicalUrl ?: url,
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
        // Audit gap 10 / Rec 3: re-read the on-disk story under the shared storage monitor and fold
        // any concurrent changes onto the synced story before writing. The network window above can
        // span seconds; a download that completed for a chapter in that window, or a bookmark the user
        // set, would otherwise be clobbered by a wholesale addOrUpdateStory. The fold preserves the
        // synced metadata/chapter list while re-applying per-chapter download state + reading position.
        // (Same monitor the download engine and repository use, so this write cannot interleave with
        // DownloadEngine.processJob's own read-modify-write of the same story.)
        val persisted =
            synchronized(storage) {
                val current = storage.getStory(storyId)
                val merged = StorySyncMergePlanning.foldConcurrentChanges(story, current, provider)
                storage.addOrUpdateStory(merged)
                // Record a trend snapshot inside the same storage transaction so a concurrent sync
                // can't interleave two history writes. Patreon fields are only filled when this sync
                // actually fetched fresh Patreon stats (`refreshedPatreonStats != null`); batch
                // "Follow Updates" syncs pass `refreshPatreonStats = false` and so record Patreon as
                // null rather than re-stamping the carried-forward value as if it were measured now.
                //
                // Trend capture is best-effort: a fenced/corrupt metrics file (e.g. an IoFailure
                // health issue, or a forward-compat UnsupportedSchema from a future app version) must
                // not fail the story sync — the story itself is already persisted above, and failing
                // here would skip the post-sync auto-download and surface a spurious "Sync failed".
                runCatching {
                    storage.appendMetricSnapshot(
                        merged.id,
                        MetricSnapshotPlanning.fromStory(merged, patreonRefreshed = refreshedPatreonStats != null, capturedAt = syncedAt),
                    )
                }.onFailure { Timber.w(it, "Failed to record metric snapshot for %s", merged.id) }
                merged
            }
        return persisted
    }

    private fun createArchive(source: Story) {
        val now = System.currentTimeMillis()
        val archive =
            ArchiveSnapshotPlanning.buildArchiveSnapshot(source, now) { archiveId, index, chapter ->
                storage.copyChapterToStory(archiveId, index, chapter)
            }
        storage.addOrUpdateStory(archive)
    }
}
