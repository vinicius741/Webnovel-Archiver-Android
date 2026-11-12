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
}
