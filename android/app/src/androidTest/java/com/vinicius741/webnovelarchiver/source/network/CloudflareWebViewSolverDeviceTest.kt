package com.vinicius741.webnovelarchiver.source.network

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Drives the real hidden WebView against a loopback server; nothing here leaves the device. */
class CloudflareWebViewSolverDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var pages: LocalPages

    @Before fun setUp() {
        pages = LocalPages()
    }

    @After fun tearDown() {
        CloudflareWebViewSolver.destroySessions()
        pages.close()
    }

    @Test
    fun hiddenWebViewRendersWithARealViewport() {
        val outcome = CloudflareWebViewSolver.render(context, request("/page"))

        val html = (outcome as CloudflareRenderOutcome.Rendered).page.html
        val width =
            Regex("data-viewport-width=\"(\\d+)\"")
                .find(html)
                ?.groupValues
                ?.get(1)
                ?.toInt()
        assertTrue("the page saw a viewport width of $width", width != null && width > 0)
        assertTrue("the page's content was not visible in the viewport", html.contains("data-visible=\"true\""))
    }

    @Test
    fun aPageThatNeverArrivesIsAStallAndTheNextRenderStartsFresh() {
        // A first page gives the session a document, as during a download run.
        assertTrue(CloudflareWebViewSolver.render(context, request("/page")) is CloudflareRenderOutcome.Rendered)

        val stalled = CloudflareWebViewSolver.render(context, request("/hang"), timeoutMs = 3_000)

        assertEquals(CloudflareRenderOutcome.Stalled(CloudflareRenderFailure.TimedOut), stalled)
        assertTrue(CloudflareWebViewSolver.render(context, request("/page")) is CloudflareRenderOutcome.Rendered)
    }

    private fun request(path: String) =
        CloudflareWebViewRequest(
            url = "http://localhost:${pages.port}$path",
            method = "GET",
            userAgent = "",
            headers = emptyMap(),
            postData = null,
        )

    /** Answers every path with one page, except `/hang`, whose request is read and never answered. */
    private class LocalPages : Closeable {
        private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        private val clients = CopyOnWriteArrayList<Socket>()
        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    clients += client
                    thread(isDaemon = true) { runCatching { answer(client) } }
                }
            }
        }

        private fun answer(client: Socket) {
            val reader = client.getInputStream().bufferedReader()
            val path = reader.readLine()?.split(" ")?.getOrNull(1) ?: return
            while (!reader.readLine().isNullOrEmpty()) Unit
            if (path.startsWith("/hang")) return
            val body = PAGE.toByteArray()
            client.getOutputStream().apply {
                write(
                    (
                        "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                    ).toByteArray(),
                )
                write(body)
                flush()
            }
            client.close()
        }

        override fun close() {
            server.close()
            clients.forEach { runCatching { it.close() } }
        }
    }

    private companion object {
        const val PAGE =
            "<!doctype html><html><head><title>Local page</title></head><body>" +
                "<p id=\"content\">Readable page</p><script>" +
                "document.body.setAttribute('data-viewport-width', String(innerWidth));" +
                "new IntersectionObserver(function(entries){" +
                "document.body.setAttribute('data-visible', String(entries[entries.length-1].isIntersecting));" +
                "}).observe(document.getElementById('content'));" +
                "</script></body></html>"
    }
}
