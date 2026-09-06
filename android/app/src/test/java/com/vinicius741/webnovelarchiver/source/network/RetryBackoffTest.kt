package com.vinicius741.webnovelarchiver.source.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Delay rules for [RetryBackoff]: a server-directed `Retry-After` keeps its jitter inside the
 * sanity cap, while ordinary client backoff keeps its jitter inside the backoff cap.
 */
class RetryBackoffTest {
    @Test
    fun serverDrivenDelayNeverExceedsTheRetryAfterSanityCap() {
        val policy = SourceNetworkPolicy(maximumRetryAfterMillis = 1_800_000L, maximumJitterMillis = 600_000L)
        val backoff = RetryBackoff(nowMillis = { 0L }, jitterMillis = { maximum -> maximum })

        // A 24h header is capped to 30 min; worst-case jitter must not push the sleep past it.
        val delay = backoff.delayFor(attempt = 1, retryAfterHeader = "86400", policy = policy)

        assertTrue(delay <= policy.maximumRetryAfterMillis)
        assertEquals(1_800_000L, delay)
    }

    @Test
    fun serverDrivenDelayKeepsJitterWhenItStaysBelowTheCap() {
        val policy = SourceNetworkPolicy(maximumRetryAfterMillis = 1_800_000L, maximumJitterMillis = 600_000L)
        val backoff = RetryBackoff(nowMillis = { 0L }, jitterMillis = { _ -> 500L })

        val delay = backoff.delayFor(attempt = 2, retryAfterHeader = "60", policy = policy)

        assertEquals(60_500L, delay)
    }

    @Test
    fun clientBackoffKeepsItsJitterWithinTheBackoffCap() {
        val policy =
            SourceNetworkPolicy(
                baseRetryDelayMillis = 1_000L,
                maximumRetryDelayMillis = 10_000L,
                maximumJitterMillis = 200L,
            )
        val backoff = RetryBackoff(nowMillis = { 0L }, jitterMillis = { maximum -> maximum })

        assertEquals(1_200L, backoff.delayFor(attempt = 1, retryAfterHeader = null, policy = policy))
    }
}
