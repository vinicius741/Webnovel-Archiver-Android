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
    fun rejectsPreviousChapterFromPersistentSession() {
        assertFalse(
            CloudflareRenderedPageValidator.isExpectedPage(
                request("https://www.scribblehub.com/read/story/chapter/100/"),
                "https://www.scribblehub.com/read/story/chapter/99/",
                "<html><body><div id=\"chp_raw\">Previous chapter</div></body></html>",
            ),
        )
    }

    @Test
    fun acceptsSlugRedirectForSameStableChapterId() {
        assertTrue(
            CloudflareRenderedPageValidator.isExpectedPage(
                request("https://www.scribblehub.com/read/old-slug/chapter/100/"),
                "https://www.scribblehub.com/read/new-slug/chapter/100/",
                "<html><body><div id=\"chp_raw\">Current chapter</div></body></html>",
            ),
        )
    }

    @Test
    fun rejectsPreviousFfnChapterFromPersistentSession() {
        // FanFiction chapter URLs classify as STORY, so only the chapter id distinguishes them.
        assertFalse(
            CloudflareRenderedPageValidator.isExpectedPage(
                request("https://www.fanfiction.net/s/7347955/3/Dreaming-of-Sunshine"),
                "https://www.fanfiction.net/s/7347955/2/Dreaming-of-Sunshine",
                "<html><body><div id=\"storytext\">Previous chapter</div></body></html>",
            ),
        )
    }

    @Test
    fun acceptsSpaceBattlesCanonicalThreadRedirectForSamePost() {
        // XenForo canonicalizes a page-1 post to its thread URL; the post id survives the kind flip.
        assertTrue(
            CloudflareRenderedPageValidator.isExpectedPage(
                request("https://forums.spacebattles.com/posts/12345/"),
                "https://forums.spacebattles.com/threads/story-slug.67890/#post-12345",
                "<html><body><article class=\"message-body\">Chapter post</article></body></html>",
            ),
        )
    }

    @Test
    fun acceptsXenForoReaderDefaultPageRedirectToCanonicalReader() {
        // Reader URLs are unclassified (no chapter id), so the generic path comparison decides;
        // XenForo redirects /reader/page-1 to the bare /reader/ canonical. Regression: the loaded
        // page was rejected as a different resource and every render timed out into the manual
        // circuit even though the page had loaded completely and unchallenged.
        assertTrue(
            CloudflareRenderedPageValidator.isExpectedPage(
                request("https://forums.spacebattles.com/threads/story-slug.1299177/reader/page-1?threadmark_category=1"),
                "https://forums.spacebattles.com/threads/story-slug.1299177/reader/?threadmark_category=1",
                "<html><body><article>Chapter one content</article></body></html>",
            ),
        )
    }

    @Test
    fun rejectsReaderRedirectToADifferentPage() {
        assertFalse(
            CloudflareRenderedPageValidator.isExpectedPage(
                request("https://forums.spacebattles.com/threads/story-slug.1299177/reader/page-2?threadmark_category=1"),
                "https://forums.spacebattles.com/threads/story-slug.1299177/reader/?threadmark_category=1",
                "<html><body><article>First page content</article></body></html>",
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

    @Test
    fun acceptsMobileRedirectForDesktopSourceUrl() {
        val request = request("https://www.fanfiction.net/s/7347955/1/Dreaming-of-Sunshine")

        assertTrue(
            CloudflareRenderedPageValidator.isExpectedPage(
                request,
                "https://m.fanfiction.net/s/7347955/1/Dreaming-of-Sunshine",
                "<html><body><div id=\"storytext\">Chapter</div></body></html>",
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
