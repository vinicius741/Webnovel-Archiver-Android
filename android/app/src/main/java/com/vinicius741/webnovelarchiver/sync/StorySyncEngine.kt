package com.vinicius741.webnovelarchiver.sync

import com.vinicius741.webnovelarchiver.cleanup.CleanupEngine
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.domain.metrics.MetricSnapshotPlanning
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.DownloadStatus
import com.vinicius741.webnovelarchiver.domain.model.SourceFailureKind
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.PatreonCopyPlanning
import com.vinicius741.webnovelarchiver.source.PatreonApi
import com.vinicius741.webnovelarchiver.source.PatreonSession
import com.vinicius741.webnovelarchiver.source.PatreonStatsFetcher
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.source.SourceUrlKind
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import com.vinicius741.webnovelarchiver.source.network.NetworkParseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    private val patreonChapters: PatreonChapterSync = defaultPatreonChapterSync(repository, network),
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
        // R05: remember which library and which story this sync belongs to. A clear/restore or a
        // delete during the network window must reject the commit instead of recreating state.
        val startedGeneration = repository.libraryGeneration()
        val requireExisting = existing != null
        // Patreon early-access copies are not source chapters: the source merge must never see
        // them (they would read as removed and trigger an archive snapshot). They are re-appended
        // after the Patreon step below.
        val existingPublic = existing?.chapters.orEmpty().filterNot { it.isPatreonCopy }
        val existingPatreon = existing?.chapters.orEmpty().filter { it.isPatreonCopy }
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
                    existingPublic,
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
                    existingPublic,
                    incoming,
                    provider,
                    existing?.lastReadChapterId,
                )
        val patreonResult =
            existing?.patreonEarlyAccess?.let { link ->
                // JSON parsing and cleanup of a page of chapter bodies must stay off the main thread.
                withContext(Dispatchers.Default) { patreonChapters.sync(storyId, link, merge.chapters, existingPatreon, status) }
            }
        val reconciled =
            PatreonCopyPlanning.reconcile(
                merge.chapters + (patreonResult?.chapters ?: existingPatreon),
                merge.lastReadChapterId ?: existing?.lastReadChapterId?.takeIf { id -> existingPatreon.any { it.id == id } },
            )
        val chapters = reconciled.chapters
        val pendingNewChapterIds =
            if (existing == null) {
                null
            } else {
                StorySyncPlanning.buildPendingNewChapterIds(existing.pendingNewChapterIds, merge.newChapterIds, chapters)
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
                // Same for the locally generated cover: the source's coverUrl stays as fallback.
                aiCoverPath = existing?.aiCoverPath,
                showAiCover = existing?.showAiCover ?: false,
                // The AI context-chapter selection is local-only too. Carry it forward so a sync
                // never resets the user's pick; indices gone stale after the chapter merge are
                // dropped at read time by AiDescriptionPlanning.resolveContextChapters.
                aiContextChapterIndices = existing?.aiContextChapterIndices,
                aiCoverContextChapterIndices = existing?.aiCoverContextChapterIndices,
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
                chapters = chapters.toMutableList(),
                tags = metadata.tags,
                score = metadata.score,
                sourceMetadata = metadata.sourceMetadata,
                lastReadChapterId = reconciled.lastReadChapterId,
                epubPath = existing?.epubPath,
                epubPaths = existing?.epubPaths,
                epubStale =
                    if (StorySyncPlanning.shouldMarkEpubStale(existing, chapters.size) ||
                        patreonResult?.storedChapterIds?.isNotEmpty() == true
                    ) {
                        true
                    } else {
                        existing?.epubStale
                    },
                epubConfig = StorySyncPlanning.updateEpubConfigForSync(existing, chapters.size),
                pendingNewChapterIds = pendingNewChapterIds,
                tabId = tabId ?: existing?.tabId,
                lastUpdated = syncedAt,
                lastChapterSyncAt = syncedAt,
                patreonUrl = patreonUrl,
                patreonStats = refreshedPatreonStats ?: existing?.patreonStats?.takeIf { existing.patreonUrl == patreonUrl },
                patreonEarlyAccess = patreonResult?.link ?: existing?.patreonEarlyAccess,
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
            try {
                repository.commitSyncedStory(
                    story = story,
                    archiveSource = existing?.takeIf { merge.removedChapters.isNotEmpty() },
                    metricSnapshot =
                        MetricSnapshotPlanning.fromStory(
                            story,
                            patreonRefreshed = refreshedPatreonStats != null,
                            capturedAt = syncedAt,
                        ),
                    startedGeneration = startedGeneration,
                    requireExisting = requireExisting,
                ) { current ->
                    // A public chapter that finished downloading during the network window may
                    // now replace a Patreon copy, so reconcile again on the folded result. Copies the
                    // user unlinked during the window are dropped by the fold; their files go below.
                    val fresh = patreonResult?.storedChapterIds.orEmpty().toSet()
                    PatreonCopyPlanning.reconcileStory(StorySyncMergePlanning.foldConcurrentChanges(story, current, provider, fresh)).first
                }
            } catch (error: Throwable) {
                // The commit was rejected: Patreon files stored by this sync are referenced by nothing.
                deleteUnreferencedPatreonFiles(storyId, story.chapters.filter { it.id in patreonResult?.storedChapterIds.orEmpty() }, null)
                throw error
            }
        deleteUnreferencedPatreonFiles(storyId, existingPatreon + story.chapters.filter { it.isPatreonCopy }, persisted)
        return persisted
    }

    /** Removes files of Patreon copies that the committed story (or nothing, when null) no longer references. */
    private suspend fun deleteUnreferencedPatreonFiles(
        storyId: String,
        candidates: List<Chapter>,
        committed: Story?,
    ) {
        val referenced =
            committed
                ?.chapters
                .orEmpty()
                .mapNotNull { it.filePath }
                .toSet()
        val orphaned = candidates.mapNotNull { it.filePath }.distinct().filterNot { it in referenced }
        if (orphaned.isEmpty()) return
        withContext(Dispatchers.IO) {
            orphaned.forEach { path -> runCatching { repository.storage.deleteChapterFile(storyId, path) } }
        }
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

private fun defaultPatreonChapterSync(
    repository: AppRepository,
    network: NetworkClient,
): PatreonChapterSync =
    PatreonChapterSync(
        api = PatreonApi(network),
        sessionPresent = PatreonSession::isPresent,
        cleanup = { html ->
            val storage = repository.storage
            CleanupEngine.shared.applyDownload(html, storage.sentencesDoc.get(), storage.regexRulesDoc.get())
        },
        saveChapter = { storyId, index, chapter, html ->
            withContext(Dispatchers.IO) { repository.storage.saveChapter(storyId, index, chapter, html) }
        },
    )
