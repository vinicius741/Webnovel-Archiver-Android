package com.vinicius741.webnovelarchiver.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture-based parser regression tests (Test Recommendations: Parser Fixtures). Each source keeps
 * sanitized HTML fixtures under `src/test/resources/fixtures/...`; these tests make parser drift
 * obvious when a site changes its markup. `getChapterList` is exercised without a live network via
 * a no-op network stub since the Royal Road fixture already includes the chapter rows inline.
 */
class SourceProviderFixtureTest {
    private fun fixture(path: String): String =
        javaClass.getResourceAsStream(path)!!.bufferedReader().use { it.readText() }

    private val noopNetwork = NetworkClient()

    @Test
    fun royalRoadMetadataParsesTitleAuthorCoverTags() {
        val html = fixture("/fixtures/royalroad/story.html")
        val meta = RoyalRoadProvider.parseMetadata(html)
        assertEquals("The Lorem Chronicle", meta.title)
        assertEquals("IpsumWriter", meta.author)
        assertEquals("https://www.royalroad.com/fiction/12345/the-lorem-chronicle", meta.canonicalUrl)
        assertEquals("https://www.royalroad.com/covers/12345.jpg", meta.coverUrl)
        // "tags" label is filtered out; Fantasy + Adventure remain, distinct.
        val tags = meta.tags.orEmpty()
        assertTrue(tags.contains("Fantasy"))
        assertTrue(tags.contains("Adventure"))
        assertFalse(tags.any { it.equals("tags", true) })
    }

    @Test
    fun royalRoadChapterListReadsRowsFromFixture() = runBlocking {
        val html = fixture("/fixtures/royalroad/story.html")
        val chapters = RoyalRoadProvider.getChapterList(html, "https://www.royalroad.com/fiction/12345/x", noopNetwork)
        assertEquals(2, chapters.size)
        assertEquals("100001", chapters[0].id)
        assertTrue(chapters[0].url.contains("/fiction/12345/chapter/100001/"))
        assertEquals("Chapter 1: Beginnings", chapters[0].title)
    }

    @Test
    fun royalRoadChapterContentStripsChromeAndScripts() {
        val html = fixture("/fixtures/royalroad/chapter.html")
        val content = RoyalRoadProvider.parseChapterContent(html)
        assertTrue(content.contains("best of lorem"))
        assertFalse(content.contains("navigation chrome"))
        assertFalse(content.contains("ads"))
    }

    @Test
    fun scribbleHubMetadataParsesTitleAuthorCoverScore() {
        val html = fixture("/fixtures/scribblehub/story.html")
        val meta = ScribbleHubProvider.parseMetadata(html)
        assertEquals("The Scribbled Saga", meta.title)
        assertEquals("ScribeAuthor", meta.author)
        assertEquals("https://www.scribblehub.com/series/98765/the-scribbled-saga", meta.canonicalUrl)
        assertEquals("4.5", meta.score)
        assertTrue(meta.tags.orEmpty().contains("Slowburn"))
    }

    @Test
    fun scribbleHubChapterListParsesTocWithoutAjaxWhenSmall() = runBlocking {
        val html = fixture("/fixtures/scribblehub/story.html")
        val chapters = ScribbleHubProvider.getChapterList(html, "https://www.scribblehub.com/series/98765/x", noopNetwork)
        // Fixtures have 2 entries; below the 15-chapter ajax threshold so no pagination occurs.
        assertEquals(2, chapters.size)
        // ScribbleHub reverses the TOC (newest-first → oldest-first), so the last document entry
        // ("sh_200002") lands first, and chapter ids are prefixed with "sh_".
        assertEquals("sh_200002", chapters.first().id)
        assertEquals("sh_200001", chapters.last().id)
    }

    @Test
    fun scribbleHubChapterContentStripsNotesAndScripts() {
        val html = fixture("/fixtures/scribblehub/chapter.html")
        val content = ScribbleHubProvider.parseChapterContent(html)
        assertTrue(content.contains("first sentence"))
        assertFalse(content.contains("Author's note"))
        assertFalse(content.contains("track"))
    }

    @Test
    fun providersDetectAndIdentifyTheirUrls() {
        assertTrue(RoyalRoadProvider.isSource("https://www.royalroad.com/fiction/12345/x"))
        assertEquals("rr_12345", RoyalRoadProvider.getStoryId("https://www.royalroad.com/fiction/12345/x"))
        assertTrue(ScribbleHubProvider.isSource("https://www.scribblehub.com/series/98765/x"))
        assertEquals("sh_98765", ScribbleHubProvider.getStoryId("https://www.scribblehub.com/series/98765/x"))
    }
}
