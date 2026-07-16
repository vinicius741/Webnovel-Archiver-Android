package com.vinicius741.webnovelarchiver.source.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareRenderedPageValidatorTest {
    @Test
    fun requiresExpectedScribbleHubChapterContent() {
        val request = request("https://www.scribblehub.com/read/story/chapter/123/")

        assertTrue(
            CloudflareRenderedPageValidator.isExpectedPage(
                request,
                "https://www.scribblehub.com/read/story/chapter/123/",
                "<html><body><div id=\"chp_raw\">Chapter</div></body></html>",
            ),
        )
        assertFalse(
            CloudflareRenderedPageValidator.isExpectedPage(
                request,
                request.url,
                "<html><body>Access denied</body></html>",
            ),
        )
    }

    @Test
    fun rejectsWrongOriginAndChallengeDom() {
        val request = request("https://www.scribblehub.com/series/123/story/")

        assertFalse(
            CloudflareRenderedPageValidator.isExpectedPage(
                request,
                "https://example.test/series/123/story/",
                "<html><body><input id=\"mypostid\"></body></html>",
            ),
        )
        assertFalse(
            CloudflareRenderedPageValidator.isExpectedPage(
                request,
                request.url,
                "<html><title>Just a moment...</title><body></body></html>",
            ),
        )
    }

    private fun request(url: String) =
        CloudflareWebViewRequest(
            url = url,
            method = "GET",
            userAgent = "test",
            headers = emptyMap(),
            postData = null,
        )
}
