package com.vinicius741.webnovelarchiver.download

import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus

/** Testable orchestration for Android's short foreground-service timeout grace period. */
internal object DownloadForegroundServiceTimeoutHandler {
    /** Returns only interrupted work to the runnable state after a foreground timeout. */
    fun recoverQueue(jobs: List<DownloadJob>): MutableList<DownloadJob> =
        jobs
            .map { it.copy(chapter = it.chapter.copy()) }
            .onEach { job ->
                if (job.status == DownloadJobStatus.Downloading.wire) {
                    job.status = DownloadJobStatus.Pending.wire
                    job.nextRetryAt = null
                }
            }.toMutableList()

    /** Stops the service even when queue recovery or foreground teardown fails. */
    @Suppress("TooGenericExceptionCaught") // Lifecycle safety boundary: stopService must always run.
    fun handle(
        recoverQueue: () -> Unit,
        stopForeground: () -> Unit,
        stopService: () -> Unit,
        onRecoveryFailure: (Exception) -> Unit,
    ) {
        try {
            try {
                recoverQueue()
            } catch (error: Exception) {
                onRecoveryFailure(error)
            }
        } finally {
            try {
                stopForeground()
            } finally {
                stopService()
            }
        }
    }
}
