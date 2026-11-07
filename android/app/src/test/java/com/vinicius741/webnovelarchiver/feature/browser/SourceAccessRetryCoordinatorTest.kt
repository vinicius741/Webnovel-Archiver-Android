package com.vinicius741.webnovelarchiver.feature.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class SourceAccessRetryCoordinatorTest {
    private val fired = mutableListOf<String>()

    @Before
    fun reset() {
        SourceAccessRetryCoordinator.clear()
        fired.clear()
    }

    private fun consume() = SourceAccessRetryCoordinator.consumeReadyRetry()?.invoke()

    @Test
    fun passedCheckFiresItsOwnRetryOnce() {
        val token = SourceAccessRetryCoordinator.arm { fired += "sync" }
        SourceAccessRetryCoordinator.markReadyToRetry(token)

        consume()
        consume()

        assertEquals(listOf("sync"), fired)
    }

    @Test
    fun abandonedCheckNeverFiresOnALaterUnrelatedPass() {
        val token = SourceAccessRetryCoordinator.arm { fired += "stale sync" }
        SourceAccessRetryCoordinator.abandon(token)

        // The blocked-download notification opens the solver with no retry of its own.
        SourceAccessRetryCoordinator.markReadyToRetry(SourceAccessRetryCoordinator.NO_RETRY)
        consume()

        assertEquals(emptyList<String>(), fired)
    }

    @Test
    fun unarmedLaunchCannotFireAnArmedRetry() {
        SourceAccessRetryCoordinator.arm { fired += "pending" }

        SourceAccessRetryCoordinator.markReadyToRetry(SourceAccessRetryCoordinator.NO_RETRY)

        assertNull(SourceAccessRetryCoordinator.consumeReadyRetry())
    }

    @Test
    fun newerArmSupersedesAnOlderLaunch() {
        val older = SourceAccessRetryCoordinator.arm { fired += "older" }
        val newer = SourceAccessRetryCoordinator.arm { fired += "newer" }

        SourceAccessRetryCoordinator.markReadyToRetry(older)
        consume()
        SourceAccessRetryCoordinator.abandon(older)
        SourceAccessRetryCoordinator.markReadyToRetry(newer)
        consume()

        assertEquals(listOf("newer"), fired)
    }

    @Test
    fun closingAfterPassingKeepsTheReadyRetry() {
        val token = SourceAccessRetryCoordinator.arm { fired += "sync" }
        SourceAccessRetryCoordinator.markReadyToRetry(token)
        SourceAccessRetryCoordinator.abandon(token)

        consume()

        assertEquals(listOf("sync"), fired)
    }
}
