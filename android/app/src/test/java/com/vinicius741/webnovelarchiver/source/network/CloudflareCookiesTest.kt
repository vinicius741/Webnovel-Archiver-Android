package com.vinicius741.webnovelarchiver.source.network

import org.junit.Assert.assertEquals
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
}
