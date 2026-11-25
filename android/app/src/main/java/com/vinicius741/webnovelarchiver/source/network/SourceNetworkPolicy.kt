package com.vinicius741.webnovelarchiver.source.network

import com.vinicius741.webnovelarchiver.source.SourceRegistry
import okhttp3.HttpUrl

/** Retry and pacing rules owned by a source policy rather than hardcoded request logic. */
data class SourceNetworkPolicy(
    val minimumRequestGapMillis: Long = 0L,
    val maximumAttempts: Int = 1,
    val retryableStatusCodes: Set<Int> = emptySet(),
    /**
     * Statuses that mean the site has locked this device out when they carry `Retry-After` or the
     * site's own limit page. Ranobes answers a reader who requested too fast with a 403 "Too many
     * requests" page and a deadline about an hour out, or with a 503 and a one-minute deadline it
     * keeps repeating, while a visit in the browser still works. Like a Cloudflare challenge, it
     * blocks the whole source until the user opens it in the browser; one of these statuses
     * without either signal stays an ordinary failure.
     */
    val accessLimitStatusCodes: Set<Int> = emptySet(),
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
    fun isAccessLimit(
        statusCode: Int,
        retryAfterHeader: String?,
        body: String,
    ): Boolean =
        statusCode in accessLimitStatusCodes &&
            (!retryAfterHeader.isNullOrBlank() || SourceAccessBlockDetector.isRequestLimitPage(body))
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
