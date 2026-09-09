package com.vinicius741.webnovelarchiver.feature.story

import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.sync.StorySyncEngine
import com.vinicius741.webnovelarchiver.sync.StorySyncMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ManualStorySyncResult(
    val story: Story,
    val downloads: SyncDownloadPlan,
)

/** Fetches and plans on I/O. Progress callbacks may run off the main thread. */
internal class ManualStorySync(
    private val lookup: (String?, String) -> Story?,
    private val fetch: suspend (String, String?, StorySyncMode, (String) -> Unit) -> Story,
) {
    constructor(repository: AppRepository, engine: StorySyncEngine) : this(
        lookup = { sourceId, url ->
            SourceRegistry.getProvider(sourceId, url)?.let { provider ->
                runCatching { repository.story(provider.getStoryId(url)) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrNull()
            }
        },
        fetch = { url, tabId, mode, progress -> engine.fetchOrSync(url, tabId, mode, status = progress) },
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
            ManualStorySyncResult(synced, SyncDownloadPlanning.plan(before, synced))
        }
}
