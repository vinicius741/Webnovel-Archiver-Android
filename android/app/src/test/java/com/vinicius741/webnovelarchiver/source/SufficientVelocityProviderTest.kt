package com.vinicius741.webnovelarchiver.source

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.PublicationStatus
import com.vinicius741.webnovelarchiver.domain.model.SourceMetricKind
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import com.vinicius741.webnovelarchiver.source.network.NetworkParseException
import com.vinicius741.webnovelarchiver.source.network.SourceChapterListIncompleteException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SufficientVelocityProviderTest {
    private val provider = SufficientVelocityProvider
    private val root = "https://forums.sufficientvelocity.com/threads/fixture-story.12345/"

    @Test
    fun registryRecognizesThreadAndPostVariantsWithSeparateIds() {
        listOf(root, "$root?ref=test", "$root#post-7001", "https://forums.sufficientvelocity.com/threads/12345/")
            .forEach { url ->
                assertSame(provider, SourceRegistry.resolve(url)?.provider)
                assertTrue(SourceUrlValidation.isImportableStoryUrl(url))
                assertEquals("sv_12345", provider.getStoryId(url))
            }
        listOf("https://forums.sufficientvelocity.com/posts/7001/", "${root}post-7001", "$root#post-7001")
            .forEach { url -> assertEquals("sv_7001", provider.getChapterId(url)) }
        assertEquals(SourceUrlKind.CHAPTER, SourceRegistry.resolve("https://forums.sufficientvelocity.com/posts/7001/")?.kind)
        assertNotEquals(provider.getStoryId(root), SpaceBattlesProvider.getStoryId(root))
        assertNotEquals(provider.getChapterId(root + "#post-7001"), SpaceBattlesProvider.getChapterId(root + "#post-7001"))
        assertNull(SourceRegistry.resolve("https://forums.sufficientvelocity.com/threads/"))
        assertNull(SourceRegistry.resolve("https://forums.sufficientvelocity.com.evil.test/threads/title.12345/"))
        assertNull(SourceRegistry.resolve("https://example.com/?next=$root"))
        assertNull(SourceRegistry.resolve("https://forums.sufficientvelocity.com/?next=$root#post-7001"))
        assertNull(provider.classifyUrl("file://forums.sufficientvelocity.com/threads/title.12345/"))
        assertTrue(runCatching { provider.getStoryId("https://forums.sufficientvelocity.com/") }.isFailure)
    }

    @Test
    fun metadataReadsLiveMarkupWithSanitizedProseAndMetrics() {
        val metadata = provider.parseMetadata(fixture("story.html"))

        assertEquals("The Fixture Expedition", metadata.title)
        assertEquals("FixtureWriter", metadata.author)
        assertEquals(root, metadata.canonicalUrl)
        assertTrue(metadata.description!!.contains("moon.\n\nIts crew"))
        assertEquals(listOf("science fiction"), metadata.tags)
        assertEquals(PublicationStatus.ongoing, metadata.publicationStatus)
        assertEquals("https://www.patreon.com/fixturewriter", metadata.patreonUrl)
        assertEquals("Original Fiction", metadata.sourceMetadata.sourceCategory)
        assertEquals(Instant.parse("2026-01-01T12:00:00Z").toEpochMilli(), metadata.sourceMetadata.createdAt)
        assertEquals(
            mapOf(SourceMetricKind.WATCHERS to 25L, SourceMetricKind.WORDS to 8_000L),
            metadata.sourceMetadata.metrics.associate { it.kind to it.value },
        )
    }

    @Test
    fun threadmarkAndRssParsersAgreeOnPostIdentityAndChronology() {
        val index = provider.parseThreadmarks(fixture("threadmarks.html"), root)
        val rss = provider.parseThreadmarkRss(fixture("threadmarks.rss"), root)

        assertEquals(listOf("sv_7001", "sv_7002"), index.map { it.id })
        assertEquals(index.map { it.id }, rss.map { it.id })
        assertEquals(index.map { it.url }, rss.map { it.url })
        assertEquals(index.map { it.publishedAt }, rss.map { it.publishedAt })
        assertEquals("Chapter 1: Fixture", index.first().title)
        assertEquals("https://forums.sufficientvelocity.com/posts/7001/", index.first().url)
        assertEquals(Instant.parse("2026-01-02T12:00:00Z").toEpochMilli(), provider.parseThreadmarkRssUpdatedAt(fixture("threadmarks.rss")))
    }

    @Test
    fun emptyMainCategoryDoesNotImportSideStories() {
        val html = fixture("threadmarks.html").replace("category-1", "category-3")
        assertTrue(provider.parseThreadmarks(html, root).isEmpty())
        val emptyMain = "<div class='block-body--threadmarkBody category-1'></div>$html"
        assertTrue(provider.parseThreadmarks(emptyMain, root).isEmpty())
    }

    @Test
    fun malformedPagesFailInsteadOfReturningAnEmptyStory() {
        assertTrue(runCatching { provider.parseMetadata("<html>Log in</html>") }.exceptionOrNull() is NetworkParseException)
        assertTrue(runCatching { provider.parseChapterContent("<html>Not a chapter</html>") }.exceptionOrNull() is NetworkParseException)
    }

    @Test
    fun fullAndLatestStoryLoadingUseMainCategoryWithoutSpaceBattlesLibraryRequests() =
        withServer { server, network ->
            val localRoot = server.url("/threads/fixture-story.12345/").toString()
            val story = fixture("story.html").replace(root, localRoot)
            server.enqueue(MockResponse().setBody(story))
            server.enqueue(MockResponse().setBody(fixture("threadmarks.rss")))
            server.enqueue(MockResponse().setBody(fixture("threadmarks.rss")))
            server.enqueue(MockResponse().setBody(fixture("threadmarks.html")))

            val loaded = provider.loadStory(localRoot, true, network)
            assertTrue(loaded.chaptersAreLatestOnly)
            assertEquals(listOf("sv_7001", "sv_7002"), loaded.chapters.map { it.id })
            assertEquals(loaded.chapters.map { it.id }, loaded.loadFullChapterList().map { it.id })
            assertEquals(Instant.parse("2026-01-02T12:00:00Z").toEpochMilli(), loaded.metadata.sourceMetadata.updatedAt)
            assertEquals(4, server.requestCount)
            val paths = List(4) { server.takeRequest().path!! }
            assertTrue(paths.drop(1).all { "threadmark_category=1" in it })
            assertFalse(paths.any { "/library/" in it })
        }

    @Test
    fun fullSyncPaginatesAndDeduplicatesBoundaryPosts() =
        withServer { server, network ->
            val localRoot = server.url("/threads/fixture-story.12345/").toString()
            val first = fixture("threadmarks.html") + "<div class='threadmarks-pagenav--wrapper category-1'><a href='?page=2'>2</a></div>"
            server.enqueue(MockResponse().setBody(first))
            server.enqueue(MockResponse().setBody(fixture("threadmarks.html").replace("7002", "7003")))

            val chapters = provider.getChapterList("", localRoot, network)
            assertEquals(listOf("sv_7001", "sv_7002", "sv_7003"), chapters.map { it.id })
            assertEquals(2, server.requestCount)
            server.takeRequest()
            assertTrue(server.takeRequest().path!!.contains("page=2"))
        }

    @Test
    fun incompleteAndEmptyIndexesFailWithoutSilentlyRemovingChapters() =
        withServer { server, network ->
            val localRoot = server.url("/threads/fixture-story.12345/").toString()
            server.enqueue(
                MockResponse().setBody(fixture("threadmarks.html") + "<div class='pageNav-main'><a href='?page=501'>501</a></div>"),
            )
            assertTrue(
                runCatching { provider.getChapterList("", localRoot, network) }.exceptionOrNull() is SourceChapterListIncompleteException,
            )
            server.enqueue(MockResponse().setBody("<html>Not found</html>"))
            assertTrue(runCatching { provider.getChapterList("", localRoot, network) }.exceptionOrNull() is NetworkParseException)
        }

    @Test
    fun missingMainChaptersOnALaterPageRejectTheFullSync() =
        withServer { server, network ->
            val localRoot = server.url("/threads/fixture-story.12345/").toString()
            val first = fixture("threadmarks.html") + "<div class='pageNav-main'><a href='?page=2'>2</a></div>"
            server.enqueue(MockResponse().setBody(first))
            server.enqueue(MockResponse().setBody("<html>Log in</html>"))
            assertTrue(
                runCatching { provider.getChapterList("", localRoot, network) }.exceptionOrNull() is SourceChapterListIncompleteException,
            )
        }

    @Test
    fun downloadsLocateTheExactPostAndReuseTheReaderPage() =
        withServer { server, network ->
            val localRoot = server.url("/threads/fixture-story.12345/").toString()
            server.enqueue(MockResponse().setBody(fixture("chapter.html")))
            val first = provider.fetchChapterContent(localRoot, chapter(server, 7001), 0, network)
            val second = provider.fetchChapterContent(localRoot, chapter(server, 7002), 1, network)

            assertTrue(first.contains("Fixture chapter 1 begins"))
            assertFalse(first.contains("Fixture chapter 2 begins"))
            assertTrue(second.contains("Fixture chapter 2 begins"))
            assertFalse(second.contains("removeMe"))
            assertTrue(second.contains("${server.url("/")}data/attachments/fixture.jpg"))
            assertEquals(1, server.requestCount)
        }

    @Test
    fun missingReaderPostFallsBackToItsDirectPostUrl() =
        withServer { server, network ->
            val localRoot = server.url("/threads/fixture-story.12345/").toString()
            repeat(3) { server.enqueue(MockResponse().setResponseCode(404)) }
            server.enqueue(MockResponse().setBody(fixture("chapter.html")))

            val content = provider.fetchChapterContent(localRoot, chapter(server, 7002), 0, network)
            assertTrue(content.contains("Fixture chapter 2 begins"))
            assertEquals(4, server.requestCount)
            repeat(3) { server.takeRequest() }
            assertEquals("/posts/7002/", server.takeRequest().path)
        }

    private fun chapter(
        server: MockWebServer,
        postId: Int,
    ): Chapter = Chapter(id = "sv_$postId", url = server.url("/posts/$postId/").toString())

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/fixtures/sufficient_velocity/$name")!!.bufferedReader().use { it.readText() }

    private fun withServer(test: suspend (MockWebServer, NetworkClient) -> Unit) =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                test(server, NetworkClient())
            } finally {
                server.shutdown()
            }
        }
}
