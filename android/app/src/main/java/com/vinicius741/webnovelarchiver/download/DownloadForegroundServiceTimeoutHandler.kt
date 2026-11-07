package com.vinicius741.webnovelarchiver.download

import com.vinicius741.webnovelarchiver.domain.download.DownloadJobTransitions
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob

/** Testable orchestration for Android's short foreground-service timeout grace period. */
internal object DownloadForegroundServiceTimeoutHandler {
    /** Returns only interrupted work to the runnable state after a foreground timeout. */
    fun recoverQueue(jobs: List<DownloadJob>): MutableList<DownloadJob> =
        jobs
            .map { it.copy(chapter = it.chapter.copy()) }
            .onEach(DownloadJobTransitions::recoverInterrupted)
            .toMutableList()

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
