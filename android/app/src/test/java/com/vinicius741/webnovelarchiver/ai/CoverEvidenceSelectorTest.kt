package com.vinicius741.webnovelarchiver.ai

import com.vinicius741.webnovelarchiver.data.storage.CoverEvidenceStore
import com.vinicius741.webnovelarchiver.domain.model.AiUsageRecord
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CoverEvidenceSelectorTest {
    private companion object {
        const val MODEL = "typesafe/jev-1.13"
    }

    @get:Rule val temporary = TemporaryFolder()
    private val response = """{"answers":{
      "appearance":{"type":"score","score":3},"premise":{"type":"score","score":1},
      "imagery":{"type":"score","score":2},"temporary":{"type":"noul","noul":0}},
      "usage":{"input_tokens":400,"output_tokens":20}}"""
    private val weakResponse = response.replace(Regex("\"score\":\\d"), "\"score\":0")

    /** Concurrent batches make response arrival order racy; answers are keyed to the judged chapter. */
    private fun chapterDispatcher(answer: (Int) -> MockResponse) =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val chapter = Regex("\"chapter_position\":(\\d+)").find(request.body.readUtf8())!!.groupValues[1].toInt()
                return answer(chapter)
            }
        }

    private fun story(chapters: Int) =
        Story(
            id = "s",
            title = "Orchard",
            chapters =
                (0 until chapters)
                    .map { index ->
                        Chapter(
                            id = "c$index",
                            title = "Chapter ${index + 1}",
                            downloaded = true,
                            content = "<p>Chapter ${index + 1}: a copper automaton tends the orchard.</p>",
                        )
                    }.toMutableList(),
        )

    private fun selector(
        server: MockWebServer,
        folder: File,
        recorded: IntArray = IntArray(1),
        readChapter: suspend (Chapter) -> String? = { it.content },
    ) = CoverEvidenceSelector(
        readChapter = readChapter,
        saveUsage = { recorded[0]++ },
        client = CoverDecisionClient(OpenRouterClient(server.url("/").toString())),
        cache = CoverEvidenceStore(folder),
    )

    @Test fun `missing downloaded files direct the user to redownload without calling Jev`() =
        runBlocking {
            MockWebServer().use { server ->
                server.dispatcher = chapterDispatcher { MockResponse().setBody(response) }
                val story = Story(chapters = mutableListOf(Chapter(id = "missing", downloaded = true)))
                val failure =
                    runCatching {
                        selector(server, temporary.newFolder(), readChapter = { null }).select(story, "test", MODEL, 10) {}
                    }.exceptionOrNull()
                assertEquals("Downloaded chapter files are missing; re-download the novel's chapters", failure?.message)
                assertEquals(0, server.requestCount)
            }
        }

    @Test fun `in memory content remains usable when a chapter file is missing`() =
        runBlocking {
            MockWebServer().use { server ->
                server.dispatcher = chapterDispatcher { MockResponse().setBody(response) }
                val story =
                    Story(
                        chapters =
                            mutableListOf(
                                Chapter(id = "missing", downloaded = true),
                                Chapter(id = "fallback", downloaded = true, content = "<p>A copper automaton tends an orchard.</p>"),
                            ),
                    )
                val selected = selector(server, temporary.newFolder(), readChapter = { null }).select(story, "test", MODEL, 10) {}
                assertEquals(listOf(2), selected.map { it.number })
                assertEquals(1, server.requestCount)
            }
        }

    @Test fun `readable text without useful evidence keeps the manual selection guidance`() =
        runBlocking {
            MockWebServer().use { server ->
                server.dispatcher = chapterDispatcher { MockResponse().setBody(weakResponse) }
                val story = Story(chapters = mutableListOf(Chapter(id = "readable", downloaded = true)))
                val notesOnly = selector(server, temporary.newFolder(), readChapter = { "<p>Author note</p>" })
                val failure = runCatching { notesOnly.select(story, "test", MODEL, 10) {} }.exceptionOrNull()
                assertEquals("No useful cover evidence found. Select context chapters manually in Generation options.", failure?.message)
                assertEquals(1, server.requestCount)
            }
        }

    @Test fun `scan stops after the first batch that fills the target and that batch spans the book`() =
        runBlocking {
            MockWebServer().use { server ->
                server.dispatcher = chapterDispatcher { MockResponse().setBody(response) }
                val selected = selector(server, temporary.newFolder()).select(story(30), "test", MODEL, 10) {}
                assertEquals(listOf(1, 3, 5, 7, 9, 13, 16, 20, 24, 28), selected.map { it.number })
                assertEquals(10, server.requestCount)
            }
        }

    @Test fun `weak chapters are skipped and scanning continues into later batches`() =
        runBlocking {
            MockWebServer().use { server ->
                // Even chapters carry evidence, odd ones do not: ten useful chapters exist in total.
                server.dispatcher =
                    chapterDispatcher { chapter ->
                        MockResponse().setBody(if (chapter % 2 == 0) response else weakResponse)
                    }
                val selected = selector(server, temporary.newFolder()).select(story(21), "test", MODEL, 10) {}
                assertEquals((2..20 step 2).toList(), selected.map { it.number })
                // Two spread batches find nine; the last unscanned chapter completes the target.
                assertEquals(21, server.requestCount)
            }
        }

    @Test fun `OpenRouter cost and served model are recorded exactly and cache reuse adds no receipt`() =
        runBlocking {
            MockWebServer().use { server ->
                val records = mutableListOf<AiUsageRecord>()
                val receipt =
                    response
                        .replace("{\"answers\":", "{\"id\":\"gen-dec-test\",\"model\":\"typesafe/jev-1.13-20260917\",\"answers\":")
                        .replace("\"output_tokens\":20", "\"output_tokens\":20,\"cost\":0.000016800123456789")
                server.enqueue(MockResponse().setBody(receipt))
                val selector =
                    CoverEvidenceSelector(
                        readChapter = { it.content },
                        saveUsage = { records += it },
                        client = CoverDecisionClient(OpenRouterClient(server.url("/").toString())),
                        cache = CoverEvidenceStore(temporary.newFolder()),
                    )
                selector.select(story(1), "openrouter-key", MODEL, 1) {}
                val record = records.single()
                assertEquals("cover_selection", record.feature)
                assertEquals("gen-dec-test", record.generationId)
                assertEquals("typesafe/jev-1.13-20260917", record.model)
                assertEquals("0.000016800123456789", record.costUsd)
                assertEquals(400L, record.promptTokens)
                assertEquals(20L, record.completionTokens)
                assertEquals("completed", record.outcome)
                selector.select(story(1), "replacement-openrouter-key", MODEL, 1) {}
                assertEquals(1, records.size)
                assertEquals(1, server.requestCount)
            }
        }

    @Test fun `missing or invalid costs remain unknown even on successful responses`() =
        runBlocking {
            MockWebServer().use { server ->
                val records = mutableListOf<AiUsageRecord>()
                for (cost in listOf("", ",\"cost\":-1", ",\"cost\":\"invalid\"")) {
                    server.enqueue(MockResponse().setBody(response.replace("\"output_tokens\":20", "\"output_tokens\":20$cost")))
                    CoverEvidenceSelector(
                        readChapter = { it.content },
                        saveUsage = { records += it },
                        client = CoverDecisionClient(OpenRouterClient(server.url("/").toString())),
                        cache = CoverEvidenceStore(temporary.newFolder()),
                    ).select(story(1), "test", MODEL, 1) {}
                }
                assertTrue(records.all { it.costUsd == null })
                assertTrue(records.all { it.outcome == "completed" })
            }
        }

    @Test fun `cached chapters resume a failed scan without billing again`() =
        runBlocking {
            MockWebServer().use { server ->
                // Target 1 judges one chapter per batch in scan order 1, 3, 2: chapter 1 is weak,
                // chapter 3 fails with 401 on the first scan and succeeds on the retry.
                val chapterRequests = mutableMapOf<Int, Int>()
                server.dispatcher =
                    chapterDispatcher { chapter ->
                        when {
                            chapter == 1 -> MockResponse().setBody(weakResponse)
                            chapterRequests.merge(chapter, 1, Int::plus) == 1 -> MockResponse().setResponseCode(401)
                            else -> MockResponse().setBody(response)
                        }
                    }
                val story = story(3)
                val folder = temporary.newFolder()
                val recorded = IntArray(1)
                assertTrue(
                    runCatching {
                        selector(server, folder, recorded = recorded).select(story, "test", MODEL, 1) {}
                    }.isFailure,
                )
                assertEquals(2, server.requestCount)
                assertEquals(2, recorded[0])
                val resumed = selector(server, folder, recorded = recorded).select(story, "test", MODEL, 1) {}
                // Chapter 1 was reused from the cache; only the previously failed chapter billed.
                assertEquals(3, server.requestCount)
                assertEquals(listOf(3), resumed.map { it.number })
                assertEquals(3, recorded[0])
                assertEquals(resumed, selector(server, folder).select(story, "test", MODEL, 1) {})
                assertEquals(3, server.requestCount)
            }
        }
}
