package com.vinicius741.webnovelarchiver.source

import com.vinicius741.webnovelarchiver.domain.model.ChapterInfo
import com.vinicius741.webnovelarchiver.domain.model.NovelMetadata
import com.vinicius741.webnovelarchiver.domain.model.SourceMetricKind
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import com.vinicius741.webnovelarchiver.source.network.NetworkParseException
import com.vinicius741.webnovelarchiver.source.network.SourceAccessBlockedException
import com.vinicius741.webnovelarchiver.source.network.SourceChapterListIncompleteException
import com.vinicius741.webnovelarchiver.source.network.SourceNetworkPolicy
import java.net.URI

/**
 * Ranobes (ranobes.net), a DataLife Engine site. The novel page carries the metadata and the newest
 * chapters; the complete table of contents is paginated, about 25 chapters per page, so an update
 * check reads one page and only a full sync walks them all.
 *
 * The site moved from ranobes.top, which still serves the same catalog. A link on the former address
 * belongs to this source: it names the same novel, and every request is made to ranobes.net, which
 * stays the first host because the network coordinator names a source's first host in its events.
 */
object RanobesProvider : SourceProvider {
    private const val ID_PREFIX = "ranobes_"

    /** 25 chapters a page: room for 10,000 chapters, and a bound on one full sync's requests. */
    private const val MAX_TOC_PAGES = 400

    override val descriptor =
        SourceDescriptor(
            id = "ranobes",
            displayName = "Ranobes",
            browseUrl = "https://ranobes.net",
            hosts = setOf("ranobes.net", "ranobes.top"),
            capabilities = SourceCapabilities(latestChapterSync = true),
            networkPolicy =
                SourceNetworkPolicy(
                    minimumRequestGapMillis = 1_500L,
                    maximumAttempts = 2,
                    retryableStatusCodes = setOf(429, 503),
                    maximumRequestsPerWindow = 30,
                ),
            featuredMetrics =
                listOf(
                    SourceMetricKind.READERS,
                    SourceMetricKind.TOTAL_VIEWS,
                    SourceMetricKind.BOOKMARKS,
                ),
        )

    /** A chapter link names its novel too, so pasting one imports the novel. */
    override fun classifyUrl(url: String): SourceUrlKind? = RanobesAddress.parse(url)?.let { SourceUrlKind.STORY }

    override fun normalizeStoryUrl(url: String): String = RanobesAddress.parse(url)?.storyUrl ?: url.trim()

    override fun getStoryId(url: String): String =
        RanobesAddress.parse(url)?.let { "$ID_PREFIX${it.novelId}" } ?: error("Ranobes novel URL was not recognized")

    override fun getChapterId(url: String): String? = RanobesAddress.parse(url)?.chapterId?.let { "$ID_PREFIX$it" }

    override fun parseMetadata(html: String): NovelMetadata = parseRanobesMetadata(html)

    override fun parseChapterContent(html: String): String = ranobesChapterContent(html)

    override suspend fun getChapterList(
        html: String,
        url: String,
        network: NetworkClient,
        progress: (String) -> Unit,
    ): List<ChapterInfo> {
        val novelId = ranobesNovelId(url)
        progress("Fetching chapter list page 1...")
        val first = ranobesTocPage(network.fetch("$baseUrl/chapters/$novelId/"), novelId, page = 1)
        // The declared page count is authoritative: stopping at a cap would hand back a short list,
        // which a full sync would treat as the author having deleted every chapter it never saw.
        if (first.pageCount > MAX_TOC_PAGES) {
            throw SourceChapterListIncompleteException(
                "Ranobes chapter list spans ${first.pageCount} pages, above the $MAX_TOC_PAGES-page fetch limit",
            )
        }
        val chapters = first.chapters.toMutableList()
        val seen = chapters.mapNotNull { it.id }.toMutableSet()
        for (page in 2..first.pageCount) {
            progress("Fetching chapter list page $page of ${first.pageCount} · ${chapterCountLabel(chapters.size)} found...")
            val next = ranobesTocPage(network.fetch("$baseUrl/chapters/$novelId/page/$page/"), novelId, page)
            // A chapter published while the pages are being read shifts the page boundary, so the
            // chapter at the seam arrives twice.
            next.chapters.filter { it.id?.let(seen::add) == true }.forEach(chapters::add)
        }
        if (chapters.size < first.totalCount) {
            throw SourceChapterListIncompleteException(
                "Ranobes lists ${first.totalCount} chapters but only ${chapters.size} could be read",
            )
        }
        if (chapters.isEmpty()) throw NetworkParseException("Ranobes lists no chapters for this novel")
        progress("${chapterCountLabel(chapters.size)} found")
        // The site lists newest first; the app reads a story from its first chapter.
        return chapters.asReversed().mapIndexed { index, chapter -> chapter.copy(chapterNumber = index + 1) }
    }

    override suspend fun getLatestChapterList(
        html: String,
        url: String,
        network: NetworkClient,
        progress: (String) -> Unit,
    ): List<ChapterInfo> {
        val novelId = ranobesNovelId(url)
        // The novel page already lists the newest chapters, with relative dates; the first
        // table-of-contents page holds the same ones with exact dates.
        val listed = parseRanobesLatestChapters(html, novelId)
        progress("Checking latest chapters...")
        return try {
            ranobesTocPage(network.fetch("$baseUrl/chapters/$novelId/"), novelId, page = 1).chapters.asReversed().ifEmpty { listed }
        } catch (_: SourceAccessBlockedException) {
            listed
        }
    }

    private fun ranobesNovelId(url: String): String =
        RanobesAddress.parse(url)?.novelId ?: throw NetworkParseException("Ranobes novel URL was not recognized")

    /** One table-of-contents page, refused unless it is the page that was asked for. */
    private fun ranobesTocPage(
        html: String,
        novelId: String,
        page: Int,
    ): RanobesTocPage {
        val toc = parseRanobesTocPage(html, novelId)
        if (toc.pageNumber != page) {
            throw NetworkParseException("Ranobes served chapter list page ${toc.pageNumber} instead of $page")
        }
        return toc
    }

    private fun chapterCountLabel(count: Int): String = "$count ${if (count == 1) "chapter" else "chapters"}"
}

/**
 * The novel, and the chapter when it is one, that a Ranobes URL addresses. The novel page is
 * `/novels/<id>-<slug>.html`, which the site also serves from its root, and a chapter is
 * `/<slug>-<id>/<chapter-id>.html`.
 */
internal class RanobesAddress(
    val novelId: String,
    private val slug: String,
    val chapterId: String?,
) {
    /** The novel page. The site redirects any other slug of [novelId] to this page. */
    val storyUrl: String get() = "${RanobesProvider.baseUrl}/novels/$novelId-$slug.html"

    /** The chapter page, or null when the address is the novel's. */
    val chapterUrl: String? get() = chapterId?.let { "${RanobesProvider.baseUrl}/$slug-$novelId/$it.html" }

    companion object {
        // Ids never start with a zero, so one novel cannot get two story ids.
        private val STORY_PATH = Regex("""^/(?:novels/)?([1-9]\d*)-([^/]+?)\.html/?$""", RegexOption.IGNORE_CASE)
        private val CHAPTER_PATH = Regex("""^/([^/]+)-([1-9]\d*)/([1-9]\d*)\.html/?$""", RegexOption.IGNORE_CASE)

        fun parse(url: String): RanobesAddress? {
            // The raw path keeps a percent-encoded slug encoded, so the URLs rebuilt from it stay valid.
            val path = runCatching { URI(url).rawPath?.takeIf { it.startsWith('/') } }.getOrNull() ?: return null
            STORY_PATH.matchEntire(path)?.let { return RanobesAddress(it.groupValues[1], it.groupValues[2], null) }
            CHAPTER_PATH.matchEntire(path)?.let {
                return RanobesAddress(it.groupValues[2], it.groupValues[1], it.groupValues[3])
            }
            return null
        }
    }
}
