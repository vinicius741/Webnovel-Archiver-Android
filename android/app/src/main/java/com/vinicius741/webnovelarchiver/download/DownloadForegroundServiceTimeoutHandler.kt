package com.vinicius741.webnovelarchiver.download

/** Testable orchestration for Android's short foreground-service timeout grace period. */
internal object DownloadForegroundServiceTimeoutHandler {
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
