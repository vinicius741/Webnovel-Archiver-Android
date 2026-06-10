package com.vinicius741.webnovelarchiver.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserUrlPlanningTest {
    @Test
    fun resolveUrlPreservesExplicitHttpUrls() {
        assertEquals(
            "https://www.royalroad.com/fiction/123/story?tab=chapters#toc",
            BrowserUrlPlanning.resolveUrl(" https://www.royalroad.com/fiction/123/story?tab=chapters#toc "),
        )
        assertEquals(
            "http://royalroad.com/fiction/123/story",
            BrowserUrlPlanning.resolveUrl("http://royalroad.com/fiction/123/story"),
        )
    }

    @Test
    fun resolveUrlAddsHttpsForUrlLikeInput() {
        assertEquals(
            "https://www.scribblehub.com/series/99/story/",
            BrowserUrlPlanning.resolveUrl("www.scribblehub.com/series/99/story/"),
        )
    }

    @Test
    fun resolveUrlConvertsPlainTextToGoogleSearch() {
        assertEquals(
            "https://www.google.com/search?q=royal%20road%20fiction",
            BrowserUrlPlanning.resolveUrl("royal road fiction"),
        )
    }

    @Test
    fun googleAuthDetectionMatchesExternalBrowserCases() {
        assertTrue(BrowserUrlPlanning.isGoogleAuthUrl("https://accounts.google.com/signin"))
        assertTrue(BrowserUrlPlanning.isGoogleAuthUrl("https://foo.accounts.google.com/signin"))
        assertTrue(BrowserUrlPlanning.isGoogleAuthUrl("https://www.google.com/o/oauth2/v2/auth"))
        assertFalse(BrowserUrlPlanning.isGoogleAuthUrl("https://www.google.com/search?q=oauth2"))
        assertFalse(BrowserUrlPlanning.isGoogleAuthUrl("https://www.royalroad.com/account/login"))
        assertFalse(BrowserUrlPlanning.isGoogleAuthUrl(""))
    }
}
