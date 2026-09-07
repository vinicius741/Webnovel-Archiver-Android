package com.vinicius741.webnovelarchiver.source

import com.vinicius741.webnovelarchiver.domain.model.PublicationStatus
import com.vinicius741.webnovelarchiver.domain.model.SourceMetricKind
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import com.vinicius741.webnovelarchiver.source.network.NetworkParseException
import com.vinicius741.webnovelarchiver.source.network.SourceChapterListIncompleteException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Ao3ProviderTest {
    private fun fixture(name: String): String = requireNotNull(javaClass.getResource("/fixtures/ao3/$name.html")).readText()

    @Test
    fun resolvesWorkAndChapterImportsWithoutAcceptingOtherHostsOrRoutes() {
        listOf(
            "https://archiveofourown.org/works/123",
            "http://www.archiveofourown.org/works/123/chapters/900?view_full_work=true#work",
        ).forEach {
            val match = requireNotNull(SourceRegistry.resolve(it, SourceUrlKind.STORY))
            assertSame(Ao3Provider, match.provider)
            assertEquals("https://archiveofourown.org/works/123", match.normalizedUrl)
            assertEquals("ao3_123", Ao3Provider.getStoryId(it))
        }
        listOf(
            "https://evil.org/works/123",
            "https://archiveofourown.org.evil.org/works/123",
            "https://archiveofourown.org/series/123",
            "https://archiveofourown.org/chapters/900",
            "https://archiveofourown.org/works/123/bookmarks",
        ).forEach {
            assertNull(SourceRegistry.resolve(it))
        }
        assertEquals("ao3_chapter_900", Ao3Provider.getChapterId("https://archiveofourown.org/works/123/chapters/900"))
    }

    @Test
    fun parsesMetadataAndKeepsCountsDistinctFromRatings() {
        val metadata = Ao3Provider.parseMetadata(fixture("story"))
        assertEquals("A Test Voyage", metadata.title)
        assertEquals("Writer One, Writer Two", metadata.author)
        assertEquals("A journey.\n\nA return.", metadata.description)
        assertEquals(PublicationStatus.ongoing, metadata.publicationStatus)
        assertNull(metadata.score)
        assertNull(metadata.coverUrl)
        assertEquals(listOf("Example Fandom"), metadata.sourceMetadata.fandoms)
        assertEquals(listOf("No Archive Warnings Apply"), metadata.sourceMetadata.contentWarnings)
        assertTrue(metadata.tags.orEmpty().contains("Alex & Sam"))
        assertEquals("General Audiences", metadata.sourceMetadata.contentRating)
        assertEquals("English", metadata.sourceMetadata.language)
        assertEquals(
            42L,
            metadata.sourceMetadata.metrics
                .single { it.kind == SourceMetricKind.KUDOS }
                .value,
        )
        assertEquals(
            0L,
            metadata.sourceMetadata.metrics
                .single { it.kind == SourceMetricKind.COMMENTS }
                .value,
        )
        assertEquals(
            2001L,
            metadata.sourceMetadata.metrics
                .single { it.kind == SourceMetricKind.TOTAL_VIEWS }
                .value,
        )
        assertNotNull(metadata.sourceMetadata.publishedAt)
        assertNotNull(metadata.sourceMetadata.updatedAt)
        assertEquals(PublicationStatus.completed, Ao3Provider.parseMetadata(fixture("story").replace("2/3", "2/2")).publicationStatus)
        assertEquals(PublicationStatus.ongoing, Ao3Provider.parseMetadata(fixture("story").replace("2/3", "2/?")).publicationStatus)
    }

    @Test
    fun indexPreservesOrderingDatesAndStableIds() {
        val chapters = parseAo3ChapterIndex(fixture("navigate"), WORK, 2)
        assertEquals(listOf("ao3_chapter_900", "ao3_chapter_950"), chapters.map { it.id })
        assertEquals(listOf("Departure", "Arrival"), chapters.map { it.title })
        assertEquals(listOf(1, 2), chapters.map { it.chapterNumber })
        assertNotNull(chapters.first().publishedAt)
        assertEquals("$WORK/chapters/950", chapters.last().url)
    }

    @Test
    fun rejectsIncompleteDuplicateAndForeignChapterIndexes() {
        listOf(
            fixture("navigate").replace("/chapters/950", "/chapters/900"),
            fixture("navigate").replace("/works/123/chapters/950", "/works/456/chapters/950"),
            "<html>Login required</html>",
        ).forEach {
            assertThrows(SourceChapterListIncompleteException::class.java) { parseAo3ChapterIndex(it, WORK, 2) }
        }
        assertThrows(SourceChapterListIncompleteException::class.java) { parseAo3ChapterIndex(fixture("navigate"), WORK, 3) }
    }

    @Test
    fun extractsOnlyChapterBodyAndPreservesFormatting() {
        val body = Ao3Provider.parseChapterContent(fixture("chapter"))
        assertTrue(body.contains("<em>voyage</em>"))
        assertTrue(body.contains("<br>"))
        assertTrue(body.contains("https://archiveofourown.org/images/example.png"))
        listOf(
            "Author notes",
            "End notes",
            "Work summary",
            "Reader comment",
            "Chapter Text",
            "tracking",
        ).forEach { assertFalse(body.contains(it)) }
        assertTrue(Ao3Provider.parseChapterContent(fixture("story")).contains("Story body."))
        assertThrows(NetworkParseException::class.java) { Ao3Provider.parseChapterContent("<html>Login required</html>") }
        assertThrows(NetworkParseException::class.java) { Ao3Provider.parseMetadata("<html>Unrevealed work</html>") }
    }

    @Test
    fun loadingUsesCanonicalWorkAndCompleteIndexEvenForUpdateChecks() =
        runBlocking {
            val requests = mutableListOf<String>()
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        requests.add(chain.request().url.toString())
                        val body =
                            if (chain
                                    .request()
                                    .url.encodedPath
                                    .endsWith("/navigate")
                            ) {
                                fixture("navigate")
                            } else {
                                fixture("story")
                            }
                        Response
                            .Builder()
                            .request(
                                chain.request(),
                            ).protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(body.toResponseBody())
                            .build()
                    }.build()
            val story = Ao3Provider.loadStory("$WORK/chapters/950", true, NetworkClient(client = client, sleep = {}))
            assertEquals(WORK, story.metadata.canonicalUrl)
            assertFalse(story.chaptersAreLatestOnly)
            assertEquals(2, story.chapters.size)
            assertEquals(listOf("$WORK/navigate?view_adult=true", "$WORK/chapters/900?view_adult=true"), requests)
            assertEquals(story.chapters, story.loadFullChapterList())
        }

    @Test
    fun singleChapterIdSurvivesExpansionAndFullWorkPagesAreRejected() {
        val index = fixture("navigate")
        val singleIndex = index.substringBefore("<li><a href=\"/works/123/chapters/950") + "</ol></body></html>"
        val single = parseAo3ChapterIndex(singleIndex, WORK, 1)
        assertEquals(single.first().id, parseAo3ChapterIndex(index, WORK, 2).first().id)
        val fullWork =
            fixture(
                "chapter",
            ).replace("</div></div></div>", "<div class='userstuff' role='article'><p>Another chapter</p></div></div></div></div>")
        assertThrows(NetworkParseException::class.java) { Ao3Provider.parseChapterContent(fullWork) }
    }

    @Test
    fun downloadUsesChapterUrlAndHonorsRequestGate() =
        runBlocking {
            var requestedUrl = ""
            var gateCalls = 0
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        requestedUrl = chain.request().url.toString()
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(fixture("chapter").toResponseBody())
                            .build()
                    }.build()
            val chapter =
                com.vinicius741.webnovelarchiver.domain.model
                    .Chapter(id = "ao3_chapter_950", url = "$WORK/chapters/950")
            val content =
                Ao3Provider.fetchChapterContent(WORK, chapter, 1, NetworkClient(client = client, sleep = {})) { claim ->
                    gateCalls++
                    claim()
                }
            assertTrue(content.contains("voyage"))
            assertEquals("$WORK/chapters/950?view_adult=true", requestedUrl)
            assertEquals(1, gateCalls)
        }

    companion object {
        private const val WORK = "https://archiveofourown.org/works/123"
    }
}
