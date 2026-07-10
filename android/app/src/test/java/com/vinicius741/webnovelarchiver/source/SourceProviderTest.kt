package com.vinicius741.webnovelarchiver.source

import com.vinicius741.webnovelarchiver.domain.model.PublicationStatus
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import com.vinicius741.webnovelarchiver.source.network.NetworkParseException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SourceProviderTest {
    @Test
    fun royalRoadParsesMetadataChaptersAndContent() =
        runBlocking {
            val html =
                """
                <html><head>
                  <link rel="canonical" href="https://www.royalroad.com/fiction/123/story" />
                  <meta property="og:image" content="https://img.example/cover.jpg" />
                </head><body>
                  <h1>Example RR</h1>
                  <h4><a>Author Name</a></h4>
                  <div id="comments"><a href="https://www.patreon.com/commenter">Commenter's Patreon</a></div>
                  <div class="author-profile"><a href="https://www.patreon.com/example-author?utm_source=royalroad">Support on Patreon</a></div>
                  <div class="description"><p>First paragraph.</p><p>Second paragraph.</p></div>
                  <div class="fiction-info"><div class="margin-bottom-10"><span class="label">Original</span><span class="label">COMPLETED</span></div></div>
                  <div class="tags"><a>Fantasy</a><a>Adventure</a></div>
                  <div class="chapter-row"><a href="/fiction/123/story/chapter/456/one">Chapter One (2 hours ago)</a><time datetime="2024-05-12T10:15:30Z"></time></div>
                  <div class="chapter-inner"><p>Body</p><script>bad()</script><div class="portlet">ad</div></div>
                </body></html>
                """.trimIndent()

            val metadata = RoyalRoadProvider.parseMetadata(html)
            val chapters = RoyalRoadProvider.getChapterList(html, "https://www.royalroad.com/fiction/123/story", NetworkClient())
            val content = RoyalRoadProvider.parseChapterContent(html)

            assertEquals("Example RR", metadata.title)
            assertEquals("First paragraph.\n\nSecond paragraph.", metadata.description)
            assertEquals("Author Name", metadata.author)
            assertEquals("https://www.patreon.com/example-author", metadata.patreonUrl)
            assertEquals(PublicationStatus.completed, metadata.publicationStatus)
            assertEquals("456", chapters.single().id)
            assertEquals("Chapter One", chapters.single().title)
            assertTrue(chapters.single().url.contains("/chapter/456/"))
            assertEquals(1_715_508_930_000L, chapters.single().publishedAt)
            assertEquals("<p>Body</p>", content.trim())
        }

    @Test
    fun royalRoadUsesArticleAndTwitterAuthorMetadataFallbacks() {
        val articleMetadata =
            RoyalRoadProvider.parseMetadata(
                """
                <html><head><meta property="article:author" content="Article Author" /></head><body><h1>Story</h1></body></html>
                """.trimIndent(),
            )
        val twitterMetadata =
            RoyalRoadProvider.parseMetadata(
                """
                <html><head><meta name="twitter:creator" content="Twitter Author" /></head><body><h1>Story</h1></body></html>
                """.trimIndent(),
            )

        assertEquals("Article Author", articleMetadata.author)
        assertEquals("Twitter Author", twitterMetadata.author)
    }

    @Test
    fun descriptionPreservesParagraphsLineBreaksAndCollapsesWhitespace() {
        val html =
            """
            <html><body>
              <div class="description">
                <p>First paragraph with  extra   spaces.</p>
                <div><p>Second paragraph<br/>with a hard break.</p></div>
                <blockquote>Quoted line.</blockquote>
              </div>
            </body></html>
            """.trimIndent()
        val metadata = RoyalRoadProvider.parseMetadata(html)
        assertEquals(
            "First paragraph with extra spaces.\n\nSecond paragraph\nwith a hard break.\n\nQuoted line.",
            metadata.description,
        )
    }

    @Test
    fun descriptionFallsBackToSingleLineMetaTagWhenNoStructuredBlock() {
        val metadata =
            RoyalRoadProvider.parseMetadata(
                """<html><head><meta property="og:description" content="Flat meta description." /></head><body><h1>Story</h1></body></html>""",
            )
        assertEquals("Flat meta description.", metadata.description)
    }

    @Test
    fun royalRoadMapsSourceHiatusLabelToHiatus() {
        val metadata =
            RoyalRoadProvider.parseMetadata(
                """
                <html><body>
                  <h1>Story</h1>
                  <div class="fiction-info"><div class="margin-bottom-10"><span class="label">HIATUS</span></div></div>
                </body></html>
                """.trimIndent(),
            )

        assertEquals(PublicationStatus.hiatus, metadata.publicationStatus)
    }

    @Test
    fun scribbleHubParsesMetadataChaptersAndContent() =
        runBlocking {
            val html =
                """
                <html><head><link rel="canonical" href="https://www.scribblehub.com/series/99/story/" /></head><body>
                  <div class="fic_title">Example SH</div>
                  <div class="auth_name_fic"><a>SH Author</a></div>
                  <a href="https://patreon.com/sh-author/posts">Patreon</a>
                  <div class="wi_fic_desc"><p>SH one.</p><p>SH two.</p></div>
                  <span class="wi_fic_status">Completed</span>
                  <div class="wi_fic_tags"><a>LitRPG</a></div>
                  <ol class="toc_ol"><li><a href="https://www.scribblehub.com/read/99-story/chapter/1000/">Chapter A</a><span>May 12, 2024</span></li></ol>
                  <div id="chp_raw"><p>Chapter body</p><div class="wi_authornotes">note</div><script>bad()</script></div>
                </body></html>
                """.trimIndent()

            val metadata = ScribbleHubProvider.parseMetadata(html)
            val chapters = ScribbleHubProvider.getChapterList(html, "https://www.scribblehub.com/series/99/story/", NetworkClient())
            val content = ScribbleHubProvider.parseChapterContent(html)

            assertEquals("Example SH", metadata.title)
            assertEquals("SH one.\n\nSH two.", metadata.description)
            assertEquals("SH Author", metadata.author)
            assertEquals("https://patreon.com/sh-author/posts", metadata.patreonUrl)
            assertEquals(PublicationStatus.completed, metadata.publicationStatus)
            assertEquals("sh_1000", chapters.single().id)
            assertEquals(
                LocalDate
                    .of(2024, 5, 12)
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli(),
                chapters.single().publishedAt,
            )
            assertEquals("<p>Chapter body</p>", content.trim())
        }

    @Test
    fun scribbleHubParsesLiveAuthorSpanAndAggregateRatingJsonLd() {
        val metadata =
            ScribbleHubProvider.parseMetadata(
                """
                <html><head>
                  <script type="application/ld+json">
                    {"@context":"https://schema.org","@type":"AggregateRating","bestRating":"5","ratingValue":"4.8","ratingCount":53}
                  </script>
                </head><body>
                  <h1 class="fic_title">System Lost</h1>
                  <a href="/profile/me">Logged In User</a>
                  <span class="auth_name_fic">DarkTechnomancer</span>
                  <span id="ratefic_user"><i class="fa fa-star ficrate"></i><span> <span>4.7</span> <span>(53 ratings)</span></span></span>
                </body></html>
                """.trimIndent(),
            )

        assertEquals("DarkTechnomancer", metadata.author)
        assertEquals("4.8", metadata.score)
    }

    @Test
    fun scribbleHubMapsSidebarLifecycleTextToOngoing() {
        val metadata =
            ScribbleHubProvider.parseMetadata(
                """
                <html><body>
                  <h1 class="fic_title">System Lost</h1>
                  <span class="auth_name_fic">DarkTechnomancer</span>
                  <div>All Rights Reserved Ongoing Similar Series</div>
                </body></html>
                """.trimIndent(),
            )

        assertEquals(PublicationStatus.ongoing, metadata.publicationStatus)
    }

    @Test
    fun scribbleHubMapsUpdatedLifecycleLineAfterContentWarnings() {
        val metadata =
            ScribbleHubProvider.parseMetadata(
                """
                <html><body>
                  <h1 class="fic_title">Getting Warhammered [40k Fanfic]</h1>
                  <span class="auth_name_fic">QuietValerie</span>
                  <section id="reviews">
                    <p>Status: 41 - Aftershock</p>
                    <p>Status: c144</p>
                  </section>
                  <aside>
                    <ul>
                      <li>Content Warning</li>
                      <li>Gore</li>
                      <li>Sexual Content</li>
                      <li>Strong Language</li>
                      <li>Ongoing - Updated Jun 3, 2026</li>
                    </ul>
                  </aside>
                </body></html>
                """.trimIndent(),
            )

        assertEquals(PublicationStatus.ongoing, metadata.publicationStatus)
    }

    @Test
    fun scribbleHubMapsRightsSidebarLifecycleTextWithPunctuation() {
        val metadata =
            ScribbleHubProvider.parseMetadata(
                """
                <html><body>
                  <h1 class="fic_title">System Lost</h1>
                  <span class="auth_name_fic">DarkTechnomancer</span>
                  <div>All Rights Reserved; Completed - Updated Jul 4, 2026</div>
                </body></html>
                """.trimIndent(),
            )

        assertEquals(PublicationStatus.completed, metadata.publicationStatus)
    }

    @Test
    fun scribbleHubParsesRatingBlockWhenJsonLdIsMissing() {
        val metadata =
            ScribbleHubProvider.parseMetadata(
                """
                <html><body>
                  <h1 class="fic_title">System Lost</h1>
                  <span class="auth_name_fic">DarkTechnomancer</span>
                  <span id="ratefic_user"><span> <span>4.8</span> <span>(53 ratings)</span></span></span>
                </body></html>
                """.trimIndent(),
            )

        assertEquals("4.8", metadata.score)
    }

    @Test
    fun scribbleHubFallbackStoryIdMatchesJavascriptEncodeURIComponent() {
        assertEquals(
            "sh_url_https%3A%2F%2Fwww.scribblehub.com%2Fbad%20path%3A%C3%A9",
            ScribbleHubProvider.getStoryId("https://www.scribblehub.com/bad path:é"),
        )
    }

    @Test
    fun sanitizeTitleTrimsOverflowAndDateSuffixes() {
        assertEquals("Test Story", sanitizeTitle("Test Story..."))
        assertEquals("Test Story", sanitizeTitle("Test Story…"))
        assertEquals("Chapter 1", sanitizeTitle("Chapter 1 (2 hours ago)"))
        assertEquals("Chapter 2", sanitizeTitle("Chapter 2 - 5 days ago"))
        assertEquals("Chapter 3", sanitizeTitle("Chapter 3 | an hour ago"))
        assertEquals("Chapter 4", sanitizeTitle("Chapter 4 Nov 25, 2025"))
        assertEquals("Chapter 5", sanitizeTitle("Chapter 5 - 25 Nov 2025"))
        assertEquals("Untitled", sanitizeTitle("..."))
    }

    @Test
    fun scribbleHubParsesRelativeChapterDate() =
        runBlocking {
            val before = System.currentTimeMillis()
            val chapters =
                ScribbleHubProvider.getChapterList(
                    """
                    <html><body>
                      <ol class="toc_ol">
                        <li><a href="https://www.scribblehub.com/read/99-story/chapter/1000/">Chapter A - 2 days ago</a></li>
                      </ol>
                    </body></html>
                    """.trimIndent(),
                    "https://www.scribblehub.com/series/99/story/",
                    NetworkClient(),
                )
            val after = System.currentTimeMillis()

            val publishedAt = chapters.single().publishedAt
            assertNotNull(publishedAt)
            assertTrue(publishedAt!! in (before - 2L * 24L * 60L * 60L * 1000L)..(after - 2L * 24L * 60L * 60L * 1000L))
        }

    @Test
    fun royalRoadThrowsWhenChapterContentMissing() {
        // Previously this returned the literal "No content found", which got saved as the chapter body
        // and baked into the EPUB. It must now throw so the failure routes through the download job
        // error channel instead of polluting the generated book.
        val noContent = "<html><body><p>no chapter-inner here</p></body></html>"
        try {
            RoyalRoadProvider.parseChapterContent(noContent)
            fail("Expected NetworkParseException when chapter content selector is missing")
        } catch (expected: NetworkParseException) {
            assertTrue(expected.message!!.contains("content not found", ignoreCase = true))
        }
    }

    @Test
    fun scribbleHubThrowsWhenChapterContentMissing() {
        val noContent = "<html><body><p>no chp_raw here</p></body></html>"
        try {
            ScribbleHubProvider.parseChapterContent(noContent)
            fail("Expected NetworkParseException when chapter content selector is missing")
        } catch (expected: NetworkParseException) {
            assertTrue(expected.message!!.contains("content not found", ignoreCase = true))
        }
    }
}
