package com.vinicius741.webnovelarchiver.download

import com.vinicius741.webnovelarchiver.data.storage.AppStorage
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob

/**
 * The queue persistence the download process loop and its failure handling use. Production wraps
 * [AppStorage]; tests supply an in-memory store so the scheduling loop runs on the JVM.
 */
internal interface DownloadQueueStore {
    /** The monitor every queue and story writer shares; read-modify-write happens under it. */
    val lock: Any

    fun maxParallelSources(): Int?

    fun queue(): MutableList<DownloadJob>

    fun saveQueue(jobs: List<DownloadJob>)

    fun mutateQueueInPlace(transform: (MutableList<DownloadJob>) -> List<DownloadJob>): List<DownloadJob>
}

internal fun AppStorage.downloadQueueStore(): DownloadQueueStore {
    val storage = this
    return object : DownloadQueueStore {
        override val lock: Any = storage

        override fun maxParallelSources(): Int? = storage.settingsDoc.get().maxParallelSources

        override fun queue(): MutableList<DownloadJob> = storage.getQueue()

        override fun saveQueue(jobs: List<DownloadJob>) = storage.saveQueue(jobs)

        override fun mutateQueueInPlace(transform: (MutableList<DownloadJob>) -> List<DownloadJob>): List<DownloadJob> =
            storage.mutateQueueInPlace(transform)
    }
}
