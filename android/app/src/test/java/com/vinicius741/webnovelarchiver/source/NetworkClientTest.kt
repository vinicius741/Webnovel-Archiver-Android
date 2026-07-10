package com.vinicius741.webnovelarchiver.source

import com.vinicius741.webnovelarchiver.source.network.HttpNetworkException
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import com.vinicius741.webnovelarchiver.source.network.NetworkOfflineException
import com.vinicius741.webnovelarchiver.source.network.NetworkPolicyResolver
import com.vinicius741.webnovelarchiver.source.network.NetworkTimeoutException
import com.vinicius741.webnovelarchiver.source.network.RateLimitNetworkException
import com.vinicius741.webnovelarchiver.source.network.SourceAccessBlockedException
import com.vinicius741.webnovelarchiver.source.network.SourceNetworkPolicy
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * MockWebServer tests for [NetworkClient] (R6 + Test Recommendations: Network). Covers retry
 * behavior on 429, the cover-size cap, and non-image rejection.
 */
class NetworkClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: NetworkClient

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        client = NetworkClient()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test
    fun fetchReturnsBodyForSuccessfulResponse() =
        runBlocking {
            server.enqueue(MockResponse().setBody("<html>hello</html>"))
            val body = client.fetch(server.url("/page").toString())
            assertEquals("<html>hello</html>", body)
        }

    @Test
    fun fetchThrowsOnNonRetryableHttpError() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(404).setBody("nope"))
            var threw = false
            try {
                client.fetch(server.url("/missing").toString())
            } catch (error: Throwable) {
                threw = true
                assertTrue(error is HttpNetworkException)
                assertTrue(error.message!!.contains("HTTP 404"))
            }
            assertTrue(threw)
        }

    @Test
    fun fetchThrowsSourceBlockedForCloudflareChallenge() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(403)
                    .setHeader("cf-mitigated", "challenge")
                    .setBody("<html><head><title>Just a moment...</title></head></html>"),
            )

            val result = runCatching { client.fetch(server.url("/protected").toString()) }

            assertTrue(result.exceptionOrNull() is SourceAccessBlockedException)
        }

    @Test
    fun fetchDoesNotFlagChapterProseAsSourceBlocked() =
        runBlocking {
            // Regression guard: chapter prose containing the content-prone marker phrase must NOT
            // be misclassified as a Cloudflare challenge (no Cloudflare header signal present).
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        "<html><body><p>Please enable javascript and cookies to continue " +
                            "reading this wonderful story. It was a dark and stormy night...</p></body></html>",
                    ),
            )

            val body = client.fetch(server.url("/chapter").toString())

            assertTrue(body.contains("dark and stormy night"))
        }

    @Test
    fun fetchFlagsCloudflareInterstitialOnSuccessStatus() =
        runBlocking {
            // A Cloudflare "Just a moment..." interstitial can return HTTP 200 with the challenge
            // body; the corroboration (server: cloudflare + markers) must still flag it.
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("server", "cloudflare")
                    .setBody("<html><head><title>Just a moment...</title></head></html>"),
            )

            val result = runCatching { client.fetch(server.url("/interstitial").toString()) }

            assertTrue(result.exceptionOrNull() is SourceAccessBlockedException)
        }

    @Test
    fun fetchHonorsPerCallTimeout() =
        runBlocking {
            server.enqueue(MockResponse().setBody("slow").setBodyDelay(250, TimeUnit.MILLISECONDS))
            val result = runCatching { client.fetch(server.url("/slow").toString(), callTimeoutMillis = 25) }

            assertTrue(result.exceptionOrNull() is NetworkTimeoutException)
        }

    @Test
    fun fetchTreatsPlain403AsNonRetryableHttpError() =
        runBlocking {
            // Default host policy is generic: an ordinary 403 is typed HTTP failure, not a
            // Cloudflare block or a rate limit, and must not consume a second queued response.
            server.enqueue(MockResponse().setResponseCode(403))
            val error = runCatching { client.fetch(server.url("/rate").toString()) }.exceptionOrNull()
            assertTrue(error is HttpNetworkException)
            assertEquals(403, (error as HttpNetworkException).statusCode)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun blockingCallAndBodyReadUseInjectedIoDispatcher() =
        runBlocking {
            val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "network-test-io") }
            val dispatcher = executor.asCoroutineDispatcher()
            try {
                var interceptorThread = ""
                val recordingClient =
                    OkHttpClient
                        .Builder()
                        .addInterceptor { chain ->
                            interceptorThread = Thread.currentThread().name
                            chain.proceed(chain.request())
                        }.build()
                client = NetworkClient(client = recordingClient, ioDispatcher = dispatcher)
                server.enqueue(MockResponse().setBody("ok"))

                assertEquals("ok", client.fetch(server.url("/thread").toString()))
                assertTrue(interceptorThread.startsWith("network-test-io"))
            } finally {
                dispatcher.close()
                executor.shutdownNow()
            }
        }

    @Test
    fun injectedScribbleHubPolicyRetries403And429AgainstMockWebServer() =
        runBlocking {
            client = retryingClient()
            server.enqueue(MockResponse().setResponseCode(403).setBody("ordinary forbidden"))
            server.enqueue(MockResponse().setBody("after 403"))
            server.enqueue(MockResponse().setResponseCode(429))
            server.enqueue(MockResponse().setBody("after 429"))

            assertEquals("after 403", client.fetch(server.url("/first").toString()))
            assertEquals("after 429", client.fetch(server.url("/second").toString()))
            assertEquals(4, server.requestCount)
        }

    @Test
    fun retryAfterIsBoundedAndJittered() =
        runBlocking {
            val sleeps = mutableListOf<Long>()
            client =
                NetworkClient(
                    policyResolver =
                        NetworkPolicyResolver {
                            SourceNetworkPolicy(
                                maximumAttempts = 2,
                                retryableStatusCodes = setOf(429),
                                maximumRetryDelayMillis = 2_000L,
                                maximumRetryAfterMillis = 1_000L,
                                maximumJitterMillis = 100L,
                            )
                        },
                    sleep = { sleeps += it },
                    jitterMillis = { 100L },
                )
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30"))
            server.enqueue(MockResponse().setBody("ok"))

            assertEquals("ok", client.fetch(server.url("/bounded").toString()))
            assertEquals(listOf(1_100L), sleeps)
        }

    @Test
    fun exhaustedPolicyThrowsTypedRateLimitError() =
        runBlocking {
            client = retryingClient(maximumAttempts = 2)
            server.enqueue(MockResponse().setResponseCode(429))
            server.enqueue(MockResponse().setResponseCode(429))

            val error = runCatching { client.fetch(server.url("/limited").toString()) }.exceptionOrNull()

            assertTrue(error is RateLimitNetworkException)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun perHostGapSleepsOutsideClaimLoopUsingInjectedClock() =
        runBlocking {
            var now = 1_000L
            val sleeps = mutableListOf<Long>()
            client =
                NetworkClient(
                    policyResolver =
                        NetworkPolicyResolver {
                            SourceNetworkPolicy(
                                maximumAttempts = 1,
                                minimumRequestGapMillis = 250L,
                                maximumJitterMillis = 0L,
                            )
                        },
                    sleep = { delayMs ->
                        sleeps += delayMs
                        now += delayMs
                    },
                    nowMillis = { now },
                    jitterMillis = { 0L },
                )
            server.enqueue(MockResponse().setBody("first"))
            server.enqueue(MockResponse().setBody("second"))

            assertEquals("first", client.fetch(server.url("/a").toString()))
            assertEquals("second", client.fetch(server.url("/b").toString()))

            // First request claims immediately; second measures remaining gap then sleeps outside the lock.
            assertEquals(listOf(250L), sleeps)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun unknownHostIsClassifiedAsOffline() =
        runBlocking {
            val throwingClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor { throw UnknownHostException("offline.test") }
                    .build()
            client = NetworkClient(client = throwingClient)

            val error = runCatching { client.fetch("https://offline.test/chapter") }.exceptionOrNull()

            assertTrue(error is NetworkOfflineException)
            assertEquals("offline.test", error?.message)
        }

    @Test
    fun fetchBytesReturnsBytesForImageContent() =
        runBlocking {
            val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "image/png")
                    .setBody(okio.Buffer().write(png)),
            )
            val bytes = client.fetchBytes(server.url("/cover.png").toString())
            assertNotNull(bytes)
            assertTrue(bytes!!.contentEquals(png))
        }

    @Test
    fun fetchBytesRejectsNonImageContentType() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/html")
                    .setBody("<p>not an image</p>"),
            )
            assertNull(client.fetchBytes(server.url("/sneaky").toString()))
        }

    @Test
    fun fetchBytesReturnsNullOnErrorStatus() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(500))
            assertNull(client.fetchBytes(server.url("/broken").toString()))
        }

    @Test
    fun fetchBytesReturnsNullOnSocketFailure() =
        runBlocking {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            assertNull(client.fetchBytes(server.url("/dead").toString()))
        }

    @Test
    fun fetchBytesRejectsOversizeImage() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "image/png")
                    .setBody(okio.Buffer().write(ByteArray(33))),
            )

            assertNull(client.fetchBytes(server.url("/large.png").toString(), maxBytes = 32L))
        }

    private fun retryingClient(maximumAttempts: Int = 3): NetworkClient =
        NetworkClient(
            policyResolver =
                NetworkPolicyResolver {
                    SourceNetworkPolicy(
                        maximumAttempts = maximumAttempts,
                        retryableStatusCodes = setOf(403, 429),
                        baseRetryDelayMillis = 0L,
                        maximumJitterMillis = 0L,
                    )
                },
            sleep = {},
            jitterMillis = { 0L },
        )
}
