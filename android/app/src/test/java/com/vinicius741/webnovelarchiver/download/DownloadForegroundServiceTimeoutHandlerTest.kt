package com.vinicius741.webnovelarchiver.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class DownloadForegroundServiceTimeoutHandlerTest {
    @Test
    fun recoversQueueBeforeStoppingForegroundAndService() {
        val calls = mutableListOf<String>()

        DownloadForegroundServiceTimeoutHandler.handle(
            recoverQueue = { calls += "recover" },
            stopForeground = { calls += "foreground" },
            stopService = { calls += "service" },
            onRecoveryFailure = { calls += "failure" },
        )

        assertEquals(listOf("recover", "foreground", "service"), calls)
    }

    @Test
    fun recoveryFailureIsReportedAndDoesNotPreventPromptStop() {
        val calls = mutableListOf<String>()
        val failure = IllegalStateException("disk unavailable")
        var reported: Exception? = null

        DownloadForegroundServiceTimeoutHandler.handle(
            recoverQueue = {
                calls += "recover"
                throw failure
            },
            stopForeground = { calls += "foreground" },
            stopService = { calls += "service" },
            onRecoveryFailure = { error ->
                calls += "failure"
                reported = error
            },
        )

        assertSame(failure, reported)
        assertEquals(listOf("recover", "failure", "foreground", "service"), calls)
    }

    @Test
    fun foregroundStopFailureStillStopsService() {
        val calls = mutableListOf<String>()
        val failure = IllegalStateException("foreground teardown failed")

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                DownloadForegroundServiceTimeoutHandler.handle(
                    recoverQueue = { calls += "recover" },
                    stopForeground = {
                        calls += "foreground"
                        throw failure
                    },
                    stopService = { calls += "service" },
                    onRecoveryFailure = { calls += "failure" },
                )
            }

        assertSame(failure, thrown)
        assertEquals(listOf("recover", "foreground", "service"), calls)
    }
}
