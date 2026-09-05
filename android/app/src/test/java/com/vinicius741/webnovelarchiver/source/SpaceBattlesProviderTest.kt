package com.vinicius741.webnovelarchiver.source

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.SourceMetricKind
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import com.vinicius741.webnovelarchiver.source.network.SourceChapterListIncompleteException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SpaceBattlesProviderTest {
    private lateinit var server: MockWebServer
    private lateinit var network: NetworkClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        network = NetworkClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun fullSyncPaginatesMainThreadmarksAndExcludesOtherCategories() =
        runBlocking {
            val root = server.url("/threads/fixture-story.1183048/").toString()
            val storyHtml = """<link rel="canonical" href="$root">"""
            server.enqueue(
                MockResponse().setBody(
                    threadmarkPage(
                        mainRows = threadmarkRow(7001, "Chapter 1"),
                        otherRows = threadmarkRow(9001, "Sidestory: Not main"),
                        lastPage = 2,
                    ),
                ),
            )
            server.enqueue(MockResponse().setBody(threadmarkPage(threadmarkRow(7002, "Chapter 2"))))

            val progress = mutableListOf<String>()
            val chapters = SpaceBattlesProvider.getChapterList(storyHtml, root, network, progress::add)

            assertEquals(listOf("sb_7001", "sb_7002"), chapters.map { it.id })
            assertEquals(
                listOf(
                    "Fetching threadmark page 1...",
                    "Fetching threadmark page 2 of 2 · 1 chapter found...",
                    "2 chapters found",
                ),
                progress,
            )
            assertEquals("${server.url("/")}posts/7001/", chapters.first().url)
            assertEquals(2, server.requestCount)
            assertTrue(server.takeRequest().path!!.contains("threadmark_category=1"))
            assertTrue(server.takeRequest().path!!.contains("page=2"))
        }

    @Test
    fun fullSyncRejectsChapterListWhenDeclaredPagesExceedFetchCap() =
        runBlocking {
            val root = server.url("/threads/fixture-story.1183048/").toString()
            val storyHtml = """<link rel="canonical" href="$root">"""
            // Declaring more pages than the fetch cap would silently truncate the list; the
            // provider must fail the full sync instead (R03).
            server.enqueue(
                MockResponse().setBody(
                    threadmarkPage(
                        mainRows = threadmarkRow(7001, "Chapter 1"),
                        lastPage = 501,
                    ),
                ),
            )

            val error =
                runCatching {
                    SpaceBattlesProvider.getChapterList(storyHtml, root, network) {}
                }.exceptionOrNull()

            assertTrue(error is SourceChapterListIncompleteException)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun rssUsesStablePostIdsAndReturnsOldestFirst() {
        val root = "https://forums.spacebattles.com/threads/fixture-story.1183048/"
        val rss =
            """
            <rss><channel>
              <item>
                <title>Chapter 12</title>
                <pubDate>Fri, 24 Jul 2026 12:00:00 GMT</pubDate>
                <link>${root}post-7012</link>
                <guid>https://forums.spacebattles.com/posts/7012</guid>
              </item>
              <item>
                <title>Chapter 11</title>
                <pubDate>Thu, 23 Jul 2026 12:00:00 GMT</pubDate>
                <link>${root}post-7011</link>
                <guid>https://forums.spacebattles.com/posts/7011</guid>
              </item>
            </channel></rss>
            """.trimIndent()

        val chapters = SpaceBattlesProvider.parseThreadmarkRss(rss, root)

        assertEquals(listOf("sb_7011", "sb_7012"), chapters.map { it.id })
        assertEquals("https://forums.spacebattles.com/posts/7012/", chapters.last().url)
        assertTrue(chapters.all { it.publishedAt != null })
    }

    @Test
    fun rssUpdatedAtUsesNewestPubDateInsteadOfLastBuildDate() {
        val rss =
            """
            <rss><channel>
              <lastBuildDate>Sun, 26 Jul 2026 12:00:00 GMT</lastBuildDate>
              <item>
                <title>Chapter 12</title>
                <pubDate>Fri, 24 Jul 2026 12:00:00 GMT</pubDate>
                <guid>https://forums.spacebattles.com/posts/7012</guid>
              </item>
              <item>
                <title>Chapter 13</title>
                <pubDate>Sat, 25 Jul 2026 12:00:00 GMT</pubDate>
                <guid>https://forums.spacebattles.com/posts/7013</guid>
              </item>
            </channel></rss>
            """.trimIndent()

        assertEquals(
            java.time.Instant
                .parse("2026-07-25T12:00:00Z")
                .toEpochMilli(),
            SpaceBattlesProvider.parseThreadmarkRssUpdatedAt(rss),
        )
    }

    @Test
    fun enrichMetadataFetchesRssAndExactStoryLibraryCard() =
        runBlocking {
            val root = server.url("/threads/fixture-story.1183048/").toString()
            val storyHtml =
                """
                <link rel="canonical" href="$root">
                <div class="p-description"><a class="username">Example Author</a></div>
                """.trimIndent()
            server.enqueue(
                MockResponse().setBody(
                    """
                    <rss><channel>
                      <lastBuildDate>Sun, 26 Jul 2026 12:00:00 GMT</lastBuildDate>
                      <item><pubDate>Fri, 24 Jul 2026 12:00:00 GMT</pubDate></item>
                      <item><pubDate>Sat, 25 Jul 2026 12:00:00 GMT</pubDate></item>
                    </channel></rss>
                    """.trimIndent(),
                ),
            )
            server.enqueue(
                MockResponse().setBody(
                    storyLibraryCard("999", watchers = "999K", replies = "999K", views = "999M", likes = "999K") +
                        storyLibraryCard("1183048", watchers = "5K", replies = "4K", views = "774K", likes = "59K"),
                ),
            )

            val metadata =
                SpaceBattlesProvider.enrichMetadata(
                    SpaceBattlesProvider.parseMetadata(storyHtml),
                    storyHtml,
                    root,
                    network,
                )

            assertEquals(
                java.time.Instant
                    .parse("2026-07-25T12:00:00Z")
                    .toEpochMilli(),
                metadata.sourceMetadata.updatedAt,
            )
            assertEquals(
                mapOf(
                    SourceMetricKind.WORDS to 166_000L,
                    SourceMetricKind.WATCHERS to 5_000L,
                    SourceMetricKind.REPLIES to 4_000L,
                    SourceMetricKind.TOTAL_VIEWS to 774_000L,
                    SourceMetricKind.LIKES to 59_000L,
                ),
                metadata.sourceMetadata.metrics.associate { it.kind to it.value },
            )
            assertEquals(2, server.requestCount)
            assertEquals(
                "/threads/fixture-story.1183048/threadmarks.rss?threadmark_category=1",
                server.takeRequest().path,
            )
            assertEquals(
                "/library/stories/?starter=Example%20Author",
                server.takeRequest().path,
            )
        }

    @Test
    fun storyLibraryParserReturnsNothingWhenExactThreadCardIsAbsent() {
        val metrics =
            parseSpaceBattlesLibraryMetrics(
                storyLibraryCard("999", watchers = "9K", replies = "8K", views = "7M", likes = "6K"),
                "1183048",
            )

        assertTrue(metrics.isEmpty())
    }

    @Test
    fun chapterDownloadsReuseReaderPageForSeveralPosts() =
        runBlocking {
            val root = server.url("/threads/fixture-story.1183048/").toString()
            server.enqueue(
                MockResponse().setBody(
                    readerPage(
                        readerPost(7001, "<p>First chapter.</p>") +
                            readerPost(7002, "<p>Second chapter.</p>"),
                    ),
                ),
            )

            val first =
                SpaceBattlesProvider.fetchChapterContent(
                    root,
                    Chapter(id = "sb_7001", url = server.url("/posts/7001/").toString()),
                    0,
                    network,
                )
            val second =
                SpaceBattlesProvider.fetchChapterContent(
                    root,
                    Chapter(id = "sb_7002", url = server.url("/posts/7002/").toString()),
                    1,
                    network,
                )

            assertTrue(first.contains("First chapter"))
            assertTrue(second.contains("Second chapter"))
            assertEquals(1, server.requestCount)
        }

    @Test
    fun readerLookupChecksNeighborPageWhenBoundaryHasShifted() =
        runBlocking {
            val root = server.url("/threads/fixture-story.1183048/").toString()
            server.enqueue(MockResponse().setBody(readerPage(readerPost(7999, "<p>Different post.</p>"))))
            server.enqueue(MockResponse().setBody(readerPage(readerPost(7001, "<p>Shifted chapter.</p>"))))

            val content =
                SpaceBattlesProvider.fetchChapterContent(
                    root,
                    Chapter(id = "sb_7001", url = server.url("/posts/7001/").toString()),
                    0,
                    network,
                )

            assertTrue(content.contains("Shifted chapter"))
            assertEquals(2, server.requestCount)
            assertTrue(server.takeRequest().path!!.contains("/reader/page-1"))
            assertTrue(server.takeRequest().path!!.contains("/reader/page-2"))
        }

    @Test
    fun malformedPostUrlsDoNotAbortContentParsing() {
        val html =
            readerPage(
                readerPost(
                    7001,
                    """<p>Safe prose.</p><a href="http://[malformed">link</a><img src="http://[malformed">""",
                ),
            )

        val content = SpaceBattlesProvider.parseChapterContent(html)

        assertTrue(content.contains("Safe prose"))
    }

    private fun threadmarkPage(
        mainRows: String,
        otherRows: String = "",
        lastPage: Int = 1,
    ): String =
        """
        <html><body>
          <div class="block-body block-body--threadmarkBody category-1">$mainRows</div>
          <div class="block-body block-body--threadmarkBody category-2">$otherRows</div>
          <div class="threadmarks-pagenav--wrapper category-1">
            <a href="?threadmark_category=1&amp;per_page=200&amp;page=$lastPage">$lastPage</a>
          </div>
        </body></html>
        """.trimIndent()

    private fun threadmarkRow(
        postId: Int,
        title: String,
    ): String =
        """
        <div class="structItem structItem--threadmark" data-preview-url="/posts/$postId/preview-threadmark">
          <div class="structItem-title"><a data-tp-primary="on" href="#post-$postId">$title</a></div>
          <time data-timestamp="1721800000"></time>
        </div>
        """.trimIndent()

    private fun readerPage(posts: String): String = "<html><body>$posts</body></html>"

    private fun readerPost(
        postId: Int,
        content: String,
    ): String =
        """
        <article class="message message--post" id="js-post-$postId" data-content="post-$postId">
          <div class="message-userContent"><div class="message-body"><div class="bbWrapper">
            $content<script>removeMe()</script>
          </div></div></div>
        </article>
        """.trimIndent()

    private fun storyLibraryCard(
        threadId: String,
        watchers: String,
        replies: String,
        views: String,
        likes: String,
    ): String =
        """
        <div class="structItem structItem--story js-threadListItem-$threadId">
          <a href="/threads/fixture-story.$threadId/">Fixture story</a>
          <div class="structItem-story-stats">
            <dl><dt>Words</dt><dd>166k</dd></dl>
            <dl><dt>Watchers</dt><dd>$watchers</dd></dl>
            <dl><dt>Replies</dt><dd>$replies</dd></dl>
            <dl><dt>Views</dt><dd>$views</dd></dl>
            <dl><dt>Likes</dt><dd>$likes</dd></dl>
          </div>
        </div>
        """.trimIndent()
}
