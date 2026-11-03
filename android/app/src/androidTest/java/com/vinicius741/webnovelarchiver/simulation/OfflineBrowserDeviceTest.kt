package com.vinicius741.webnovelarchiver.simulation

import android.content.Context
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vinicius741.webnovelarchiver.app.DevEnvironment
import com.vinicius741.webnovelarchiver.data.storage.AppStorage
import com.vinicius741.webnovelarchiver.platform.WebViewSafety
import com.vinicius741.webnovelarchiver.source.network.CloudflareRenderOutcome
import com.vinicius741.webnovelarchiver.source.network.CloudflareWebViewRequest
import com.vinicius741.webnovelarchiver.source.network.CloudflareWebViewSolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Runs only in the instrumentation sandbox. No public host or provider credentials are used. */
@RunWith(AndroidJUnit4::class)
class OfflineBrowserDeviceTest {
    @Test fun downloadRetryCrossingItsDeadlineDuringProgressCannotStrandTheQueue() =
        kotlinx.coroutines.runBlocking {
            val base = ApplicationProvider.getApplicationContext<Context>()
            val scoped = SimulationContext(base, "scheduler-race-${System.nanoTime()}")
            val storage = AppStorage(scoped)
            var now = 1_000L
            var completed = 0
            val chapter =
                SimulationFixtures
                    .stories(6)
                    .first()
                    .chapters
                    .first()
            val job =
                com.vinicius741.webnovelarchiver.domain.model.DownloadJob(
                    id = "retry-test",
                    storyId = SimulationFixtures.STORY_ID,
                    sourceId = "royal_road",
                    chapter = chapter,
                    nextRetryAt = 1_005L,
                )
            storage.saveQueue(listOf(job))
            val wake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
            try {
                val loop =
                    com.vinicius741.webnovelarchiver.download.DownloadProcessLoop(
                        storage = storage,
                        wakeSignals = wake,
                        cleanupUnsupportedSourceJobs = {},
                        processJob = { active ->
                            completed += 1
                            storage.mutateQueueInPlace { jobs ->
                                jobs.onEach { if (it.id == active.id) it.status = "completed" }
                            }
                        },
                        publishQueueChanged = {},
                        // Reproduces a deadline expiring after eligibility selection and before wake planning.
                        emitProgress = { _, _ -> now = 1_010L },
                        nowMillis = { now },
                    )
                kotlinx.coroutines.withTimeout(3_000L) { loop.run() }
                assertEquals(1, completed)
                assertEquals("completed", storage.getQueue().single().status)
            } finally {
                wake.close()
                scoped.filesDir.parentFile?.deleteRecursively()
            }
        }

    @Test fun chromiumPostsFormsAndReturnsRawJsonFromALoopbackOrigin() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        LoopbackFixtureServer().use { server ->
            try {
                val form =
                    CloudflareWebViewSolver.render(
                        context,
                        CloudflareWebViewRequest("${server.origin}/form", "POST", "fixture-browser", emptyMap(), "page=1".toByteArray()),
                        timeoutMs = 15_000L,
                    )
                assertTrue("Form outcome: $form", form is CloudflareRenderOutcome.Rendered)
                assertEquals("<li>fixture chapter</li>0", (form as CloudflareRenderOutcome.Rendered).page.html)
                val json =
                    CloudflareWebViewSolver.render(
                        context,
                        CloudflareWebViewRequest(
                            "${server.origin}/json",
                            "GET",
                            "fixture-browser",
                            mapOf("Accept" to "application/json"),
                            null,
                            usesBrowserFetch = true,
                        ),
                        timeoutMs = 15_000L,
                    )
                assertTrue("JSON outcome: $json", json is CloudflareRenderOutcome.Rendered)
                assertEquals("{\"data\":[{\"id\":\"fixture\"}]}", (json as CloudflareRenderOutcome.Rendered).page.html)
                assertTrue(server.requests.any { it.startsWith("POST /form") })
                assertTrue(server.requests.any { it.startsWith("GET /json") })
            } finally {
                CloudflareWebViewSolver.destroySessions()
            }
        }
    }

    @Test fun simulationContextKeepsStorageAndRecoveryInsideItsSession() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val scoped = SimulationContext(base, "instrumentation-isolation")
        val storage = AppStorage(scoped)
        assertTrue(storage.root.canonicalPath.startsWith(scoped.filesDir.canonicalPath + "/"))
        assertFalse(storage.root.canonicalPath.startsWith(base.filesDir.canonicalPath + "/webnovel_archiver/"))
        assertTrue(storage.preRestoreSnapshotDir.canonicalPath.startsWith(scoped.filesDir.canonicalPath + "/"))
        assertTrue(storage.restoreRoot.canonicalPath.startsWith(scoped.cacheDir.canonicalPath + "/"))
        assertTrue(scoped.applicationContext === scoped)
    }

    @Test fun simulationDisablesNetworkLoadingInReaderWebViews() {
        val previous = DevEnvironment.requested
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            DevEnvironment.requested = true
            val web = WebView(ApplicationProvider.getApplicationContext())
            try {
                WebViewSafety.applyReaderSettings(web, enableTtsHighlight = true)
                assertTrue(web.settings.blockNetworkLoads)
                assertFalse(web.settings.allowFileAccess)
            } finally {
                WebViewSafety.destroy(web)
                DevEnvironment.requested = previous
            }
        }
    }
}

private class LoopbackFixtureServer : Closeable {
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    val origin = "http://127.0.0.1:${server.localPort}"
    val requests = CopyOnWriteArrayList<String>()
    private val worker =
        thread(isDaemon = true, name = "loopback-fixture") {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use {
                    it.soTimeout = 5_000
                    val reader = it.getInputStream().bufferedReader()
                    val request = reader.readLine() ?: return@use
                    requests += request
                    var length = 0
                    while (true) {
                        val header = reader.readLine().orEmpty()
                        if (header.isBlank()) break
                        if (header.startsWith("Content-Length:", ignoreCase = true)) length = header.substringAfter(':').trim().toInt()
                    }
                    repeat(length) { reader.read() }
                    val path = request.split(' ').getOrNull(1)
                    val body =
                        when (path) {
                            "/form" -> "<li>fixture chapter</li>0"
                            "/json" -> "{\"data\":[{\"id\":\"fixture\"}]}"
                            else -> "<!doctype html><html><body>Local fixture origin</body></html>"
                        }.toByteArray()
                    val type = if (path == "/json") "application/json" else "text/html"
                    val headers = "HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                    it.getOutputStream().apply {
                        write(headers.toByteArray())
                        write(body)
                        flush()
                    }
                }
            }
        }

    override fun close() {
        server.close()
        worker.join(2_000)
    }
}
