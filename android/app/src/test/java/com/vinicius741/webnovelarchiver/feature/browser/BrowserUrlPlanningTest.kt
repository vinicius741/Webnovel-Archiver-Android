package com.vinicius741.webnovelarchiver.feature.browser

import com.vinicius741.webnovelarchiver.feature.story.resolveUrl
import org.junit.Assert.assertEquals
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
}
