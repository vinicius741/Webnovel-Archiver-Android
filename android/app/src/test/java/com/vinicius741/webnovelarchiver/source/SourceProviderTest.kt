package com.vinicius741.webnovelarchiver.source

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

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
                  <div class="tags"><a>Fantasy</a><a>Adventure</a></div>
                  <div class="chapter-row"><a href="/fiction/123/story/chapter/456/one">Chapter One (2 hours ago)</a></div>
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
            assertEquals("456", chapters.single().id)
            assertEquals("Chapter One", chapters.single().title)
            assertTrue(chapters.single().url.contains("/chapter/456/"))
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
    fun scribbleHubParsesMetadataChaptersAndContent() =
        runBlocking {
            val html =
                """
                <html><head><link rel="canonical" href="https://www.scribblehub.com/series/99/story/" /></head><body>
                  <div class="fic_title">Example SH</div>
                  <div class="auth_name_fic"><a>SH Author</a></div>
                  <a href="https://patreon.com/sh-author/posts">Patreon</a>
                  <div class="wi_fic_desc"><p>SH one.</p><p>SH two.</p></div>
                  <div class="wi_fic_tags"><a>LitRPG</a></div>
                  <ol class="toc_ol"><li><a href="https://www.scribblehub.com/read/99-story/chapter/1000/">Chapter A</a></li></ol>
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
            assertEquals("sh_1000", chapters.single().id)
            assertEquals("<p>Chapter body</p>", content.trim())
        }

    @Test
    fun scribbleHubFallbackStoryIdMatchesJavascriptEncodeURIComponent() {
        assertEquals(
            "sh_url_https%3A%2F%2Fwww.scribblehub.com%2Fbad%20path%3A%C3%A9",
            ScribbleHubProvider.getStoryId("https://www.scribblehub.com/bad path:é"),
        )
    }

    @Test
    fun sanitizeTitleMatchesReactNativeOverflowAndDateCleanup() {
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
    fun royalRoadThrowsWhenChapterContentMissing() {
        // Previously this returned the literal "No content found", which got saved as the chapter body
        // and baked into the EPUB. It must now throw so the failure routes through the download job
        // error channel instead of polluting the generated book.
        val noContent = "<html><body><p>no chapter-inner here</p></body></html>"
        try {
            RoyalRoadProvider.parseChapterContent(noContent)
            fail("Expected IllegalStateException when chapter content selector is missing")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("content not found", ignoreCase = true))
        }
    }

    @Test
    fun scribbleHubThrowsWhenChapterContentMissing() {
        val noContent = "<html><body><p>no chp_raw here</p></body></html>"
        try {
            ScribbleHubProvider.parseChapterContent(noContent)
            fail("Expected IllegalStateException when chapter content selector is missing")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("content not found", ignoreCase = true))
        }
    }
}
