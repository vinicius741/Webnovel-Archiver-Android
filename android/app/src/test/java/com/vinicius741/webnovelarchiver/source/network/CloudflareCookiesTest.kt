package com.vinicius741.webnovelarchiver.source.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CloudflareCookiesTest {
    @Test
    fun domainCandidatesIncludeParentDomainForWwwHost() {
        assertEquals(
            listOf(null, "www.scribblehub.com", ".www.scribblehub.com", "scribblehub.com", ".scribblehub.com"),
            CloudflareCookies.domainCandidates("https://www.scribblehub.com/series/123/story/"),
        )
    }

    @Test
    fun domainCandidatesKeepExactHostForBareDomain() {
        assertEquals(
            listOf(null, "scribblehub.com", ".scribblehub.com"),
            CloudflareCookies.domainCandidates("https://scribblehub.com/series/123/story/"),
        )
    }

    @Test
    fun clearanceScopeIncludesFanFictionMobileAndDesktopHosts() {
        assertEquals(
            listOf(
                "https://www.fanfiction.net/s/7347955/1/Dreaming-of-Sunshine",
                "https://fanfiction.net/s/7347955/1/Dreaming-of-Sunshine",
                "https://m.fanfiction.net/s/7347955/1/Dreaming-of-Sunshine",
            ),
            CloudflareCookies.clearanceScopeUrls(
                "https://www.fanfiction.net/s/7347955/1/Dreaming-of-Sunshine",
            ),
        )
    }

    @Test
    fun clearanceScopeDoesNotIncludeAnUnrelatedSourceHost() {
        val scope =
            CloudflareCookies.clearanceScopeUrls(
                "https://www.fanfiction.net/s/7347955/1/Dreaming-of-Sunshine",
            )

        assertFalse(scope.any { it.contains("scribblehub", ignoreCase = true) })
    }
}
