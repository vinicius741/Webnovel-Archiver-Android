package com.vinicius741.webnovelarchiver.download

import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus

/** Pure durable-state recovery applied when Android times out the data-sync foreground service. */
internal object DownloadForegroundServiceTimeoutPlanning {
    /**
     * Returns only interrupted work to the runnable state. Jobs that had not started remain pending,
     * while user-paused and terminal jobs retain their intent for the next explicit service start.
     */
    fun recoverQueue(jobs: List<DownloadJob>): MutableList<DownloadJob> =
        jobs
            .map { it.copy(chapter = it.chapter.copy()) }
            .onEach { job ->
                if (job.status == DownloadJobStatus.Downloading.wire) {
                    job.status = DownloadJobStatus.Pending.wire
                    job.nextRetryAt = null
                }
            }.toMutableList()
}
