package com.vinicius741.webnovelarchiver.source.network

import okhttp3.HttpUrl

/** Retry and pacing rules owned by a source policy rather than hardcoded request logic. */
data class SourceNetworkPolicy(
    val minimumRequestGapMillis: Long = 0L,
    val maximumAttempts: Int = 1,
    val retryableStatusCodes: Set<Int> = emptySet(),
    val baseRetryDelayMillis: Long = 2_500L,
    val maximumRetryDelayMillis: Long = 60_000L,
    val maximumRetryAfterMillis: Long = 60_000L,
    val maximumJitterMillis: Long = 1_000L,
)

fun interface NetworkPolicyResolver {
    fun policyFor(url: HttpUrl): SourceNetworkPolicy
}

/** Production host mapping. Tests inject a fixed resolver so MockWebServer uses the same path. */
object DefaultNetworkPolicyResolver : NetworkPolicyResolver {
    private val default = SourceNetworkPolicy()
    private val scribbleHub =
        SourceNetworkPolicy(
            minimumRequestGapMillis = 1_500L,
            maximumAttempts = 3,
            retryableStatusCodes = setOf(403, 429),
        )

    override fun policyFor(url: HttpUrl): SourceNetworkPolicy =
        when (url.host.lowercase()) {
            "scribblehub.com", "www.scribblehub.com" -> scribbleHub
            else -> default
        }
}
