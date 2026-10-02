package com.vinicius741.webnovelarchiver.ai

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class JevCoverClientTest {
    private val response = """{"id":"gen-dec-test","model":"typesafe/jev-1.13-20260917","answers":{
        "appearance":{"type":"score","score":2},
        "premise":{"type":"score","score":1},
        "imagery":{"type":"score","score":0.5},
        "temporary":{"type":"noul","noul":0.1}},
        "usage":{"input_tokens":1000,"output_tokens":20,"cost":0.000042}}"""

    @Test fun `sends authenticated typed questions and parses score range`() =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(response))
                val client = JevCoverClient(OpenRouterClient(server.url("/").toString()))
                val body = JevCoverClient.request(JsonObject().apply { addProperty("passage", "A copper automaton") })
                val answer = JevCoverClient.parse(client.evaluate("test-key", body))
                val request = server.takeRequest()
                assertEquals("Bearer test-key", request.getHeader("Authorization"))
                assertEquals("/api/alpha/decisions", request.path)
                assertEquals("POST", request.method)
                assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
                assertEquals(body, JsonParser.parseString(request.body.readUtf8()))
                assertEquals("typesafe/jev-1.13", body.get("model").asString)
                assertEquals(setOf("appearance", "premise", "imagery", "temporary"), body.getAsJsonObject("questions").keySet())
                assertEquals(1.0, answer.appearance, 0.0)
                assertEquals(0.5, answer.premise, 0.0)
                assertEquals(0.1, answer.temporary, 0.0)
            }
        }

    @Test fun `auth failure is not retried and never exposes response body`() =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(401).setBody("sensitive provider detail"))
                val failure =
                    runCatching {
                        JevCoverClient(
                            OpenRouterClient(server.url("/").toString()),
                        ).evaluate("secret", JsonObject())
                    }.exceptionOrNull()
                assertTrue(failure is IOException)
                assertTrue(failure!!.message!!.contains("OpenRouter API key"))
                assertFalse(failure.message!!.contains("sensitive"))
                assertEquals(1, server.requestCount)
            }
        }

    @Test fun `rate limit retries and accounts for each response`() =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(429))
                server.enqueue(MockResponse().setBody(response))
                val statuses = mutableListOf<Int>()
                JevCoverClient(OpenRouterClient(server.url("/").toString())).evaluate("test", JsonObject()) { _, code -> statuses += code }
                assertEquals(listOf(429, 200), statuses)
            }
        }

    @Test fun `insufficient credits and unavailable model give corrective guidance without retry`() =
        runBlocking {
            for ((code, guidance) in mapOf(402 to "insufficient credits", 404 to "select chapters manually")) {
                MockWebServer().use { server ->
                    server.enqueue(MockResponse().setResponseCode(code))
                    val statuses = mutableListOf<Int>()
                    val failure =
                        runCatching {
                            JevCoverClient(OpenRouterClient(server.url("/").toString()))
                                .evaluate("test", JsonObject()) { _, status -> statuses += status }
                        }.exceptionOrNull()
                    assertTrue(failure is IOException)
                    assertTrue(failure!!.message!!.contains(guidance))
                    assertEquals(listOf(code), statuses)
                    assertEquals(1, server.requestCount)
                }
            }
        }

    @Test fun `OpenRouter service overload retries twice then stops`() =
        runBlocking {
            MockWebServer().use { server ->
                repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }
                val statuses = mutableListOf<Int>()
                val failure =
                    runCatching {
                        JevCoverClient(OpenRouterClient(server.url("/").toString()))
                            .evaluate("test", JsonObject()) { _, code -> statuses += code }
                    }.exceptionOrNull()
                assertTrue(failure is IOException)
                assertEquals(listOf(503, 503, 503), statuses)
                assertEquals(3, server.requestCount)
            }
        }

    @Test fun `malformed missing and out of range judgments are rejected`() {
        val json = JsonParser.parseString(response).asJsonObject
        val missing = json.deepCopy().apply { getAsJsonObject("answers").remove("premise") }
        val wrongRange = json.deepCopy().apply { getAsJsonObject("answers").getAsJsonObject("appearance").addProperty("score", 3) }
        for (bad in listOf(JsonObject(), missing, wrongRange)) assertTrue(runCatching { JevCoverClient.parse(bad) }.isFailure)
    }

    @Test fun `cancellation stops waiting for the network`() =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(response).setBodyDelay(3, TimeUnit.SECONDS))
                val job = launch { JevCoverClient(OpenRouterClient(server.url("/").toString())).evaluate("test", JsonObject()) }
                kotlinx.coroutines.delay(100)
                withTimeout(1000) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)
            }
        }
}
