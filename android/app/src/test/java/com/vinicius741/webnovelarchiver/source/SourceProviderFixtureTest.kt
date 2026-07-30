package com.vinicius741.webnovelarchiver.source

import com.vinicius741.webnovelarchiver.domain.model.PublicationStatus
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import com.vinicius741.webnovelarchiver.ui.size
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
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
    private fun fixture(path: String): String = javaClass.getResourceAsStream(path)!!.bufferedReader().use { it.readText() }

    private val noopNetwork = NetworkClient()

    @Test
    fun royalRoadMetadataParsesTitleAuthorCoverTags() {
        val html = fixture("/fixtures/royalroad/story.html")
        val meta = RoyalRoadProvider.parseMetadata(html)
        assertEquals("The Lorem Chronicle", meta.title)
        assertEquals("IpsumWriter", meta.author)
        assertEquals("https://www.royalroad.com/fiction/12345/the-lorem-chronicle", meta.canonicalUrl)
        assertEquals("https://www.royalroad.com/covers/12345.jpg", meta.coverUrl)
        assertEquals(PublicationStatus.completed, meta.publicationStatus)
        // "tags" label is filtered out; Fantasy + Adventure remain, distinct.
        val tags = meta.tags.orEmpty()
        assertTrue(tags.contains("Fantasy"))
        assertTrue(tags.contains("Adventure"))
        assertFalse(tags.any { it.equals("tags", true) })
        // Content warnings from the red "Warning: This fiction contains:" block are captured as ⚠-prefixed tags.
        assertTrue(tags.contains("⚠ AI-Assisted Content"))
        assertTrue(tags.contains("⚠ Graphic Violence"))
    }

    @Test
    fun royalRoadChapterListReadsRowsFromFixture() =
        runBlocking {
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
        assertEquals(PublicationStatus.completed, meta.publicationStatus)
        assertTrue(meta.tags.orEmpty().contains("Slowburn"))
    }

    @Test
    fun scribbleHubChapterListParsesTocWithoutAjaxWhenSmall() =
        runBlocking {
            val html = fixture("/fixtures/scribblehub/story.html")
            val progress = mutableListOf<String>()
            val chapters =
                ScribbleHubProvider.getChapterList(
                    html,
                    "https://www.scribblehub.com/series/98765/x",
                    noopNetwork,
                    progress::add,
                )
            // Fixtures have 2 entries; below the 15-chapter ajax threshold so no pagination occurs.
            assertEquals(2, chapters.size)
            assertEquals(listOf("Parsing chapter list...", "2 chapters found"), progress)
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
    fun spaceBattlesMetadataParsesThreadmarkHeaderAndStarterArtwork() {
        val html = fixture("/fixtures/spacebattles/story.html")
        val meta = SpaceBattlesProvider.parseMetadata(html)
        assertEquals("Phantom Star", meta.title)
        assertEquals("ExampleAuthor", meta.author)
        assertEquals("https://forums.spacebattles.com/threads/fixture-story.1183048/", meta.canonicalUrl)
        assertEquals(PublicationStatus.ongoing, meta.publicationStatus)
        assertEquals(
            "https://forums.spacebattles.com/data/attachments/fixture-cover.jpg",
            meta.coverUrl,
        )
        assertEquals(listOf("space opera", "original"), meta.tags)
        assertTrue(meta.description.orEmpty().contains("\n\n"))
    }

    @Test
    fun spaceBattlesChapterContentStripsScripts() {
        val html = fixture("/fixtures/spacebattles/chapter.html")
        val content = SpaceBattlesProvider.parseChapterContent(html)
        assertTrue(content.contains("first star burned blue"))
        assertFalse(content.contains("trackReader"))
        val paragraphs = Jsoup.parseBodyFragment(content).select("p")
        assertEquals(3, paragraphs.size)
        assertEquals(3, paragraphs[0].select("br").size)
    }

    @Test
    fun providersDetectAndIdentifyTheirUrls() {
        assertTrue(RoyalRoadProvider.isSource("https://www.royalroad.com/fiction/12345/x"))
        assertEquals("rr_12345", RoyalRoadProvider.getStoryId("https://www.royalroad.com/fiction/12345/x"))
        assertTrue(ScribbleHubProvider.isSource("https://www.scribblehub.com/series/98765/x"))
        assertEquals("sh_98765", ScribbleHubProvider.getStoryId("https://www.scribblehub.com/series/98765/x"))
        assertTrue(SpaceBattlesProvider.isSource("https://forums.spacebattles.com/threads/fixture-story.1183048/"))
        assertTrue(SpaceBattlesProvider.isSource("https://forums.spacebattles.com/posts/7001/"))
        assertEquals("sb_1183048", SpaceBattlesProvider.getStoryId("https://forums.spacebattles.com/threads/x.1183048/"))
        assertEquals("sb_7001", SpaceBattlesProvider.getChapterId("https://forums.spacebattles.com/posts/7001/"))
    }
}
