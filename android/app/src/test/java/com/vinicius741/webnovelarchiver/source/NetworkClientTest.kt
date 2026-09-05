package com.vinicius741.webnovelarchiver.source

import com.vinicius741.webnovelarchiver.source.network.HttpNetworkException
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import com.vinicius741.webnovelarchiver.source.network.NetworkOfflineException
import com.vinicius741.webnovelarchiver.source.network.NetworkPolicyResolver
import com.vinicius741.webnovelarchiver.source.network.NetworkRequestGate
import com.vinicius741.webnovelarchiver.source.network.NetworkTimeoutException
import com.vinicius741.webnovelarchiver.source.network.RateLimitNetworkException
import com.vinicius741.webnovelarchiver.source.network.SourceAccessBlockedException
import com.vinicius741.webnovelarchiver.source.network.SourceNetworkPolicy
import com.vinicius741.webnovelarchiver.source.network.fetchBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
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
import java.io.IOException
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
    fun reusablePageCoalescesConcurrentRequestsForTheSameReaderPage() =
        runBlocking {
            server.enqueue(MockResponse().setBody("<html>reader batch</html>").setBodyDelay(100, TimeUnit.MILLISECONDS))
            val url = server.url("/reader/page-1").toString()

            val bodies =
                listOf(
                    async { client.fetchReusablePage(url) },
                    async { client.fetchReusablePage(url) },
                    async { client.fetchReusablePage(url) },
                ).awaitAll()

            assertEquals(List(3) { "<html>reader batch</html>" }, bodies)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun reusablePageDoesNotCacheBodiesRejectedByTheCaller() =
        runBlocking {
            val url = server.url("/reader/page-1").toString()
            val isReaderPage: (String) -> Boolean = { it.contains("message--post") }
            server.enqueue(MockResponse().setBody("<html>temporary login wall</html>"))
            server.enqueue(MockResponse().setBody("<article class=\"message--post\">chapter</article>"))

            assertEquals(
                "<html>temporary login wall</html>",
                client.fetchReusablePage(url, cacheValidator = isReaderPage),
            )
            val valid = client.fetchReusablePage(url, cacheValidator = isReaderPage)
            val cached = client.fetchReusablePage(url, cacheValidator = isReaderPage)

            assertEquals("<article class=\"message--post\">chapter</article>", valid)
            assertEquals(valid, cached)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun reusablePageTtlStartsAfterRequestGateCompletes() =
        runBlocking {
            var now = 0L
            var gateCalls = 0
            client = NetworkClient(nowMillis = { now })
            val gate =
                NetworkRequestGate { claimSourcePermission ->
                    gateCalls += 1
                    now = 1_000L
                    claimSourcePermission()
                }
            val url = server.url("/reader/page-1").toString()
            server.enqueue(MockResponse().setBody("<html>reader batch</html>"))

            val first = client.fetchReusablePage(url, ttlMillis = 500L, requestGate = gate)
            val cached = client.fetchReusablePage(url, ttlMillis = 500L, requestGate = gate)

            assertEquals(first, cached)
            assertEquals(1, gateCalls)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun preparedPageBypassesGateOnlyWhileTheCachedResponseExists() =
        runBlocking {
            val firstUrl = server.url("/prepared").toString()
            val clearedUrl = server.url("/cleared").toString()
            var gateCalls = 0
            val gate =
                NetworkRequestGate { claimSourcePermission ->
                    gateCalls += 1
                    claimSourcePermission()
                }
            server.enqueue(MockResponse().setBody("prepared body"))
            server.enqueue(MockResponse().setBody("stale body"))
            server.enqueue(MockResponse().setBody("fresh body"))

            client.prepareBulkDownload(firstUrl, gate)
            assertEquals("prepared body", client.fetch(firstUrl, requestGate = gate))
            assertEquals(1, gateCalls)
            assertEquals(1, server.requestCount)

            client.prepareBulkDownload(clearedUrl, gate)
            client.onNetworkChanged()
            assertEquals("fresh body", client.fetch(clearedUrl, requestGate = gate))
            assertEquals(3, gateCalls)
            assertEquals(3, server.requestCount)
        }

    @Test
    fun fetchThrowsOnNonRetryableHttpError() =
        runBlocking {
            client = NetworkClient(policyResolver = NetworkPolicyResolver { SourceNetworkPolicy(maximumAttempts = 3) })
            server.enqueue(MockResponse().setResponseCode(404).setBody("nope"))
            server.enqueue(MockResponse().setBody("Unexpected retry"))
            var threw = false
            try {
                client.fetch(server.url("/missing").toString())
            } catch (error: Throwable) {
                threw = true
                assertTrue(error is HttpNetworkException)
                assertTrue(error.message!!.contains("HTTP 404"))
            }
            assertTrue(threw)
            assertEquals(1, server.requestCount)
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
    fun interceptorThrownSourceBlockedCrossesTheAsyncBoundaryTyped() =
        runBlocking {
            // Regression guard: executeCancellable runs calls through enqueue, which wraps a
            // non-IOException interceptor failure in a generic IOException and rethrows the
            // original on OkHttp's dispatcher thread. The typed exception must stay an IOException
            // so the catch sites (executeAttempt, download/sync failure planning) still see it.
            val throwingClient =
                NetworkClient(
                    client =
                        OkHttpClient
                            .Builder()
                            .addInterceptor { _ -> throw SourceAccessBlockedException(server.url("/blocked").toString()) }
                            .build(),
                )

            val result = runCatching { throwingClient.fetch(server.url("/blocked").toString()) }

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
    fun cancellingTheCoroutineCancelsTheUnderlyingCall() =
        runBlocking {
            // R13: cancellation must reach the in-flight OkHttp call so a dead operation stops
            // occupying a worker. The interceptor observes chain.call().isCanceled() flipping.
            val sawCancel = java.util.concurrent.CountDownLatch(1)
            val recordingClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        try {
                            while (!chain.call().isCanceled()) Thread.sleep(5)
                            sawCancel.countDown()
                        } catch (_: InterruptedException) {
                        }
                        throw IOException("cancelled under test")
                    }.build()
            client = NetworkClient(client = recordingClient)
            val url = server.url("/stalled").toString()

            val job =
                launch(Dispatchers.IO) {
                    runCatching { client.fetch(url, maximumAttemptsOverride = 1) }
                }
            Thread.sleep(100)
            job.cancelAndJoin()

            assertTrue("coroutine cancellation never reached the OkHttp call", sawCancel.await(2, TimeUnit.SECONDS))
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
    fun serverRetryAfterBeyondTheBackoffBudgetDefersInsteadOfRetryingEarly() =
        runBlocking {
            // R14: an accepted Retry-After longer than the ordinary backoff cap must not be clamped
            // into an early retry; the operation defers with a typed rate-limit error.
            client =
                NetworkClient(
                    policyResolver =
                        NetworkPolicyResolver {
                            SourceNetworkPolicy(
                                maximumAttempts = 3,
                                retryableStatusCodes = setOf(429),
                                maximumRetryDelayMillis = 2_000L,
                                maximumRetryAfterMillis = 60_000L,
                                maximumJitterMillis = 0L,
                            )
                        },
                )
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30"))

            val error = runCatching { client.fetch(server.url("/deferred").toString()) }.exceptionOrNull()

            assertTrue(error is RateLimitNetworkException)
            assertEquals(30_000L, (error as RateLimitNetworkException).retryAfterMillis)
            // Deferred, not retried: the server saw exactly the one rejected request.
            assertEquals(1, server.requestCount)
        }

    @Test
    fun serverRetryAfterWithinTheBackoffBudgetIsSleptAndRetried() =
        runBlocking {
            client =
                NetworkClient(
                    policyResolver =
                        NetworkPolicyResolver {
                            SourceNetworkPolicy(
                                maximumAttempts = 2,
                                retryableStatusCodes = setOf(429),
                                baseRetryDelayMillis = 0L,
                                maximumRetryDelayMillis = 2_000L,
                                maximumRetryAfterMillis = 1_000L,
                                maximumJitterMillis = 0L,
                                minimumRequestGapMillis = 0L,
                            )
                        },
                )
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1"))
            server.enqueue(MockResponse().setBody("ok"))

            assertEquals("ok", client.fetch(server.url("/within-budget").toString()))
            assertEquals(2, server.requestCount)
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
                        // R14: retryable responses now record a host cooldown on arrival; zero
                        // caps keep these tests' retries immediate instead of wall-clock waits.
                        maximumRetryDelayMillis = 0L,
                        maximumRetryAfterMillis = 0L,
                        maximumJitterMillis = 0L,
                        maximumAdaptiveGapMillis = 0L,
                    )
                },
            sleep = {},
            jitterMillis = { 0L },
        )
}
