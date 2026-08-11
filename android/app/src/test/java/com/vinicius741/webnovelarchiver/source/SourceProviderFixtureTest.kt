package com.vinicius741.webnovelarchiver.source

import com.vinicius741.webnovelarchiver.domain.model.PublicationStatus
import com.vinicius741.webnovelarchiver.domain.model.SourceMetricKind
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import com.vinicius741.webnovelarchiver.ui.size
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

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
        assertEquals(listOf("AI-Assisted Content", "Graphic Violence"), meta.sourceMetadata.contentWarnings)
        assertEquals("Original", meta.sourceMetadata.sourceType)
        assertEquals("4.65", meta.score)
    }

    @Test
    fun royalRoadMetadataParsesLabelDrivenMetricsAndJsonLdDates() {
        val metadata = RoyalRoadProvider.parseMetadata(fixture("/fixtures/royalroad/story.html"))
        val metrics = metadata.sourceMetadata.metrics.associate { it.kind to it.value }

        assertEquals(2_360_000L, metrics[SourceMetricKind.TOTAL_VIEWS])
        assertEquals(12_500L, metrics[SourceMetricKind.AVERAGE_VIEWS])
        assertEquals(0L, metrics[SourceMetricKind.FOLLOWERS])
        assertEquals(1_240L, metrics[SourceMetricKind.FAVORITES])
        assertEquals(1_024L, metrics[SourceMetricKind.RATINGS])
        assertEquals(182L, metrics[SourceMetricKind.PAGES])
        assertEquals(Instant.parse("2024-01-02T03:04:05Z").toEpochMilli(), metadata.sourceMetadata.createdAt)
        assertEquals(Instant.parse("2024-01-05T15:30:00Z").toEpochMilli(), metadata.sourceMetadata.publishedAt)
        assertEquals(Instant.parse("2026-07-30T00:00:00Z").toEpochMilli(), metadata.sourceMetadata.updatedAt)
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
            assertEquals(1, chapters[0].chapterNumber)
            assertEquals(2, chapters[1].chapterNumber)
        }

    @Test
    fun royalRoadChapterNumberUsesStableWindowDataWhenTitleHasNoNumber() =
        runBlocking {
            val html = fixture("/fixtures/royalroad/story.html").replace("Chapter 1: Beginnings", "Opening Scene")
            val chapters = RoyalRoadProvider.getChapterList(html, "https://www.royalroad.com/fiction/12345/x", noopNetwork)

            assertEquals("Opening Scene", chapters.first().title)
            assertEquals(1, chapters.first().chapterNumber)
        }

    @Test
    fun royalRoadListingStateIsSeparateFromPublicationStatus() {
        val metadata =
            RoyalRoadProvider.parseMetadata(
                """
                <html><body>
                  <h1>A Stub</h1>
                  <h4><a>Author</a></h4>
                  <div class="fiction-info"><div class="margin-bottom-10">
                    <span class="label">Fan Fiction</span>
                    <span class="label">INACTIVE</span>
                    <span class="label">STUB</span>
                  </div></div>
                </body></html>
                """.trimIndent(),
            )

        assertEquals(PublicationStatus.unknown, metadata.publicationStatus)
        assertEquals("Fan Fiction", metadata.sourceMetadata.sourceType)
        assertEquals("INACTIVE, STUB", metadata.sourceMetadata.sourceListingState)
        assertTrue(metadata.sourceMetadata.metrics.isEmpty())
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
        assertEquals(Instant.parse("2024-05-12T09:00:00Z").toEpochMilli(), meta.sourceMetadata.publishedAt)
        assertEquals(listOf("Violence", "Sexual Content"), meta.sourceMetadata.contentWarnings)
    }

    @Test
    fun scribbleHubStatsFixtureParsesAllRetainedMetrics() {
        val metadata = ScribbleHubProvider.parseStatsMetadata(fixture("/fixtures/scribblehub/stats.html"))

        fun metric(kind: SourceMetricKind): Long? = metadata.metrics.firstOrNull { it.kind == kind }?.value

        assertEquals(6_200L, metric(SourceMetricKind.READERS))
        assertEquals(0L, metric(SourceMetricKind.FAVORITES))
        assertEquals(2_470_000L, metric(SourceMetricKind.TOTAL_VIEWS))
        assertEquals(2_250_000L, metric(SourceMetricKind.TOTAL_VIEWS_CHAPTERS))
        assertEquals(12_300L, metric(SourceMetricKind.AVERAGE_VIEWS))
        assertEquals(2_250_000L, metric(SourceMetricKind.WORDS))
        assertEquals(34_500L, metric(SourceMetricKind.AVERAGE_WORDS))
        assertEquals(1_234L, metric(SourceMetricKind.PAGES))
        assertEquals(0L, metric(SourceMetricKind.CHAPTERS_PER_WEEK))
        assertEquals(366L, metric(SourceMetricKind.RATINGS))
        assertEquals(48L, metric(SourceMetricKind.REVIEWS))
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
            166_000L,
            meta.sourceMetadata.metrics
                .single { it.kind == SourceMetricKind.WORDS }
                .value,
        )
        assertEquals(
            4_975L,
            meta.sourceMetadata.metrics
                .single { it.kind == SourceMetricKind.WATCHERS }
                .value,
        )
        assertEquals(Instant.parse("2024-01-02T03:04:05Z").toEpochMilli(), meta.sourceMetadata.createdAt)
        assertEquals(Instant.parse("2025-11-26T13:32:08Z").toEpochMilli(), meta.sourceMetadata.updatedAt)
        assertEquals("Quest", meta.sourceMetadata.sourceType)
        assertEquals("Original Fiction", meta.sourceMetadata.sourceCategory)
        assertEquals("Open", meta.sourceMetadata.sourceListingState)
        assertEquals("Ongoing", meta.sourceMetadata.sourceStatus)
        assertFalse(meta.sourceMetadata.metrics.any { it.kind == SourceMetricKind.LIKES })
        assertEquals(
            "https://forums.spacebattles.com/data/attachments/fixture-cover.jpg",
            meta.coverUrl,
        )
        assertEquals(listOf("space opera", "original"), meta.tags)
        assertTrue(meta.description.orEmpty().contains("\n\n"))
    }

    @Test
    fun spaceBattlesPreservesDroppedAndCancelledSourceStatuses() {
        val html = fixture("/fixtures/spacebattles/story.html")
        listOf("Dropped", "Cancelled").forEach { rawStatus ->
            val meta = SpaceBattlesProvider.parseMetadata(html.replace("Ongoing", rawStatus))
            assertEquals(rawStatus, meta.sourceMetadata.sourceStatus)
            assertEquals(PublicationStatus.hiatus, meta.publicationStatus)
            assertNotEquals(PublicationStatus.completed, meta.publicationStatus)
        }
    }

    @Test
    fun spaceBattlesMetadataStoresNewestMainThreadmarkRssDate() {
        val rss =
            """
            <rss><channel>
              <lastBuildDate>Sun, 26 Jul 2026 12:00:00 GMT</lastBuildDate>
              <item><pubDate>Fri, 24 Jul 2026 12:00:00 GMT</pubDate></item>
              <item><pubDate>Sat, 25 Jul 2026 12:00:00 GMT</pubDate></item>
            </channel></rss>
            """.trimIndent()

        val meta = SpaceBattlesProvider.parseMetadata(fixture("/fixtures/spacebattles/story.html"), rss)

        assertEquals(Instant.parse("2026-07-25T12:00:00Z").toEpochMilli(), meta.sourceMetadata.updatedAt)
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
