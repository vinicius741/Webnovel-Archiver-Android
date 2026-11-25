package com.vinicius741.webnovelarchiver.source.network

import com.vinicius741.webnovelarchiver.source.SourceRegistry
import okhttp3.HttpUrl

/** Retry and pacing rules owned by a source policy rather than hardcoded request logic. */
data class SourceNetworkPolicy(
    val minimumRequestGapMillis: Long = 0L,
    val maximumAttempts: Int = 1,
    val retryableStatusCodes: Set<Int> = emptySet(),
    /**
     * Statuses that are a rate limit only when the response carries `Retry-After`. Ranobes answers
     * a reader who requested too fast with a 403 "Too many requests" page and a deadline about an
     * hour out; a 403 without one stays an ordinary failure.
     */
    val retryAfterRateLimitStatusCodes: Set<Int> = emptySet(),
    val baseRetryDelayMillis: Long = 2_500L,
    val maximumRetryDelayMillis: Long = 60_000L,
    val maximumRetryAfterMillis: Long = 30L * 60L * 1_000L,
    val maximumJitterMillis: Long = 1_000L,
    val maximumAdaptiveGapMillis: Long = 30_000L,
    val requestWindowMillis: Long = 60_000L,
    val maximumRequestsPerWindow: Int = Int.MAX_VALUE,
    /**
     * Pages never go through OkHttp, only the persistent WebView session. For a source whose
     * Cloudflare rules challenge OkHttp every time: probing with it first spends a request that is
     * always rejected and shows the site two different clients on one address.
     */
    val browserTransportOnly: Boolean = false,
) {
    fun isRateLimit(
        statusCode: Int,
        retryAfterHeader: String?,
    ): Boolean =
        statusCode in retryableStatusCodes ||
            (statusCode in retryAfterRateLimitStatusCodes && !retryAfterHeader.isNullOrBlank())
}

fun interface NetworkPolicyResolver {
    fun policyFor(url: HttpUrl): SourceNetworkPolicy
}

/** Production host mapping. Tests inject a fixed resolver so MockWebServer uses the same path. */
object DefaultNetworkPolicyResolver : NetworkPolicyResolver {
    private val default = SourceNetworkPolicy()

    override fun policyFor(url: HttpUrl): SourceNetworkPolicy =
        SourceRegistry.providerForHost(url.host)?.descriptor?.networkPolicy ?: default
}
