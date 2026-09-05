package com.vinicius741.webnovelarchiver.source.network

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.min

/**
 * Translates a retry attempt into the delay before the next one, honoring a server `Retry-After`
 * header (seconds or HTTP-date) when present. Extracted so [NetworkClient] stays under its
 * file-size budget without inlining this date parsing into the retry loop.
 */
internal class RetryBackoff(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val jitterMillis: (Long) -> Long,
) {
    fun delayFor(
        attempt: Int,
        retryAfterHeader: String?,
        policy: SourceNetworkPolicy,
    ): Long {
        // An accepted server deadline is honored as-is (already sanity-capped by
        // [retryAfterMillis]); clamping it to the ordinary backoff cap would make this client
        // retry early against the server's explicit instruction (R14).
        val serverRequested = retryAfterMillis(retryAfterHeader, policy)
        val maximumJitter = min(policy.maximumJitterMillis.coerceAtLeast(0L), (serverRequested ?: 0L) / 5L)
        if (serverRequested != null) {
            val jitter = jitterMillis(maximumJitter).coerceIn(0L, maximumJitter)
            return serverRequested + jitter
        }
        val clientBackoff =
            (policy.baseRetryDelayMillis.coerceAtLeast(0L) * attempt)
                .coerceAtMost(policy.maximumRetryDelayMillis.coerceAtLeast(0L))
        val clientJitterMax = min(policy.maximumJitterMillis.coerceAtLeast(0L), clientBackoff / 5L)
        val jitter = jitterMillis(clientJitterMax).coerceIn(0L, clientJitterMax)
        return (clientBackoff + jitter).coerceAtMost(policy.maximumRetryDelayMillis.coerceAtLeast(0L))
    }

    fun retryAfterMillis(
        header: String?,
        policy: SourceNetworkPolicy,
    ): Long? {
        if (header.isNullOrBlank()) return null
        val rawMillis =
            header.trim().toLongOrNull()?.let { seconds ->
                seconds
                    .coerceIn(0L, Long.MAX_VALUE / 1_000L)
                    .times(1_000L)
            }
                ?: runCatching {
                    ZonedDateTime
                        .parse(header.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant()
                        .toEpochMilli()
                        .minus(nowMillis())
                        .coerceAtLeast(0L)
                }.getOrNull()
        return rawMillis?.coerceAtMost(policy.maximumRetryAfterMillis.coerceAtLeast(0L))
    }
}
