package com.vinicius741.webnovelarchiver.operations

import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.sync.StorySyncEngine
import com.vinicius741.webnovelarchiver.sync.StorySyncMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ManualStorySyncResult(
    val story: Story,
    val downloads: SyncDownloadPlan,
)

/** Fetches and plans on I/O. Progress callbacks may run off the main thread. */
class ManualStorySync internal constructor(
    private val lookup: (String?, String) -> Story?,
    /** True for a source whose chapters are local files, which are all read after every sync. */
    private val readsEveryChapter: (String?, String) -> Boolean,
    private val fetch: suspend (String, String?, StorySyncMode, (String) -> Unit) -> Story,
) {
    constructor(
        lookup: (String?, String) -> Story?,
        fetch: suspend (String, String?, StorySyncMode, (String) -> Unit) -> Story,
    ) : this(lookup, { _, _ -> false }, fetch)

    constructor(repository: AppRepository, engine: StorySyncEngine, refreshPatreonStats: Boolean = true) : this(
        lookup = { sourceId, url ->
            SourceRegistry.getProvider(sourceId, url)?.let { provider ->
                runCatching { repository.story(provider.getStoryId(url)) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrNull()
            }
        },
        readsEveryChapter = { sourceId, url -> SourceRegistry.getProvider(sourceId, url)?.descriptor?.isLocal == true },
        fetch = { url, tabId, mode, progress -> engine.fetchOrSync(url, tabId, mode, refreshPatreonStats, progress) },
    )

    suspend fun run(
        url: String,
        tabId: String?,
        mode: StorySyncMode,
        sourceId: String? = null,
        progress: (String) -> Unit,
    ): ManualStorySyncResult =
        withContext(Dispatchers.IO) {
            val before = lookup(sourceId, url)
            val synced = fetch(url, tabId, mode, progress)
            ManualStorySyncResult(synced, SyncDownloadPlanning.plan(before, synced, readsEveryChapter = readsEveryChapter(sourceId, url)))
        }
}
