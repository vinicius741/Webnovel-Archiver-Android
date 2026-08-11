package com.vinicius741.webnovelarchiver.sync

import com.vinicius741.webnovelarchiver.domain.model.SourceAvailability
import com.vinicius741.webnovelarchiver.domain.model.SourceFailureKind
import com.vinicius741.webnovelarchiver.domain.model.SourceSyncState
import com.vinicius741.webnovelarchiver.source.network.HttpNetworkException
import com.vinicius741.webnovelarchiver.source.network.NetworkOfflineException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.UnknownHostException

class SourceSyncFailurePlanningTest {
    @Test
    fun notFoundMarksSourceUnavailableWithoutDiscardingFirstSeenTime() {
        val failure = SourceSyncFailurePlanning.classify(HttpNetworkException("https://source/story", 404))
        val first = SourceSyncFailurePlanning.afterFailure(SourceSyncState(), failure, checkedAt = 100L)
        val second = SourceSyncFailurePlanning.afterFailure(first, failure, checkedAt = 200L)

        assertEquals(SourceAvailability.not_found, second.availability)
        assertEquals(SourceFailureKind.not_found, second.lastFailure)
        assertEquals(2, second.consecutiveNotFoundCount)
        assertEquals(100L, second.unavailableSince)
        assertEquals(404, second.lastHttpStatus)
    }

    @Test
    fun transientFailureRecordsAttemptWithoutChangingAvailability() {
        val previous =
            SourceSyncState(
                availability = SourceAvailability.available,
                lastCheckedAt = 50L,
            )
        val failure =
            SourceSyncFailurePlanning.classify(
                NetworkOfflineException("https://source/story", UnknownHostException("offline")),
            )

        val result = SourceSyncFailurePlanning.afterFailure(previous, failure, checkedAt = 100L)

        assertEquals(SourceAvailability.available, result.availability)
        assertEquals(SourceFailureKind.offline, result.lastFailure)
        assertEquals(100L, result.lastCheckedAt)
        assertNull(result.unavailableSince)
    }

    @Test
    fun successfulCheckClearsUnavailableState() {
        val result = SourceSyncFailurePlanning.afterSuccess(checkedAt = 300L)

        assertEquals(SourceAvailability.available, result.availability)
        assertEquals(300L, result.lastCheckedAt)
        assertEquals(0, result.consecutiveNotFoundCount)
        assertNull(result.lastFailure)
        assertNull(result.unavailableSince)
    }
}
