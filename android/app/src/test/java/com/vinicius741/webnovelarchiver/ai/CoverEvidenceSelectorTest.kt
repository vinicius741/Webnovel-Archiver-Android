package com.vinicius741.webnovelarchiver.ai

import com.vinicius741.webnovelarchiver.data.storage.CoverEvidenceCache
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CoverEvidenceSelectorTest {
    @get:Rule val temporary = TemporaryFolder()
    private val response = """{"answers":{
      "appearance":{"type":"score","score":2},"premise":{"type":"score","score":1},
      "imagery":{"type":"score","score":1},"temporary":{"type":"noul","noul":0}},
      "usage":{"input_tokens":400,"output_tokens":20}}"""

    @Test fun `missing downloaded files direct the user to redownload without calling TypeSafe`() =
        runBlocking {
            MockWebServer().use { server ->
                val story = Story(chapters = mutableListOf(Chapter(id = "missing", downloaded = true)))
                val selector =
                    CoverEvidenceSelector(
                        readChapter = { null },
                        saveUsage = {},
                        client = TypeSafeCoverClient(server.url("/").toString()),
                        cache = CoverEvidenceCache(temporary.newFolder()),
                    )
                val failure = runCatching { selector.select(story, "test") {} }.exceptionOrNull()
                assertEquals("Downloaded chapter files are missing; re-download the novel's chapters", failure?.message)
                assertEquals(0, server.requestCount)
            }
        }

    @Test fun `in memory content remains usable when a chapter file is missing`() =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(response))
                val story =
                    Story(
                        chapters =
                            mutableListOf(
                                Chapter(id = "missing", downloaded = true),
                                Chapter(id = "fallback", downloaded = true, content = "<p>A copper automaton tends an orchard.</p>"),
                            ),
                    )
                val selector =
                    CoverEvidenceSelector(
                        readChapter = { null },
                        saveUsage = {},
                        client = TypeSafeCoverClient(server.url("/").toString()),
                        cache = CoverEvidenceCache(temporary.newFolder()),
                    )
                assertEquals(listOf(2), selector.select(story, "test") {}.map { it.number })
                assertEquals(1, server.requestCount)
            }
        }

    @Test fun `readable text without useful evidence keeps the manual selection guidance`() =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(response.replace("\"score\":2", "\"score\":0").replace("\"score\":1", "\"score\":0")))
                val story = Story(chapters = mutableListOf(Chapter(id = "readable", downloaded = true)))
                val selector =
                    CoverEvidenceSelector(
                        readChapter = { "<p>Author note</p>" },
                        saveUsage = {},
                        client = TypeSafeCoverClient(server.url("/").toString()),
                        cache = CoverEvidenceCache(temporary.newFolder()),
                    )
                val failure = runCatching { selector.select(story, "test") {} }.exceptionOrNull()
                assertEquals("No useful cover evidence found. Select context chapters manually in Generation options.", failure?.message)
                assertEquals(1, server.requestCount)
            }
        }

    @Test fun `scan resumes completed chapters after failure and repeat scan makes no calls`() =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(response))
                server.enqueue(MockResponse().setResponseCode(401))
                val story =
                    Story(
                        id = "s",
                        title = "Orchard",
                        chapters =
                            mutableListOf(
                                Chapter(
                                    id = "one",
                                    title = "One",
                                    downloaded = true,
                                    content = "<p>A copper automaton tends an orchard.</p>",
                                ),
                                Chapter(
                                    id = "two",
                                    title = "Two",
                                    downloaded = true,
                                    content = "<p>Friends build a farm beside a crystal mountain.</p>",
                                ),
                                Chapter(id = "missing", downloaded = false, content = "Must not be scanned"),
                            ),
                    )
                val folder = temporary.newFolder()
                var recorded = 0

                fun selector() =
                    CoverEvidenceSelector(
                        readChapter = { it.content },
                        saveUsage = { recorded++ },
                        client = TypeSafeCoverClient(server.url("/").toString()),
                        cache = CoverEvidenceCache(folder),
                    )
                assertTrue(runCatching { selector().select(story, "test") {} }.isFailure)
                assertEquals(2, server.requestCount)
                server.enqueue(MockResponse().setBody(response))
                val resumed = selector().select(story, "test") {}
                assertEquals(3, server.requestCount)
                assertEquals(listOf(1, 2), resumed.map { it.number })
                assertEquals(resumed, selector().select(story, "test") {})
                assertEquals(3, server.requestCount)
                assertEquals(3, recorded)
                story.chapters[1].content = "<p>Updated river valley scene</p>"
                server.enqueue(MockResponse().setBody(response))
                selector().select(story, "test") {}
                assertEquals(4, server.requestCount)
            }
        }
}
