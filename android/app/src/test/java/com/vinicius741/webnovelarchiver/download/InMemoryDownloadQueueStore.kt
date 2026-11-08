package com.vinicius741.webnovelarchiver.download

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob

/**
 * A [DownloadQueueStore] that behaves like the JSON-backed queue: every read returns fresh copies
 * and every write replaces the stored rows, so callers cannot share mutable rows with the store.
 */
internal class InMemoryDownloadQueueStore(
    vararg initial: DownloadJob,
    private val maxParallelSources: Int? = 2,
) : DownloadQueueStore {
    override val lock = Any()
    private var rows: List<DownloadJob> = initial.map(::detached)

    override fun maxParallelSources(): Int? = maxParallelSources

    override fun queue(): MutableList<DownloadJob> = synchronized(lock) { rows.mapTo(mutableListOf(), ::detached) }

    override fun saveQueue(jobs: List<DownloadJob>) = synchronized(lock) { rows = jobs.map(::detached) }

    override fun mutateQueueInPlace(transform: (MutableList<DownloadJob>) -> List<DownloadJob>): List<DownloadJob> =
        synchronized(lock) {
            transform(queue()).also(::saveQueue)
        }

    fun job(id: String): DownloadJob = queue().single { it.id == id }

    fun add(job: DownloadJob) = synchronized(lock) { rows = rows + detached(job) }

    private fun detached(job: DownloadJob) = job.copy(chapter = job.chapter.copy())
}

internal fun downloadJob(
    id: String,
    sourceId: String,
    status: String = "pending",
    url: String = "https://example.test/$id",
    retryCount: Int = 0,
    nextRetryAt: Long? = null,
) = DownloadJob(
    id = id,
    storyId = "story-$sourceId",
    sourceId = sourceId,
    chapter = Chapter(id = id, title = id, url = url),
    status = status,
    addedAt = 0L,
    retryCount = retryCount,
    nextRetryAt = nextRetryAt,
)
