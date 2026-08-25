package com.vinicius741.webnovelarchiver.ai

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.vinicius741.webnovelarchiver.domain.model.AiSettings
import com.vinicius741.webnovelarchiver.domain.model.AiUsageRecord
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/*
 * MockWebServer tests for the single-chapter Verified rewrite flow: request shape (structured
 * outputs, provider routing with step-down), the hard finish_reason=length reject, single-bounded
 * repair, verifier pairing and statuses. The fake source stands in for the repository.
 */

class AiChapterRewriteEngineTest {
    private lateinit var server: MockWebServer
    private lateinit var engine: AiChapterRewriteEngine
    private lateinit var source: FakeRewriteSource

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = OpenRouterClient(baseUrl = server.url("/").toString(), client = OkHttpClient())
        source = FakeRewriteSource()
        engine = AiChapterRewriteEngine(source, client)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `happy path rewrites verifies and records a ready draft`() =
        runBlocking {
            enqueueModels()
            val parsed = ChapterBlockParsing.parseChapter(SOURCE_HTML)
            enqueueRewrite(successReply(parsed) { it })
            enqueueVerify("""{"findings": []}""")

            val output = engine.draft(STORY, CHAPTER)

            assertEquals("ready", output.status)
            assertTrue(output.polishedHtml.contains("Rewritten opening"))
            assertTrue(output.validation.ok)
            assertEquals(3, server.requestCount)
            server.takeRequest()
            val rewriteBody = server.takeRequest().body.readUtf8()
            assertTrue(rewriteBody.contains("\"provider\":{\"zdr\":true,\"data_collection\":\"deny\",\"require_parameters\":true}"))
            assertTrue(rewriteBody.contains("\"response_format\":{\"type\":\"json_schema\""))
            assertTrue(rewriteBody.contains("\"temperature\":0.6"))
            assertTrue(rewriteBody.contains("\"max_tokens\":"))
            val verifyBody = server.takeRequest().body.readUtf8()
            assertTrue(verifyBody.contains("\"model\":\"test/verifier\""))
            assertTrue(verifyBody.contains("merged into the block above"))
            assertEquals(2, source.recordedUsage.size)
            assertTrue(source.recordedUsage.all { it.operationId == output.operationId })
            assertTrue(source.recordedUsage.map { it.feature }.containsAll(listOf("chapter_rewrite", "chapter_verify")))
        }

    @Test
    fun `routing failures step the provider tier down and record the tier that served`() =
        runBlocking {
            enqueueModels()
            val parsed = ChapterBlockParsing.parseChapter(SOURCE_HTML)
            val routingError = """{"error":{"message":"No allowed providers found for the selected model."}}"""
            server.enqueue(MockResponse().setResponseCode(404).setBody(routingError))
            server.enqueue(MockResponse().setResponseCode(404).setBody(routingError))
            enqueueRewrite(successReply(parsed) { it })
            enqueueVerify("""{"findings": []}""")

            val output = engine.draft(STORY, CHAPTER)

            assertEquals("none", output.providerTier)
            assertEquals(5, server.requestCount)
            repeat(4) { server.takeRequest() }
            val verifyBody = server.takeRequest().body.readUtf8()
            // The verifier also starts strict once the routing serves the model.
            assertTrue(verifyBody.contains("\"provider\":{\"zdr\":true"))
        }

    @Test
    fun `finish_reason length is a hard reject with no repair`() =
        runBlocking {
            enqueueModels()
            server.enqueue(
                MockResponse().setBody(
                    chatContent(
                        """{"blocks": [], "self_audit": {"protected_blocks_unchanged": true, "possible_drift": []}}""",
                        finishReason = "length",
                    ),
                ),
            )

            val error = runCatching { engine.draft(STORY, CHAPTER) }.exceptionOrNull()

            assertNotNull(error)
            assertTrue(error!!.message!!.contains("truncated"))
            assertEquals(2, server.requestCount)
            assertTrue(source.recordedUsage.any { it.outcome == "truncated" })
        }

    @Test
    fun `invalid first reply gets exactly one repair`() =
        runBlocking {
            enqueueModels()
            val parsed = ChapterBlockParsing.parseChapter(SOURCE_HTML)
            server.enqueue(
                MockResponse().setBody(
                    chatContent(
                        """{"blocks": [{"id": "b0001", "html": "<p>only one</p>"}], "self_audit": {"protected_blocks_unchanged": true, "possible_drift": []}}""",
                        finishReason = "stop",
                    ),
                ),
            )
            enqueueRewrite(successReply(parsed) { it })
            enqueueVerify("""{"findings": []}""")

            val output = engine.draft(STORY, CHAPTER)

            assertEquals("ready", output.status)
            assertEquals(4, server.requestCount)
            repeat(2) { server.takeRequest() }
            val repairBody = server.takeRequest().body.readUtf8()
            assertTrue(repairBody.contains("failed validation"))
            assertTrue(source.recordedUsage.any { it.feature == "chapter_repair" })
        }

    @Test
    fun `verifier blockers mark the draft blocked and unappliable`() =
        runBlocking {
            enqueueModels()
            val parsed = ChapterBlockParsing.parseChapter(SOURCE_HTML)
            enqueueRewrite(successReply(parsed) { it })
            enqueueVerify(
                """{"findings": [{"severity": "blocker", "type": "changed_number", "block_ids": ["b0002"], "evidence": "4 vs 5"}]}""",
            )

            val output = engine.draft(STORY, CHAPTER)

            assertEquals("blocked", output.status)
            assertEquals(1, ChapterRewriteValidation.blockersOf(output.verdict!!).size)
        }

    @Test
    fun `unparseable verifier reply after one retry is a verify failure never a pass`() =
        runBlocking {
            enqueueModels()
            val parsed = ChapterBlockParsing.parseChapter(SOURCE_HTML)
            enqueueRewrite(successReply(parsed) { it })
            server.enqueue(MockResponse().setBody(chatContent("this is not json", finishReason = "stop")))
            server.enqueue(MockResponse().setBody(chatContent("still not json", finishReason = "stop")))

            val output = engine.draft(STORY, CHAPTER)

            assertEquals("verify_failed", output.status)
            assertEquals(4, server.requestCount)
        }

    @Test
    fun `truncated verifier reply retries with a larger budget instead of discarding the rewrite`() =
        runBlocking {
            enqueueModels()
            val parsed = ChapterBlockParsing.parseChapter(SOURCE_HTML)
            enqueueRewrite(successReply(parsed) { it })
            server.enqueue(MockResponse().setBody(chatContent("""{"findings": [{"severity": "blo""", finishReason = "length")))
            enqueueVerify("""{"findings": []}""")

            val output = engine.draft(STORY, CHAPTER)

            assertEquals("ready", output.status)
            assertEquals(4, server.requestCount)
            repeat(2) { server.takeRequest() }
            val firstVerify = server.takeRequest().body.readUtf8()
            assertTrue(firstVerify.contains("\"max_tokens\":4000"))
            val retryVerify = server.takeRequest().body.readUtf8()
            assertTrue(retryVerify.contains("\"max_tokens\":6000"))
            // The cut-off verdict still lands in the ledger as a terminal outcome.
            assertTrue(source.recordedUsage.any { it.feature == "chapter_verify" && it.outcome == "truncated" })
        }

    @Test
    fun `verifier model equal to the rewriter is swapped for a verified alternate`() =
        runBlocking {
            enqueueModels()
            source.settings = source.settings.copy(chapterVerifierModel = "test/model")
            val parsed = ChapterBlockParsing.parseChapter(SOURCE_HTML)
            enqueueRewrite(successReply(parsed) { it })
            enqueueVerify("""{"findings": []}""")

            engine.draft(STORY, CHAPTER)

            repeat(2) { server.takeRequest() }
            val verifyBody = server.takeRequest().body.readUtf8()
            assertTrue(verifyBody.contains(AiSettings.ALTERNATE_CHAPTER_VERIFIER_MODEL))
        }

    /** The engine fetches the model catalog once per instance; queue it before any chat reply. */
    private fun enqueueModels() {
        server.enqueue(
            MockResponse().setBody(
                """
                {"data": [{"id": "test/model", "name": "Test Model", "pricing": {"prompt": "0.000001", "completion": "0.000005"},
                "context_length": 100000, "top_provider": {"max_completion_tokens": 8000},
                "supported_parameters": ["response_format", "temperature"]}]}
                """.trimIndent(),
            ),
        )
    }

    private fun enqueueRewrite(body: String) {
        server.enqueue(MockResponse().setBody(body))
    }

    private fun enqueueVerify(json: String) {
        server.enqueue(MockResponse().setBody(chatContent(json, finishReason = "stop")))
    }

    /** Builds a valid rewrite reply: protected blocks copied, addressable blocks mapped through [edit]. */
    private fun successReply(
        parsed: ParsedChapter,
        edit: (String) -> String,
    ): String {
        val blocks = com.google.gson.JsonArray()
        parsed.blocks.forEach { block ->
            val html =
                when {
                    block.protected -> block.html
                    block.id == "b0001" -> "<p>Rewritten opening line for the test chapter.</p>"
                    else -> edit(block.html)
                }
            blocks.add(
                JsonObject().apply {
                    addProperty("id", block.id)
                    addProperty("html", html)
                },
            )
        }
        val reply =
            JsonObject().apply {
                add("blocks", blocks)
                add(
                    "self_audit",
                    JsonObject().apply {
                        addProperty("protected_blocks_unchanged", true)
                        add("possible_drift", com.google.gson.JsonArray())
                    },
                )
            }
        return chatContent(reply.toString(), finishReason = "stop")
    }

    private fun chatContent(
        content: String,
        finishReason: String,
    ): String {
        val payload =
            JsonObject().apply {
                addProperty("id", "gen-${server.requestCount}")
                addProperty("model", "test/model")
                add(
                    "choices",
                    com.google.gson.JsonArray().apply {
                        add(
                            JsonObject().apply {
                                addProperty("finish_reason", finishReason)
                                add(
                                    "message",
                                    JsonObject().apply {
                                        addProperty("role", "assistant")
                                        addProperty("content", content)
                                    },
                                )
                            },
                        )
                    },
                )
                add(
                    "usage",
                    JsonObject().apply {
                        addProperty("prompt_tokens", 100)
                        addProperty("completion_tokens", 50)
                        addProperty("cost", 0.01)
                    },
                )
            }
        return payload.toString()
    }

    private class FakeRewriteSource : AiChapterRewriteEngineSource {
        var settings =
            AiSettings(
                apiKey = "sk-test",
                chapterRewriteModel = "test/model",
                chapterVerifierModel = "test/verifier",
            )
        val recordedUsage = mutableListOf<AiUsageRecord>()

        override fun aiSettings(): AiSettings = settings

        override fun story(id: String): Story =
            Story(
                id = id,
                title = "Test Novel",
                author = "Author",
                chapters =
                    mutableListOf(
                        Chapter(id = CHAPTER, title = "Chapter One", downloaded = true, filePath = "novels/test/0001.html"),
                    ),
            )

        override suspend fun chapterHtml(chapter: Chapter): String = SOURCE_HTML

        override suspend fun recordUsage(record: AiUsageRecord) {
            recordedUsage.add(record)
        }
    }

    private companion object {
        const val STORY = "story1"
        const val CHAPTER = "ch1"
        val SOURCE_HTML =
            ChapterBlockParsing.sanitizeChapterHtml(
                "<p>The opening line of the test chapter.</p>" +
                    "<blockquote><strong>[SYSTEM] Level 4</strong></blockquote>" +
                    "<p>A short beat.</p><p>Another beat lands.</p>",
            )
    }
}
