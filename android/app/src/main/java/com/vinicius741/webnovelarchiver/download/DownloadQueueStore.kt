package com.vinicius741.webnovelarchiver.download

import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob

/**
 * The queue persistence the download process loop and its failure handling use. Production wraps
 * the repository's working queue; tests supply an in-memory store so the scheduling loop runs on
 * the JVM.
 */
internal interface DownloadQueueStore {
    /** The monitor every queue and story writer shares; read-modify-write happens under it. */
    val lock: Any

    fun maxParallelSources(): Int?

    /** A copy the caller may change in place; call while holding [lock]. */
    fun queue(): MutableList<DownloadJob>

    /** Call while holding [lock]. */
    fun saveQueue(jobs: List<DownloadJob>)

    fun mutateQueueInPlace(transform: (MutableList<DownloadJob>) -> List<DownloadJob>): List<DownloadJob>
}

/**
 * Write-through to the repository's working queue: reads never parse `download_queue.json`, and a
 * save updates the working queue under the same monitor hold, so a repository transaction can never
 * read a queue older than the file. Callers publish with [AppRepository.publishDownloadState].
 */
internal fun AppRepository.downloadQueueStore(): DownloadQueueStore {
    val repository = this
    return object : DownloadQueueStore {
        override val lock: Any get() = repository.transactionLock

        override fun maxParallelSources(): Int? = repository.settings.get().maxParallelSources

        override fun queue(): MutableList<DownloadJob> = repository.queueForWriteLocked()

        override fun saveQueue(jobs: List<DownloadJob>) = repository.saveQueueLocked(jobs)

        override fun mutateQueueInPlace(transform: (MutableList<DownloadJob>) -> List<DownloadJob>): List<DownloadJob> =
            synchronized(lock) { transform(queueForWriteLocked()).also(::saveQueueLocked) }
    }
}
