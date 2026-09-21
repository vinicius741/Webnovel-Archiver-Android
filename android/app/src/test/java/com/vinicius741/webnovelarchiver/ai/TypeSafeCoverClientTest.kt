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

class TypeSafeCoverClientTest {
    private val response = """{"model":"jev-1.13.0","answers":{
        "appearance":{"type":"score","score":2},
        "premise":{"type":"score","score":1},
        "imagery":{"type":"score","score":0.5},
        "temporary":{"type":"noul","noul":0.1}},
        "usage":{"input_tokens":1000,"output_tokens":20}}"""

    @Test fun `sends authenticated typed questions and parses score range`() =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(response))
                val client = TypeSafeCoverClient(server.url("/v1/systemone").toString())
                val body = TypeSafeCoverClient.request(JsonObject().apply { addProperty("passage", "A copper automaton") })
                val answer = TypeSafeCoverClient.parse(client.evaluate("test-key", body))
                val request = server.takeRequest()
                assertEquals("Bearer test-key", request.getHeader("Authorization"))
                assertEquals("/v1/systemone", request.path)
                assertEquals(body, JsonParser.parseString(request.body.readUtf8()))
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
                        TypeSafeCoverClient(
                            server.url("/").toString(),
                        ).evaluate("secret", JsonObject())
                    }.exceptionOrNull()
                assertTrue(failure is IOException)
                assertTrue(failure!!.message!!.contains("TypeSafe API key"))
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
                TypeSafeCoverClient(server.url("/").toString()).evaluate("test", JsonObject()) { _, code -> statuses += code }
                assertEquals(listOf(429, 200), statuses)
            }
        }

    @Test fun `malformed missing and out of range judgments are rejected`() {
        val json = JsonParser.parseString(response).asJsonObject
        val missing = json.deepCopy().apply { getAsJsonObject("answers").remove("premise") }
        val wrongRange = json.deepCopy().apply { getAsJsonObject("answers").getAsJsonObject("appearance").addProperty("score", 3) }
        for (bad in listOf(JsonObject(), missing, wrongRange)) assertTrue(runCatching { TypeSafeCoverClient.parse(bad) }.isFailure)
    }

    @Test fun `cancellation stops waiting for the network`() =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(response).setBodyDelay(3, TimeUnit.SECONDS))
                val job = launch { TypeSafeCoverClient(server.url("/").toString()).evaluate("test", JsonObject()) }
                kotlinx.coroutines.delay(100)
                withTimeout(1000) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)
            }
        }
}
