package com.vinicius741.webnovelarchiver.ai

import com.google.gson.JsonParser
import com.vinicius741.webnovelarchiver.domain.model.AiSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AiReasoningRequestsTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OpenRouterClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OpenRouterClient(server.url("/").toString(), OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `catalog parses omitted null and mandatory effort metadata`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody(
                    """{"data":[
              {"id":"a","reasoning":{"supported_efforts":["xhigh","high","none"],"mandatory":true}},
              {"id":"b","reasoning":{"supported_efforts":null}},
              {"id":"c","reasoning":{}},
              {"id":"d"}
            ]}""",
                ),
            )
            val models = client.fetchModels()
            assertEquals(OpenRouterReasoningOptions(listOf("xhigh", "high", "none"), true), models[0].reasoning)
            assertEquals(OpenRouterReasoningOptions(null), models[1].reasoning)
            assertEquals(OpenRouterReasoningOptions(), models[2].reasoning)
            assertNull(models[3].reasoning)
            assertEquals(models, client.cachedModels)
        }

    @Test
    fun `chat sends saved levels or provider default and always excludes reasoning text`() =
        runBlocking {
            for (effort in listOf("high", "xhigh", "none", null)) {
                server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"ok"}}]}"""))
                client.chatCompletion("key", "m", listOf(OpenRouterMessage("user", "hello")), 2000, reasoningEffort = effort)
                val body = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
                val reasoning = body.getAsJsonObject("reasoning")
                assertEquals(effort, reasoning.get("effort")?.asString)
                assertTrue(reasoning.get("exclude").asBoolean)
            }
        }

    @Test
    fun `request resolution uses cached capability data and never sends an unsupported level`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody(
                    """{"data":[{"id":"m","reasoning":{"supported_efforts":["high"],"mandatory":true}}]}""",
                ),
            )
            assertEquals("high", client.reasoningEffortFor(AiSettings(reasoningEfforts = mapOf("m" to "high")), "m"))
            assertNull(client.reasoningEffortFor(AiSettings(reasoningEfforts = mapOf("m" to "none")), "m"))
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `failed catalog falls back to saved manual effort and is retryable`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
            val settings = AiSettings(reasoningEfforts = mapOf("manual/model" to "medium"))
            assertEquals("medium", client.reasoningEffortFor(settings, "manual/model"))
            assertNull(client.cachedModels)
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"manual/model"}]}"""))
            assertNull(client.reasoningEffortFor(settings, "manual/model"))
            assertEquals(2, server.requestCount)
        }
}
