package com.vinicius741.webnovelarchiver.ai

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
import java.util.Base64

/**
 * MockWebServer tests for [OpenRouterClient]: request shape (auth header, JSON body) and the
 * friendly error mapping for auth/credit/model/rate-limit failures.
 */
class OpenRouterClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OpenRouterClient

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        client = OpenRouterClient(baseUrl = server.url("/").toString(), client = OkHttpClient())
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `chatCompletion sends bearer key and parses first choice content`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"id":"gen-chat-1","model":"deepseek/deepseek-v4-flash-0731:free","choices":[{"message":{"role":"assistant","content":"  A cursed blade, a stubborn farmer.  "}}],"usage":{"prompt_tokens":123,"completion_tokens":17,"total_tokens":140,"reasoning_tokens":4,"prompt_tokens_details":{"cached_tokens":9},"cost":0.0000012300000000000001}}
                    """.trimIndent(),
                ),
            )

            val result =
                client.chatCompletion(
                    apiKey = "sk-or-v1-test",
                    model = "deepseek/deepseek-v4-flash-0731",
                    messages = listOf(OpenRouterMessage("user", "hello")),
                    maxTokens = 700,
                )

            assertEquals("A cursed blade, a stubborn farmer.", result.content)
            assertEquals("gen-chat-1", result.receipt.generationId)
            assertEquals("deepseek/deepseek-v4-flash-0731:free", result.receipt.model)
            assertEquals(123L, result.receipt.promptTokens)
            assertEquals(17L, result.receipt.completionTokens)
            assertEquals(140L, result.receipt.totalTokens)
            assertEquals(4L, result.receipt.reasoningTokens)
            assertEquals(9L, result.receipt.cachedTokens)
            assertEquals("0.0000012300000000000001", result.receipt.costUsd)
            val recorded = server.takeRequest()
            assertEquals("/api/v1/chat/completions", recorded.path)
            assertEquals("Bearer sk-or-v1-test", recorded.getHeader("Authorization"))
            val body = recorded.body.readUtf8()
            assert(body.contains("\"model\":\"deepseek/deepseek-v4-flash-0731\""))
            assert(body.contains("\"max_tokens\":700"))
            assert(body.contains("\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]"))
        }

    @Test
    fun `chatCompletion maps auth credit model and rate-limit failures to friendly messages`() =
        runBlocking {
            val cases =
                mapOf(
                    401 to "Invalid OpenRouter API key",
                    402 to "insufficient credits",
                    404 to "Model not found",
                    429 to "rate limit",
                )
            cases.forEach { (code, expectedFragment) ->
                server.enqueue(MockResponse().setResponseCode(code).setBody("""{"error":{"message":"boom"}}"""))
                val error =
                    runCatching {
                        client.chatCompletion("key", "m", listOf(OpenRouterMessage("user", "hi")), 10)
                    }.exceptionOrNull()
                assertTrue("expected failure for HTTP $code", error is OpenRouterException)
                assertTrue("message for HTTP $code: ${error?.message}", error?.message?.contains(expectedFragment) == true)
            }
        }

    @Test
    fun `chatCompletion keeps a billing receipt attached to an HTTP failure`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(429)
                    .setBody(
                        """{"id":"gen-failed","error":{"message":"upstream stopped"},"usage":{"prompt_tokens":12,"total_tokens":12,"cost":"0.00003"}}""",
                    ),
            )

            val error =
                runCatching {
                    client.chatCompletion("key", "m", listOf(OpenRouterMessage("user", "hi")), 10)
                }.exceptionOrNull() as OpenRouterException

            assertEquals("gen-failed", error.receipt?.generationId)
            assertEquals(12L, error.receipt?.promptTokens)
            assertEquals("0.00003", error.receipt?.costUsd)
        }

    @Test
    fun `chatCompletion rejects an empty completion`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody(
                    """{"id":"gen-empty-1","model":"m:provider","choices":[{"message":{"content":"   "}}],"usage":{"prompt_tokens":8,"completion_tokens":0,"total_tokens":8,"cost":"0.00000007"}}""",
                ),
            )

            val error =
                runCatching {
                    client.chatCompletion("key", "m", listOf(OpenRouterMessage("user", "hi")), 10)
                }.exceptionOrNull()

            // The dedicated type marks empty completions as retryable flakes, unlike HTTP failures.
            assertTrue(error is OpenRouterEmptyCompletionException)
            assertTrue(error?.message?.contains("empty") == true)
            val empty = error as OpenRouterEmptyCompletionException
            assertEquals("gen-empty-1", empty.receipt.generationId)
            assertEquals("m:provider", empty.receipt.model)
            assertEquals(8L, empty.receipt.promptTokens)
            assertEquals("0.00000007", empty.receipt.costUsd)
        }

    @Test
    fun `fetchModels parses catalog pricing and sorts by id`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"data":[
                      {"id":"zeta/big","name":"Zeta Big","pricing":{"prompt":"0.000002","completion":"0.000006"}},
                      {"id":"alpha/free","name":"Alpha Free","pricing":{"prompt":"0","completion":"0"}}
                    ]}
                    """.trimIndent(),
                ),
            )

            val models = client.fetchModels()

            assertEquals(listOf("alpha/free", "zeta/big"), models.map { it.id })
            assertEquals("Alpha Free", models[0].name)
            assertTrue(models[0].isFree)
            assertTrue(!models[1].isFree)
            assertEquals("0.000002", models[1].promptPricePerToken)
            assertNull(server.takeRequest().getHeader("Authorization"))
        }

    @Test
    fun `fetchModels surfaces a friendly error when the catalog is unavailable`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))

            val error = runCatching { client.fetchModels() }.exceptionOrNull()

            assertTrue(error is OpenRouterException)
            assertTrue(error?.message?.contains("model list") == true)
        }

    @Test
    fun `generateImage sends prompt and optional parameters and decodes base64`() =
        runBlocking {
            val imageBytes = ByteArray(8) { it.toByte() }
            val base64 = Base64.getEncoder().encodeToString(imageBytes)
            server.enqueue(
                MockResponse().setBody(
                    """{"id":"gen-image-1","model":"x-ai/grok-imagine-image-2.0:provider","created":1,"data":[{"b64_json":"$base64","media_type":"image/png"}],"usage":{"prompt_tokens":31,"completion_tokens":1,"total_tokens":32,"cost":0.04000000000000001}}""",
                ),
            )

            val image =
                client.generateImage(
                    apiKey = "sk-or-v1-test",
                    model = "x-ai/grok-imagine-image-2.0",
                    prompt = "a lone tower under storm light",
                    aspectRatio = "2:3",
                    resolution = "1K",
                    quality = "medium",
                )

            assertTrue(image.bytes.contentEquals(imageBytes))
            assertEquals("image/png", image.mediaType)
            assertEquals("gen-image-1", image.receipt.generationId)
            assertEquals("x-ai/grok-imagine-image-2.0:provider", image.receipt.model)
            assertEquals(31L, image.receipt.promptTokens)
            assertEquals(1L, image.receipt.completionTokens)
            assertEquals(32L, image.receipt.totalTokens)
            assertEquals("0.04000000000000001", image.receipt.costUsd)
            val recorded = server.takeRequest()
            assertEquals("/api/v1/images", recorded.path)
            assertEquals("Bearer sk-or-v1-test", recorded.getHeader("Authorization"))
            val body = recorded.body.readUtf8()
            assert(body.contains("\"model\":\"x-ai/grok-imagine-image-2.0\""))
            assert(body.contains("\"prompt\":\"a lone tower under storm light\""))
            assert(body.contains("\"aspect_ratio\":\"2:3\""))
            assert(body.contains("\"resolution\":\"1K\""))
            assert(body.contains("\"quality\":\"medium\""))
        }

    @Test
    fun `generateImage omits null optional parameters`() =
        runBlocking {
            val base64 = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
            server.enqueue(MockResponse().setBody("""{"data":[{"b64_json":"$base64"}]}"""))

            val image = client.generateImage("key", "m", "p")

            assertNull(image.mediaType)
            val body = server.takeRequest().body.readUtf8()
            assert(!body.contains("aspect_ratio"))
            assert(!body.contains("resolution"))
            assert(!body.contains("quality"))
        }

    @Test
    fun `fetchCurrentKeyUsage parses exact decimal counters and reset`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"data":{"usage":0.12345678901234567890,"usage_daily":"0.01000000000000000001","usage_weekly":1.25,"usage_monthly":2.50000000000000000009,"limit":10.00000000000000000001,"limit_remaining":9.87654321098765432109,"limit_reset":"2026-08-20T00:00:00Z"}}
                    """.trimIndent(),
                ),
            )

            val usage = client.fetchCurrentKeyUsage("sk-or-v1-test")

            assertEquals("0.12345678901234567890", usage.usage)
            assertEquals("0.01000000000000000001", usage.usageDaily)
            assertEquals("1.25", usage.usageWeekly)
            assertEquals("2.50000000000000000009", usage.usageMonthly)
            assertEquals("10.00000000000000000001", usage.limit)
            assertEquals("9.87654321098765432109", usage.limitRemaining)
            assertEquals("2026-08-20T00:00:00Z", usage.limitReset)
            val request = server.takeRequest()
            assertEquals("/api/v1/key", request.path)
            assertEquals("Bearer sk-or-v1-test", request.getHeader("Authorization"))
        }

    @Test
    fun `generateImage maps failures and rejects missing images`() =
        runBlocking {
            val cases =
                mapOf(
                    401 to "Invalid OpenRouter API key",
                    402 to "insufficient credits",
                    404 to "Model not found",
                    429 to "rate limit",
                )
            cases.forEach { (code, expectedFragment) ->
                server.enqueue(MockResponse().setResponseCode(code).setBody("""{"error":{"message":"boom"}}"""))
                val error = runCatching { client.generateImage("key", "m", "p") }.exceptionOrNull()
                assertTrue("expected failure for HTTP $code", error is OpenRouterException)
                assertTrue("message for HTTP $code: ${error?.message}", error?.message?.contains(expectedFragment) == true)
            }

            server.enqueue(MockResponse().setBody("""{"data":[]}"""))
            val empty = runCatching { client.generateImage("key", "m", "p") }.exceptionOrNull()
            assertTrue(empty is OpenRouterException)
            assertTrue(empty?.message?.contains("no image") == true)

            server.enqueue(MockResponse().setBody("""{"data":[{"b64_json":"!!not-base64!!"}]}"""))
            val unreadable = runCatching { client.generateImage("key", "m", "p") }.exceptionOrNull()
            assertTrue(unreadable is OpenRouterException)
            assertTrue(unreadable?.message?.contains("unreadable") == true)
        }

    @Test
    fun `fetchImageModels parses parameter map with allowed values without auth`() =
        runBlocking {
            // Real catalog shape: supported_parameters maps each parameter name to its spec object,
            // whose values array enumerates what the model accepts.
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"data":[
                      {"id":"z-ai/zz","name":"Z AI","supported_parameters":{
                        "resolution":{"type":"enum","values":["1K","2K"]},
                        "aspect_ratio":{"type":"enum","values":["2:3"]},
                        "moderation":{"type":"boolean"}
                      }},
                      {"id":"a-ai/aa","name":"A AI","supported_parameters":{}},
                      {"id":"m-ai/mm","name":"M AI","supported_parameters":["resolution","quality"]}
                    ]}
                    """.trimIndent(),
                ),
            )

            val models = client.fetchImageModels()

            assertEquals(listOf("a-ai/aa", "m-ai/mm", "z-ai/zz"), models.map { it.id })
            assertEquals("Z AI", models[2].name)
            assertEquals(
                mapOf("resolution" to listOf("1K", "2K"), "aspect_ratio" to listOf("2:3"), "moderation" to null),
                models[2].supportedParameters,
            )
            assertTrue(models[0].supportedParameters.isEmpty())
            // A plain string array is also accepted (forward compatibility): names present, values unconstrained.
            assertEquals(mapOf("resolution" to null, "quality" to null), models[1].supportedParameters)
            assertNull(server.takeRequest().getHeader("Authorization"))
        }

    @Test
    fun `fetchImageModels rejects an empty catalog loudly`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("""{"data":[]}"""))

            val error = runCatching { client.fetchImageModels() }.exceptionOrNull()

            assertTrue(error is OpenRouterException)
            assertTrue(error?.message?.contains("empty image model list") == true)
        }
}

class AiModelPresentationTest {
    @Test
    fun `priceLabel marks free models and formats per-million prices`() {
        val free =
            OpenRouterModel("a/free", "A Free", promptPricePerToken = "0", completionPricePerToken = "0")
        assertEquals("Free", AiModelPresentation.priceLabel(free))

        val paid =
            OpenRouterModel(
                "a/paid",
                "A Paid",
                promptPricePerToken = "0.00000015",
                completionPricePerToken = "0.0000006",
            )
        assertEquals("$0.15 in · $0.6 out per 1M tokens", AiModelPresentation.priceLabel(paid))

        val unknown = OpenRouterModel("a/x", "A X", promptPricePerToken = null, completionPricePerToken = null)
        assertEquals("pricing unavailable", AiModelPresentation.priceLabel(unknown))
    }

    @Test
    fun `filter searches id and name case-insensitively and honors free-only`() {
        val models =
            listOf(
                OpenRouterModel("deepseek/flash", "DeepSeek Flash", "0.0000001", "0.0000003"),
                OpenRouterModel("meta/llama", "Llama Free", "0", "0"),
            )

        assertEquals(2, AiModelPresentation.filter(models, "", freeOnly = false).size)
        assertEquals(1, AiModelPresentation.filter(models, "LLAMA", freeOnly = false).size)
        assertEquals(1, AiModelPresentation.filter(models, "deepseek", freeOnly = false).size)
        assertEquals(0, AiModelPresentation.filter(models, "deepseek", freeOnly = true).size)
        assertEquals(1, AiModelPresentation.filter(models, "", freeOnly = true).size)
    }
}
