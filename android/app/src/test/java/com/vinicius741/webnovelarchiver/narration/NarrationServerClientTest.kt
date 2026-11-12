package com.vinicius741.webnovelarchiver.narration

import com.vinicius741.webnovelarchiver.tts.TtsWavWriter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import kotlin.concurrent.thread

class NarrationServerClientTest {
    private val server = ServerSocket(0)
    private val requests = mutableListOf<String>()

    /** Answers each connection with [respond]'s status, content type and body, like the Python server (HTTP/1.0, then close). */
    private fun serve(respond: (String) -> Triple<Int, String, ByteArray>) {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use {
                    val reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.ISO_8859_1))
                    val head = generateSequence { reader.readLine() }.takeWhile { line -> line.isNotEmpty() }.toList()
                    val length =
                        head
                            .firstOrNull { line -> line.startsWith("Content-Length:", true) }
                            ?.substringAfter(':')
                            ?.trim()
                            ?.toInt() ?: 0
                    val body = CharArray(length).also { chars -> reader.read(chars) }.concatToString()
                    synchronized(requests) { requests += head.joinToString("\n") + "\n\n" + body }
                    val (status, type, payload) = respond(head.first())
                    val out = it.getOutputStream()
                    out.write("HTTP/1.0 $status X\r\nContent-Type: $type\r\nContent-Length: ${payload.size}\r\n\r\n".toByteArray())
                    out.write(payload)
                    out.flush()
                }
            }
        }
    }

    private val connections =
        java.util.concurrent.atomic
            .AtomicInteger()

    /**
     * Like the Rust server: HTTP/1.1, several requests per connection. [closeAfter] requests on a
     * connection, it closes it without saying so (as a server does to an idle connection).
     */
    private fun serveKeepAlive(
        closeAfter: Int = Int.MAX_VALUE,
        respond: (String) -> Triple<Int, String, ByteArray>,
    ) {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                connections.incrementAndGet()
                thread(isDaemon = true) {
                    socket.use { connection ->
                        val reader = BufferedReader(InputStreamReader(connection.getInputStream(), Charsets.ISO_8859_1))
                        repeat(closeAfter) {
                            val head = generateSequence { reader.readLine() }.takeWhile { line -> line.isNotEmpty() }.toList()
                            if (head.isEmpty()) return@thread
                            val length =
                                head
                                    .firstOrNull { line -> line.startsWith("Content-Length:", true) }
                                    ?.substringAfter(':')
                                    ?.trim()
                                    ?.toInt() ?: 0
                            val body = CharArray(length).also { chars -> reader.read(chars) }.concatToString()
                            synchronized(requests) { requests += head.joinToString("\n") + "\n\n" + body }
                            val (status, type, payload) = respond(head.first())
                            val out = connection.getOutputStream()
                            val extra = if (status == 503) "Retry-After: 7\r\n" else "X-Slots: 2\r\n"
                            out.write(
                                "HTTP/1.1 $status X\r\nContent-Type: $type\r\nContent-Length: ${payload.size}\r\n${extra}Connection: keep-alive\r\n\r\n"
                                    .toByteArray(),
                            )
                            out.write(payload)
                            out.flush()
                        }
                    }
                }
            }
        }
    }

    private fun client(token: String = "abcd-efgh-jkmn") =
        NarrationServerClient(
            NarrationServerEndpoint("127.0.0.1", server.localPort),
            token,
            "Mac",
            connectTimeoutMs = 2_000,
            readTimeoutMs = 2_000,
        )

    @After
    fun tearDown() = server.close()

    @Test
    fun readsInfoAndSendsTheToken() =
        runBlocking {
            serve {
                Triple(
                    200,
                    "application/json",
                    """{"protocol":1,"model":"kokoro-en-v0_19","sampleRate":24000,"workers":4,"threadsPerWorker":2,"name":"mac"}"""
                        .toByteArray(),
                )
            }
            val info = client().info()
            assertEquals(4, info.workers)
            assertEquals("kokoro-en-v0_19", info.model)
            assertNull(NarrationServerPlanning.incompatibility(info, "Mac"))
            assertEquals(5, NarrationServerPlanning.inFlight(info))
            assertTrue(requests.single().contains("Authorization: Bearer abcd-efgh-jkmn"))
            assertTrue(requests.single().startsWith("GET /v1/info HTTP/1.1"))
        }

    @Test
    fun synthesizeReturnsTheWavAsPcmAndPostsTheText() =
        runBlocking {
            val wav = TtsWavWriter.mono16(FloatArray(480) { 0.25f }, 24_000)
            serve { Triple(200, "audio/wav", wav) }
            val clip = client().synthesize("\"Go home.\"", 1)
            assertNotNull(clip)
            assertEquals(24_000, clip!!.sampleRate)
            assertEquals(960, clip.pcm.size)
            val request = requests.single()
            assertTrue(request.startsWith("POST /v1/synthesize HTTP/1.1"))
            assertTrue(request.endsWith("""{"text":"\"Go home.\"","speakerId":1}"""))
        }

    @Test
    fun aRejectedTokenIsAServerProblemNotASentenceFailure() =
        runBlocking {
            serve { Triple(401, "application/json", """{"error":"Wrong or missing token"}""".toByteArray()) }
            try {
                client("wrong-token").synthesize("Hello.", 1)
                fail("Expected a server problem")
            } catch (problem: NarrationServerProblem) {
                assertTrue(problem.message!!.contains("rejected the token"))
                assertTrue(!problem.transient)
            }
        }

    @Test
    fun aServerErrorFailsOnlyThatSentence() =
        runBlocking {
            serve { Triple(500, "application/json", """{"error":"Generation failed"}""".toByteArray()) }
            assertNull(client().synthesize("Hello.", 1))
        }

    @Test
    fun aClosedPortIsTransientlyUnreachable() =
        runBlocking {
            val port = server.localPort
            server.close()
            try {
                NarrationServerClient(NarrationServerEndpoint("127.0.0.1", port), "abcd-efgh-jkmn", "Mac", 1_000, 1_000).info()
                fail("Expected a server problem")
            } catch (problem: NarrationServerProblem) {
                assertTrue(problem.transient)
                assertTrue(problem.message!!.contains("Mac"))
            }
        }

    @Test
    fun theStatusCheckReadsTheMemoryLevelAndSpotsAWrongToken() =
        runBlocking {
            serveKeepAlive { line ->
                if (line.startsWith("GET /v1/status")) {
                    Triple(200, "application/json", """{"memory":{"level":"critical","slots":0}}""".toByteArray())
                } else {
                    Triple(404, "application/json", """{"error":"Not found"}""".toByteArray())
                }
            }
            assertEquals("critical", client().memoryLevel())
            assertTrue(requests.single().startsWith("GET /v1/status HTTP/1.1"))
        }

    @Test
    fun aWrongTokenIsRejected() =
        runBlocking {
            serve { Triple(401, "application/json", """{"error":"Wrong or missing token"}""".toByteArray()) }
            try {
                client().memoryLevel()
                fail("Expected a rejected token")
            } catch (rejected: NarrationServerRejected) {
                assertTrue(!rejected.transient)
            }
        }

    @Test
    fun anUnreachableLocalAddressFallsBackToTailscale() =
        runBlocking {
            serveKeepAlive { Triple(200, "application/json", """{"memory":{"level":"normal"}}""".toByteArray()) }
            val closedPort = ServerSocket(0).use { it.localPort }
            val computer =
                NarrationServer(
                    id = "m",
                    name = "Mac",
                    address = "127.0.0.1:$closedPort",
                    token = "abcd-efgh-jkmn",
                    tailscaleAddress = "127.0.0.1:${server.localPort}",
                )
            val reached = reachServer(computer, readTimeoutMs = 2_000) { it.memoryLevel() }
            reached.client.close()
            assertEquals(NarrationRoute.TAILSCALE, reached.route.route)
            assertEquals("normal", reached.value)
        }

    @Test
    fun theLocalAddressIsUsedWhenItAnswers() =
        runBlocking {
            serveKeepAlive { Triple(200, "application/json", """{"memory":{"level":"normal"}}""".toByteArray()) }
            val closedPort = ServerSocket(0).use { it.localPort }
            val computer =
                NarrationServer(
                    name = "Mac",
                    address = "127.0.0.1:${server.localPort}",
                    token = "abcd-efgh-jkmn",
                    tailscaleAddress = "127.0.0.1:$closedPort",
                )
            val status = NarrationServerMonitor.checkServer(computer, checkedAtMs = 5)
            assertEquals(NarrationServerStatus(computer.locationKey, 5, online = true, route = NarrationRoute.LOCAL), status)
        }

    @Test
    fun noAnsweringAddressIsOfflineWithOneMessage() =
        runBlocking {
            val closedA = ServerSocket(0).use { it.localPort }
            val closedB = ServerSocket(0).use { it.localPort }
            val computer =
                NarrationServer(
                    name = "Mac",
                    address = "127.0.0.1:$closedA",
                    token = "abcd-efgh-jkmn",
                    tailscaleAddress = "127.0.0.1:$closedB",
                )
            val status = NarrationServerMonitor.checkServer(computer, checkedAtMs = 5)
            assertTrue(!status.online)
            assertTrue(status.problem!!.contains("local network or over Tailscale"))
        }

    @Test
    fun keptAliveConnectionsCarrySeveralSentences() =
        runBlocking {
            val wav = TtsWavWriter.mono16(FloatArray(480) { 0.25f }, 24_000)
            serveKeepAlive { line ->
                if (line.startsWith("GET")) Triple(200, "application/json", INFO.toByteArray()) else Triple(200, "audio/wav", wav)
            }
            val client = client()
            client.info()
            repeat(3) { assertNotNull(client.synthesize("Hello.", 1)) }
            client.close()
            assertEquals(1, connections.get())
            assertEquals(2, client.serverSlots)
            assertTrue(requests.all { it.contains("Connection: keep-alive") })
        }

    @Test
    fun aConnectionTheServerClosedIsReplacedWithoutFailing() =
        runBlocking {
            val wav = TtsWavWriter.mono16(FloatArray(480) { 0.25f }, 24_000)
            serveKeepAlive(closeAfter = 1) { Triple(200, "audio/wav", wav) }
            val client = client()
            assertNotNull(client.synthesize("One.", 1))
            Thread.sleep(100) // Let the server close the first connection.
            assertNotNull(client.synthesize("Two.", 1))
            client.close()
            assertEquals(2, connections.get())
        }

    @Test
    fun aServerLowOnMemoryIsBusyNotBroken() =
        runBlocking {
            serveKeepAlive {
                Triple(
                    503,
                    "application/json",
                    """{"error":"Mac is low on memory and has stopped generating (800 MB free)","retryAfterMs":30000,"stopped":true}"""
                        .toByteArray(),
                )
            }
            try {
                client().synthesize("Hello.", 1)
                fail("Expected the server to be busy")
            } catch (busy: NarrationServerBusy) {
                assertEquals(30_000L, busy.retryAfterMs)
                assertTrue(busy.stopped)
                assertTrue(busy.message!!.contains("low on memory"))
            }
        }

    @Test
    fun aBusyServerWithoutABodyFallsBackToRetryAfter() =
        runBlocking {
            serveKeepAlive { Triple(503, "text/plain", "busy".toByteArray()) }
            try {
                client().synthesize("Hello.", 1)
                fail("Expected the server to be busy")
            } catch (busy: NarrationServerBusy) {
                assertEquals(7_000L, busy.retryAfterMs)
                assertTrue(!busy.stopped)
            }
        }

    @Test
    fun aResponseCutOffInItsHeadersIsALostConnection() =
        runBlocking {
            thread(isDaemon = true) {
                server.accept().use { socket ->
                    BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1)).readLine()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\n".toByteArray())
                        flush()
                    }
                }
            }
            try {
                client().synthesize("Hello.", 1)
                fail("Expected a lost connection")
            } catch (problem: NarrationServerProblem) {
                assertTrue(problem.transient)
                assertTrue(problem.message!!.contains("Lost the connection"))
            }
        }

    private companion object {
        const val INFO = """{"protocol":1,"model":"kokoro-en-v0_19","sampleRate":24000,"workers":4,"threadsPerWorker":8,"name":"mac"}"""
    }
}
