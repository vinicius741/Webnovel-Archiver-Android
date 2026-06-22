package com.vinicius741.webnovelarchiver.sync

import com.vinicius741.webnovelarchiver.data.storage.AppStorage
import com.vinicius741.webnovelarchiver.domain.archive.ArchiveSnapshotPlanning
import com.vinicius741.webnovelarchiver.domain.model.DownloadStatus
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.source.NetworkClient
import com.vinicius741.webnovelarchiver.source.PatreonStatsFetcher
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.source.SourceUrlValidation
import com.vinicius741.webnovelarchiver.ui.size

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
        val incoming = provider.getChapterList(html, url, network, status)
        if (incoming.isEmpty()) error("Source returned no chapters")
        val patreonUrl = metadata.patreonUrl
        val refreshedPatreonStats =
            patreonUrl?.let { creatorUrl ->
                status("Refreshing Patreon statistics...")
                runCatching { PatreonStatsFetcher(network).fetch(creatorUrl) }.getOrNull()
            }
        val merge = StorySyncPlanning.mergeChapters(existing?.chapters ?: emptyList(), incoming, provider, existing?.lastReadChapterId)
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
                lastUpdated = System.currentTimeMillis(),
                patreonUrl = patreonUrl,
                patreonStats = refreshedPatreonStats ?: existing?.patreonStats?.takeIf { existing.patreonUrl == patreonUrl },
            )
        storage.addOrUpdateStory(story)
        return story
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
