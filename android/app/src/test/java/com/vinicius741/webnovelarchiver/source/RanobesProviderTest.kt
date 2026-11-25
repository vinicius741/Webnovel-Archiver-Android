package com.vinicius741.webnovelarchiver.source

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.ChapterInfo
import com.vinicius741.webnovelarchiver.domain.model.PublicationStatus
import com.vinicius741.webnovelarchiver.domain.model.SourceMetricKind
import com.vinicius741.webnovelarchiver.source.network.HttpNetworkException
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import com.vinicius741.webnovelarchiver.source.network.NetworkParseException
import com.vinicius741.webnovelarchiver.source.network.SourceAccessBlockedException
import com.vinicius741.webnovelarchiver.source.network.SourceChapterListIncompleteException
import com.vinicius741.webnovelarchiver.sync.StorySyncPlanning
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RanobesProviderTest {
    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/ranobes/$name.html")).bufferedReader().use { it.readText() }

    /** Answers the site's real URLs with fixture pages and records each request; no socket is opened. */
    private class Site(
        private val respond: (path: String) -> String?,
    ) : Interceptor {
        val requests = mutableListOf<String>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val path = chain.request().url.encodedPath
            requests.add(path)
            val body = respond(path)
            return Response
                .Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(if (body == null) 403 else 200)
                .message("")
                .body(body.orEmpty().toResponseBody())
                .build()
        }

        companion object {
            val pages: (String) -> String? = { path ->
                when (path) {
                    "/novels/24680-the-lorem-fox.html" -> "story"
                    "/chapters/24680/" -> "toc-page1"
                    "/chapters/24680/page/2/" -> "toc-page2"
                    else -> null
                }
            }
        }
    }

    /** The pacing waits move a stand-in clock, so no test sleeps the source's request gap. */
    private fun network(site: Site): NetworkClient {
        var now = 0L
        return NetworkClient(
            client = OkHttpClient.Builder().addInterceptor(site).build(),
            sleep = { now += it },
            nowMillis = { now },
        )
    }

    private fun fixtureSite(): Site =
        Site { path ->
            val name = Site.pages(path) ?: return@Site null
            fixture(name)
        }

    @Test
    fun resolvesNovelAndChapterLinksAndRejectsEveryOtherAddress() {
        val story = "https://ranobes.net/novels/24680-the-lorem-fox.html"
        val wrongSlug = "https://ranobes.net/novels/24680-another-slug.html"
        mapOf(
            story to story,
            "http://www.ranobes.net/24680-the-lorem-fox.html?from=share#top" to story,
            "https://ranobes.net/the-lorem-fox-24680/370002.html" to story,
            wrongSlug to wrongSlug,
        ).forEach { (submitted, normalized) ->
            val match = requireNotNull(SourceRegistry.resolve(submitted, SourceUrlKind.STORY))
            assertSame(RanobesProvider, match.provider)
            assertEquals(normalized, match.normalizedUrl)
            assertEquals("ranobes_24680", RanobesProvider.getStoryId(submitted))
        }
        assertSame(RanobesProvider, SourceRegistry.providerForHost("www.ranobes.net"))
        assertEquals("ranobes_370002", RanobesProvider.getChapterId("https://ranobes.net/the-lorem-fox-24680/370002.html"))
        assertNull(RanobesProvider.getChapterId(story))

        listOf(
            // The mirror serves the same catalog, but behind another protection service.
            "https://ranobes.top/novels/24680-the-lorem-fox.html",
            "https://ranobes.net.evil.org/novels/24680-the-lorem-fox.html",
            "https://example.com/import?next=$story",
            "https://ranobes.net/chapters/24680/",
            "https://ranobes.net/chapters/24680/first",
            "https://ranobes.net/novels/24680.html",
            "https://ranobes.net/tags/genre/Adventure/",
            "https://ranobes.net/",
        ).forEach {
            assertNull(it, SourceRegistry.resolve(it))
            assertFalse(it, SourceUrlValidation.isImportableStoryUrl(it))
        }
    }

    @Test
    fun parsesNovelMetadataWithCountersStatusAndUtcDates() {
        val metadata = RanobesProvider.parseMetadata(fixture("story"))

        assertEquals("The Lorem Fox", metadata.title)
        assertEquals("Fixture Author", metadata.author)
        assertEquals("https://ranobes.net/uploads/posts/2024-02/fixture-lorem-fox.jpg", metadata.coverUrl)
        assertEquals("A fixture synopsis opens here.\n\nIt continues in a second paragraph.", metadata.description)
        assertEquals("https://ranobes.net/novels/24680-the-lorem-fox.html", metadata.canonicalUrl)
        assertEquals("4.5", metadata.score)
        assertNull(metadata.patreonUrl)
        assertEquals(PublicationStatus.ongoing, metadata.publicationStatus)
        assertEquals(listOf("Adventure", "Fantasy", "Clever Protagonist", "System"), metadata.tags.orEmpty())

        val source = metadata.sourceMetadata
        assertEquals("Ongoing", source.sourceStatus)
        assertEquals("English", source.language)
        assertEquals(listOf("Adventure", "Fantasy"), source.genres)
        assertEquals(Instant.parse("2024-02-03T09:15:00Z").toEpochMilli(), source.publishedAt)
        assertEquals(Instant.parse("2026-08-20T18:30:00Z").toEpochMilli(), source.updatedAt)

        fun metric(kind: SourceMetricKind): Long? = source.metrics.firstOrNull { it.kind == kind }?.value

        // The site separates thousands with spaces.
        assertEquals(12_345L, metric(SourceMetricKind.READERS))
        assertEquals(234_567L, metric(SourceMetricKind.TOTAL_VIEWS))
        assertEquals(42L, metric(SourceMetricKind.COMMENTS))
        assertEquals(12L, metric(SourceMetricKind.RATINGS))
        assertEquals(345L, metric(SourceMetricKind.BOOKMARKS))
    }

    @Test
    fun readsTheStatusFromItsLinkAndFallsBackToHeadlineAndMicrodata() {
        val completed =
            RanobesProvider.parseMetadata(
                fixture("story").replace("status-end/Ongoing/\">Ongoing</a>", "status-end/Completed/\">Completed</a>"),
            )
        assertEquals(PublicationStatus.completed, completed.publicationStatus)
        assertEquals("Completed", completed.sourceMetadata.sourceStatus)

        val sparse =
            RanobesProvider.parseMetadata(
                """
                <html><body><div class="block r-fullstory"><h1 class="title">Sparse Novel <span class="subtitle">by Headline Author</span></h1>
                <div data-tv-field="genre"><meta itemprop="genre" content="Adventure"><meta itemprop="genre" content="Fantasy"></div>
                <div data-tv-field="events"><meta itemprop="keywords" content="Clever Protagonist, System, "></div>
                </div></body></html>
                """.trimIndent(),
            )
        assertEquals("Sparse Novel", sparse.title)
        assertEquals("Headline Author", sparse.author)
        assertEquals(PublicationStatus.unknown, sparse.publicationStatus)
        assertEquals(listOf("Adventure", "Fantasy", "Clever Protagonist", "System"), sparse.tags.orEmpty())
        assertNull(sparse.description)
        assertNull(sparse.score)
        assertTrue(sparse.sourceMetadata.metrics.isEmpty())
    }

    @Test
    fun rejectsPagesThatAreNotANovel() {
        listOf(
            "<html><body><title>Attention Required</title>Please sign in</body></html>",
            fixture("chapter"),
            fixture("toc-page1"),
            "<html><body><div class=\"r-fullstory\"><h1 class=\"title\"> <span class=\"subtitle\">by X</span></h1></div></body></html>",
        ).forEach {
            assertThrows(NetworkParseException::class.java) { RanobesProvider.parseMetadata(it) }
        }
    }

    @Test
    fun parsesATableOfContentsPageInTheSitesNewestFirstOrder() {
        val page = parseRanobesTocPage(fixture("toc-page1"), "24680")

        assertEquals(listOf("ranobes_370003", "ranobes_370002"), page.chapters.map { it.id })
        assertEquals(listOf("Chapter 3: The Third Hour", "Chapter 2: The Second Hour"), page.chapters.map { it.title })
        assertEquals("https://ranobes.net/the-lorem-fox-24680/370003.html", page.chapters.first().url)
        assertEquals(Instant.parse("2026-08-20T18:30:00Z").toEpochMilli(), page.chapters.first().publishedAt)
        assertEquals(RanobesTocPage(page.chapters, pageNumber = 1, pageCount = 2, totalCount = 3), page)
    }

    @Test
    fun rejectsAChapterListItCannotReadChapterByChapter() {
        val foreignNovel = "99999"
        listOf(
            "<html><body>None chapters was added at this moment :(</body></html>" to "24680",
            // The data block is empty: a page that lost its list.
            fixture("toc-page1").replace(Regex("""\{"book_title".*"""), "") to "24680",
            // The page is another novel's.
            fixture("toc-page1") to foreignNovel,
            // A chapter link that is not a chapter of the novel page it was listed on.
            fixture("toc-page1").replace("/the-lorem-fox-24680/370002.html", "/another-fox-99999/370002.html") to "24680",
            fixture("toc-page1").replace("\"count_all\":3", "\"count_all\":\"many\"") to "24680",
        ).forEach { (html, novelId) ->
            val error = assertThrows(NetworkParseException::class.java) { parseRanobesTocPage(html, novelId) }
            assertTrue(error.message.orEmpty(), error.message.orEmpty().startsWith("Ranobes"))
        }
    }

    @Test
    fun fullChapterListWalksEveryPageAndReturnsReadingOrder() =
        runBlocking {
            val site = fixtureSite()
            val progress = mutableListOf<String>()

            val chapters = RanobesProvider.getChapterList(fixture("story"), STORY, network(site), progress::add)

            assertEquals(listOf("ranobes_370001", "ranobes_370002", "ranobes_370003"), chapters.map { it.id })
            assertEquals(listOf(1, 2, 3), chapters.map { it.chapterNumber })
            assertEquals("Chapter 1: The First Hour", chapters.first().title)
            assertEquals("https://ranobes.net/the-lorem-fox-24680/370001.html", chapters.first().url)
            assertEquals(Instant.parse("2024-02-03T09:20:11Z").toEpochMilli(), chapters.first().publishedAt)
            assertEquals(listOf("/chapters/24680/", "/chapters/24680/page/2/"), site.requests)
            assertEquals(
                listOf(
                    "Fetching chapter list page 1...",
                    "Fetching chapter list page 2 of 2 · 2 chapters found...",
                    "3 chapters found",
                ),
                progress,
            )
        }

    @Test
    fun fullChapterListFailsInsteadOfComingBackShort() {
        // The last page lost its chapter.
        val short =
            Site { path ->
                when (path) {
                    "/chapters/24680/" -> fixture("toc-page1")
                    else -> fixture("toc-page2").replace(Regex("""\{"id":"370001"[^}]*\}"""), "")
                }
            }
        assertThrows(SourceChapterListIncompleteException::class.java) {
            runBlocking { RanobesProvider.getChapterList(fixture("story"), STORY, network(short)) }
        }

        // The site ignored the page number and answered with the first page again.
        val repeated = Site { fixture("toc-page1") }
        val error =
            assertThrows(NetworkParseException::class.java) {
                runBlocking { RanobesProvider.getChapterList(fixture("story"), STORY, network(repeated)) }
            }
        assertTrue(error.message.orEmpty(), "instead of 2" in error.message.orEmpty())

        // A withheld page ends the walk there.
        val withheld =
            Site { path ->
                if (path == "/chapters/24680/page/2/") null else Site.pages(path)?.let(::fixture)
            }
        val failure =
            assertThrows(HttpNetworkException::class.java) {
                runBlocking { RanobesProvider.getChapterList(fixture("story"), STORY, network(withheld)) }
            }
        assertEquals(403, failure.statusCode)
        assertEquals(listOf("/chapters/24680/", "/chapters/24680/page/2/"), withheld.requests)

        // A list too long to walk is refused before its second page is requested.
        val tooLong =
            Site { path ->
                Site.pages(path)?.let { fixture(it).replace("\"pages_count\":2", "\"pages_count\":401") }
            }
        assertThrows(SourceChapterListIncompleteException::class.java) {
            runBlocking { RanobesProvider.getChapterList(fixture("story"), STORY, network(tooLong)) }
        }
        assertEquals(listOf("/chapters/24680/"), tooLong.requests)
    }

    @Test
    fun fullChapterListKeepsAChapterPublishedWhileThePagesWereRead() =
        runBlocking {
            // The newest chapter arrived after page 1 was read, so page 2 begins with the chapter
            // page 1 ended with.
            val seam =
                Site { path ->
                    when (path) {
                        "/chapters/24680/" -> fixture("toc-page1")
                        else ->
                            fixture("toc-page2").replace(
                                "\"chapters\":[",
                                "\"chapters\":[{\"id\":\"370002\",\"title\":\"Chapter 2: The Second Hour\",\"date\":\"2025-06-11 07:05:09\",\"link\":\"https://ranobes.net/the-lorem-fox-24680/370002.html\"},",
                            )
                    }
                }

            val chapters = RanobesProvider.getChapterList(fixture("story"), STORY, network(seam))

            assertEquals(listOf("ranobes_370001", "ranobes_370002", "ranobes_370003"), chapters.map { it.id })
            assertEquals(listOf(1, 2, 3), chapters.map { it.chapterNumber })
        }

    @Test
    fun aNovelWithoutChaptersIsReportedAsSuch() {
        val empty =
            Site {
                fixture("toc-page1")
                    .replace(Regex("\"chapters\":\\[.*?\\]"), "\"chapters\":[]")
                    .replace("\"pages_count\":2", "\"pages_count\":1")
                    .replace("\"count_all\":3", "\"count_all\":0")
            }
        val progress = mutableListOf<String>()

        val error =
            assertThrows(NetworkParseException::class.java) {
                runBlocking { RanobesProvider.getChapterList(fixture("story"), STORY, network(empty), progress::add) }
            }

        assertTrue(error.message.orEmpty(), "no chapters" in error.message.orEmpty())
        assertEquals(listOf("Fetching chapter list page 1..."), progress)
    }

    @Test
    fun latestChaptersReadOneTableOfContentsPageAndFallBackToTheNovelPage() =
        runBlocking {
            val site = Site { path -> Site.pages(path)?.let(::fixture) }
            val progress = mutableListOf<String>()

            val latest =
                requireNotNull(
                    RanobesProvider.getLatestChapterList(fixture("story"), STORY, network(site), progress::add),
                )

            assertEquals(listOf("ranobes_370002", "ranobes_370003"), latest.map { it.id })
            assertEquals(Instant.parse("2025-06-11T07:05:09Z").toEpochMilli(), latest.first().publishedAt)
            assertNull(latest.first().chapterNumber)
            assertEquals(listOf("/chapters/24680/"), site.requests)
            assertEquals(listOf("Checking latest chapters..."), progress)

            // The table of contents is blocked: the novel page's own list answers, with estimated dates.
            val blocked = Site { throw SourceAccessBlockedException("https://ranobes.net$it") }
            val listed = requireNotNull(RanobesProvider.getLatestChapterList(fixture("story"), STORY, network(blocked)))

            assertEquals(listOf("ranobes_370001", "ranobes_370002", "ranobes_370003"), listed.map { it.id })
            assertEquals("https://ranobes.net/the-lorem-fox-24680/370001.html", listed.first().url)
            assertTrue(listed.all { it.publishedAt != null })
        }

    @Test
    fun latestChaptersOverlapTheStoredNovelInReadingOrder() =
        runBlocking {
            val network = network(Site { path -> Site.pages(path)?.let(::fixture) })
            val full = RanobesProvider.getChapterList(fixture("story"), STORY, network)
            val stored =
                full.map {
                    Chapter(id = requireNotNull(it.id), title = it.title, url = it.url)
                }
            val latest = RanobesProvider.getLatestChapterList(fixture("story"), STORY, network).orEmpty()

            val unchanged = requireNotNull(StorySyncPlanning.mergeLatestChapters(stored, latest, RanobesProvider, null))
            assertEquals(full.map { it.id }, unchanged.chapters.map { it.id })
            assertTrue(unchanged.newChapterIds.isEmpty())

            // A fourth chapter arrives after the three already stored.
            val fourth = ChapterInfo(id = "ranobes_370004", title = "Chapter 4", url = "$NOVEL_DIR/370004.html")
            val grown = requireNotNull(StorySyncPlanning.mergeLatestChapters(stored, latest + listOf(fourth), RanobesProvider, null))
            assertEquals(
                listOf("ranobes_370001", "ranobes_370002", "ranobes_370003", "ranobes_370004"),
                grown.chapters.map { it.id },
            )
            assertEquals(listOf("ranobes_370004"), grown.newChapterIds)
        }

    @Test
    fun extractsChapterTextWithoutAdvertisementsOrNavigation() {
        val body = RanobesProvider.parseChapterContent(fixture("chapter"))

        assertTrue(body.contains("<p>The <em>first</em> hour begins.</p>"))
        assertTrue(body, Regex("""Second paragraph\.\s*<br>\s*Next line\.""").containsMatchIn(body))
        assertTrue(body.contains("<img src=\"https://ranobes.net/uploads/posts/2024-02/fixture-illustration.png\""))
        assertTrue(body.contains("Third paragraph, after the advertisement."))
        listOf(
            "adPlacement",
            "free-support-top",
            "bg-ssp",
            "Bookmark",
            "Chapters list",
            "reader comment",
            "The Lorem Fox",
        ).forEach {
            assertFalse(it, body.contains(it))
        }
        assertThrows(NetworkParseException::class.java) {
            RanobesProvider.parseChapterContent("<html><body><div id=\"text\">Please sign in</div></body></html>")
        }
        assertThrows(NetworkParseException::class.java) {
            RanobesProvider.parseChapterContent(
                "<html><body><div id=\"arrticle\"><div class=\"free-support-top\"><script>ad()</script></div></div></body></html>",
            )
        }
    }

    @Test
    fun loadStoryImportsTheWholeNovelThenChecksUpdatesFromOneTableOfContentsPage() =
        runBlocking {
            val site = fixtureSite()
            val network = network(site)

            val imported = RanobesProvider.loadStory(STORY, preferLatestChapters = false, network = network)

            assertFalse(imported.chaptersAreLatestOnly)
            assertEquals("The Lorem Fox", imported.metadata.title)
            assertEquals(3, imported.chapters.size)
            assertEquals(
                listOf("/novels/24680-the-lorem-fox.html", "/chapters/24680/", "/chapters/24680/page/2/"),
                site.requests,
            )

            // An update check opened from a chapter link reads the novel page and one table of
            // contents page. The sync engine hands the provider the novel link the chapter resolves to.
            site.requests.clear()
            val novelOfChapter = requireNotNull(SourceRegistry.resolve("$NOVEL_DIR/370002.html")).normalizedUrl
            val update = RanobesProvider.loadStory(novelOfChapter, preferLatestChapters = true, network = network)

            assertTrue(update.chaptersAreLatestOnly)
            assertEquals(listOf("ranobes_370002", "ranobes_370003"), update.chapters.map { it.id })
            assertEquals(listOf("/novels/24680-the-lorem-fox.html", "/chapters/24680/"), site.requests)
            assertEquals(imported.chapters, update.loadFullChapterList())
        }

    @Test
    fun readsSiteTimestampsAsUtcAndCountersWithSpacedThousands() {
        val updated = Instant.parse("2026-11-24T16:47:00Z").toEpochMilli()
        assertEquals(updated, ranobesTimestamp("2026-11-24T16:47:00+00:00"))
        assertEquals(updated, ranobesTimestamp("2026-11-24 16:47:00"))
        assertEquals(updated, ranobesTimestamp(" 24 November 2026, 16:47 "))
        assertEquals(Instant.parse("2021-03-16T13:09:00Z").toEpochMilli(), ranobesTimestamp("16 March 2021, 13:09"))
        assertEquals(Instant.parse("2026-11-24T15:47:00Z").toEpochMilli(), ranobesTimestamp("2026-11-24T16:47:00+01:00"))
        assertNull(ranobesTimestamp("9 hours ago"))
        assertNull(ranobesTimestamp(""))
        assertNull(ranobesTimestamp(null))

        assertEquals(1_995_392L, ranobesCounter("1 995 392"))
        assertEquals(264_706L, ranobesCounter("264 706"))
        // A non-breaking space between the thousands reads the same.
        assertEquals(264_706L, ranobesCounter("264" + 0x00A0.toChar() + "706"))
        assertEquals(2_500L, ranobesCounter("2.5K"))
        assertEquals(1_240L, ranobesCounter("1,240"))
        assertEquals(12L, ranobesCounter("12 ratings"))
        assertNull(ranobesCounter("chapters"))
        assertNull(ranobesCounter(null))
    }

    companion object {
        private const val STORY = "https://ranobes.net/novels/24680-the-lorem-fox.html"
        private const val NOVEL_DIR = "https://ranobes.net/the-lorem-fox-24680"
    }
}
