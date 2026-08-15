package com.vinicius741.webnovelarchiver.source.network

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class CloudflareBypassInterceptorTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test
    fun challengeUsesRenderedBrowserPageWithoutRetryingThroughOkHttp() {
        server.enqueue(cloudflareChallenge())
        var renderedRequest: CloudflareWebViewRequest? = null
        val client =
            clientWithRenderer { request ->
                renderedRequest = request
                CloudflareRenderOutcome.Rendered(
                    CloudflareRenderedPage("<html><body>browser result</body></html>", request.url),
                )
            }
        val request =
            Request
                .Builder()
                .url(server.url("/protected"))
                .header("User-Agent", "test-browser")
                .header("Accept-Language", "en-US")
                .build()

        client.newCall(request).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("<html><body>browser result</body></html>", response.body?.string())
        }

        assertEquals(1, server.requestCount)
        assertEquals("GET", renderedRequest?.method)
        assertEquals("test-browser", renderedRequest?.userAgent)
        assertNull(renderedRequest?.postData)
        assertFalse(renderedRequest?.headers?.containsKey("User-Agent") == true)
        assertEquals("en-US", renderedRequest?.headers?.get("Accept-Language"))
    }

    @Test
    fun formPostIsForwardedToBrowserRenderer() {
        server.enqueue(cloudflareChallenge())
        var renderedRequest: CloudflareWebViewRequest? = null
        val client =
            clientWithRenderer { request ->
                renderedRequest = request
                CloudflareRenderOutcome.Rendered(
                    CloudflareRenderedPage("<html><body><li>chapter</li></body></html>", request.url),
                )
            }
        val request =
            Request
                .Builder()
                .url(server.url("/wp-admin/admin-ajax.php"))
                .post(
                    FormBody
                        .Builder()
                        .add("action", "wi_gettocchp")
                        .add("pagenum", "2")
                        .build(),
                ).build()

        client.newCall(request).execute().close()

        assertEquals("POST", renderedRequest?.method)
        val postData = renderedRequest?.postData?.toString(Charsets.UTF_8).orEmpty()
        assertTrue(postData.contains("action=wi_gettocchp"))
        assertTrue(postData.contains("pagenum=2"))
    }

    @Test
    fun failedBrowserRenderPreservesOriginalChallenge() {
        server.enqueue(cloudflareChallenge())
        val client = clientWithRenderer { CloudflareRenderOutcome.NeedsManualVerification(CloudflareRenderFailure.ChallengeActive) }

        client
            .newCall(Request.Builder().url(server.url("/protected")).build())
            .execute()
            .use { response ->
                assertEquals(403, response.code)
                assertEquals("challenge", response.header("cf-mitigated"))
            }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun needsManualVerificationOpensTheCircuit() {
        server.enqueue(cloudflareChallenge())
        val reliability = SourceReliabilityCoordinator()
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor(
                    CloudflareBypassInterceptor(
                        CloudflarePageRenderer {
                            CloudflareRenderOutcome.NeedsManualVerification(CloudflareRenderFailure.ChallengeActive)
                        },
                        reliability,
                    ),
                ).build()

        client.newCall(Request.Builder().url(server.url("/protected")).build()).execute().close()

        assertTrue(reliability.isManualVerificationRequired(server.hostName))
    }

    @Test
    fun pageContentUnexpectedReturnsHtmlForTheParser() {
        server.enqueue(cloudflareChallenge())
        val client =
            clientWithRenderer {
                CloudflareRenderOutcome.PageContentUnexpected(
                    CloudflareRenderedPage("<html><body>settled but wrong shape</body></html>", it.url),
                )
            }

        client.newCall(Request.Builder().url(server.url("/chapter/9")).build()).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals(
                "settled but wrong shape",
                response.body
                    ?.string()
                    ?.substringAfter("<body>")
                    ?.substringBefore("</body>"),
            )
            assertEquals("1", response.header(CloudflareBypassInterceptor.BROWSER_RENDERED_HEADER))
        }
    }

    @Test
    fun originHttpErrorSurfacesTheStatusForPerJobFailure() {
        server.enqueue(cloudflareChallenge())
        val client = clientWithRenderer { CloudflareRenderOutcome.OriginHttpError(404) }

        client.newCall(Request.Builder().url(server.url("/chapter/removed")).build()).execute().use { response ->
            assertEquals(404, response.code)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun transportErrorThrowsRetryableIoException() {
        server.enqueue(cloudflareChallenge())
        val client = clientWithRenderer { CloudflareRenderOutcome.TransportError }

        val error =
            runCatching {
                client.newCall(Request.Builder().url(server.url("/chapter/1")).build()).execute()
            }.exceptionOrNull()

        assertTrue(error is IOException)
    }

    @Test
    fun normalResponseNeverStartsBrowserRenderer() {
        server.enqueue(MockResponse().setBody("normal"))
        var rendererCalled = false
        val client =
            clientWithRenderer {
                rendererCalled = true
                CloudflareRenderOutcome.Rendered(CloudflareRenderedPage("<html></html>", it.url))
            }

        client
            .newCall(Request.Builder().url(server.url("/normal")).build())
            .execute()
            .close()

        assertFalse(rendererCalled)
    }

    @Test
    fun authoritativeChallengeHeaderUsesRendererEvenOnStatus200() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("cf-mitigated", "challenge")
                .setBody("<html><title>Just a moment...</title></html>"),
        )
        val client =
            clientWithRenderer {
                CloudflareRenderOutcome.Rendered(CloudflareRenderedPage("<html><body>browser</body></html>", it.url))
            }

        client.newCall(Request.Builder().url(server.url("/protected")).build()).execute().use { response ->
            assertEquals(
                "browser",
                response.body
                    ?.string()
                    ?.substringAfter("<body>")
                    ?.substringBefore("</body>"),
            )
            assertEquals("1", response.header(CloudflareBypassInterceptor.BROWSER_RENDERED_HEADER))
        }
    }

    @Test
    fun activeBrowserTransportSkipsRejectedOkHttpProbe() {
        val reliability = SourceReliabilityCoordinator()
        reliability.recordChallengeDetected(server.hostName)
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor(
                    CloudflareBypassInterceptor(
                        CloudflarePageRenderer {
                            CloudflareRenderOutcome.Rendered(
                                CloudflareRenderedPage("<html><body>sticky browser</body></html>", it.url),
                            )
                        },
                        reliability,
                    ),
                ).build()

        client.newCall(Request.Builder().url(server.url("/chapter")).build()).execute().close()

        assertEquals(0, server.requestCount)
    }

    @Test
    fun stickyTransportOriginErrorSurfacesStatusWithoutOkHttpProbe() {
        val reliability = SourceReliabilityCoordinator()
        reliability.recordChallengeDetected(server.hostName)
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor(
                    CloudflareBypassInterceptor(
                        CloudflarePageRenderer { CloudflareRenderOutcome.OriginHttpError(410) },
                        reliability,
                    ),
                ).build()

        client.newCall(Request.Builder().url(server.url("/chapter/gone")).build()).execute().use { response ->
            assertEquals(410, response.code)
        }
        assertEquals(0, server.requestCount)
    }

    private fun clientWithRenderer(render: (CloudflareWebViewRequest) -> CloudflareRenderOutcome): OkHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor(CloudflareBypassInterceptor(CloudflarePageRenderer(render)))
            .build()

    private fun cloudflareChallenge(): MockResponse =
        MockResponse()
            .setResponseCode(403)
            .setHeader("cf-mitigated", "challenge")
            .setHeader("server", "cloudflare")
            .setBody("<html><head><title>Just a moment...</title></head></html>")
}
