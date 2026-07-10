package com.vinicius741.webnovelarchiver.source.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceNetworkPolicyTest {
    @Test
    fun productionResolverKeepsScribbleHubRulesOutsideNetworkClient() {
        val policy = DefaultNetworkPolicyResolver.policyFor("https://www.scribblehub.com/chapter/1".toHttpUrl())

        assertEquals(1_500L, policy.minimumRequestGapMillis)
        assertEquals(3, policy.maximumAttempts)
        assertEquals(setOf(403, 429), policy.retryableStatusCodes)
    }

    @Test
    fun mockWebServerHostGetsGenericPolicyUnlessTestInjectsOne() {
        val policy = DefaultNetworkPolicyResolver.policyFor("http://localhost:1234/chapter".toHttpUrl())

        assertEquals(1, policy.maximumAttempts)
        assertTrue(policy.retryableStatusCodes.isEmpty())
    }
}
