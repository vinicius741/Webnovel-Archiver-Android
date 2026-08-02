package com.vinicius741.webnovelarchiver.source

import com.vinicius741.webnovelarchiver.domain.model.SourceMetricKind
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

class ScribbleHubProviderTest {
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
    fun enrichMetadataFetchesCanonicalStatsPageOnceAndKeepsBaseMetadata() =
        runBlocking {
            val canonical = server.url("/series/98765/the-scribbled-saga").toString()
            val base =
                ScribbleHubProvider
                    .parseMetadata("<html><body><h1 class=fic_title>Base title</h1></body></html>")
                    .copy(canonicalUrl = canonical)
            val stats =
                javaClass
                    .getResourceAsStream("/fixtures/scribblehub/stats.html")!!
                    .bufferedReader()
                    .use { it.readText() }
            server.enqueue(MockResponse().setBody(stats))

            val enriched = ScribbleHubProvider.enrichMetadata(base, "", canonical, network)

            assertEquals("Base title", enriched.title)
            assertEquals(canonical, enriched.canonicalUrl)
            assertEquals(
                6_200L,
                enriched.sourceMetadata.metrics
                    .first { it.kind == SourceMetricKind.READERS }
                    .value,
            )
            assertEquals(1, server.requestCount)
            assertEquals("/series/98765/the-scribbled-saga/stats/", server.takeRequest().path)
        }

    @Test
    fun statsFailureReturnsBaseMetadataWithoutBlockingImport() =
        runBlocking {
            val canonical = server.url("/series/98765/the-scribbled-saga").toString()
            val base =
                ScribbleHubProvider
                    .parseMetadata("<html><body><h1 class=fic_title>Base title</h1></body></html>")
                    .copy(canonicalUrl = canonical)
            server.enqueue(MockResponse().setResponseCode(500))

            val enriched = ScribbleHubProvider.enrichMetadata(base, "", canonical, network)

            assertSame(base, enriched)
            assertEquals(1, server.requestCount)
        }
}
