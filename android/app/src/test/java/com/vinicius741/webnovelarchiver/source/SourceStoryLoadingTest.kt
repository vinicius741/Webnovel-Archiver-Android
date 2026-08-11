package com.vinicius741.webnovelarchiver.source

import com.vinicius741.webnovelarchiver.domain.model.ChapterInfo
import com.vinicius741.webnovelarchiver.domain.model.NovelMetadata
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceStoryLoadingTest {
    @Test
    fun inheritedLoaderReusesFetchedDocumentForLatestToFullFallback() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(MockResponse().setBody("<html><h1>Story</h1></html>"))
                val provider = fixtureProvider()
                val progress = mutableListOf<String>()

                val loaded =
                    provider.loadStory(
                        url = server.url("/story").toString(),
                        preferLatestChapters = true,
                        network = NetworkClient(),
                        progress = progress::add,
                    )

                assertEquals("Story", loaded.metadata.title)
                assertTrue(loaded.chaptersAreLatestOnly)
                assertEquals(listOf("latest"), loaded.chapters.map { it.id })
                assertEquals(listOf("full"), loaded.loadFullChapterList().map { it.id })
                assertEquals(1, server.requestCount)
                assertTrue("Parsing chapters..." in progress)
            } finally {
                server.shutdown()
            }
        }

    private fun fixtureProvider(): SourceProvider =
        object : SourceProvider {
            override val descriptor =
                SourceDescriptor(
                    id = "fixture_source",
                    displayName = "Fixture Source",
                    browseUrl = "https://fixture.test",
                    hosts = setOf("fixture.test"),
                    capabilities = SourceCapabilities(latestChapterSync = true),
                )

            override fun classifyUrl(url: String): SourceUrlKind? = SourceUrlKind.STORY

            override fun getStoryId(url: String): String = "fixture_story"

            override fun getChapterId(url: String): String? = url.substringAfterLast('/')

            override fun parseMetadata(html: String): NovelMetadata = NovelMetadata(title = "Story")

            override suspend fun getChapterList(
                html: String,
                url: String,
                network: NetworkClient,
                progress: (String) -> Unit,
            ): List<ChapterInfo> = listOf(ChapterInfo(id = "full", title = "Full", url = "$url/full"))

            override suspend fun getLatestChapterList(
                html: String,
                url: String,
                network: NetworkClient,
                progress: (String) -> Unit,
            ): List<ChapterInfo> = listOf(ChapterInfo(id = "latest", title = "Latest", url = "$url/latest"))

            override fun parseChapterContent(html: String): String = html
        }
}
