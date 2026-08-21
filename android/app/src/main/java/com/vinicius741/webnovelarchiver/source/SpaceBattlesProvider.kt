package com.vinicius741.webnovelarchiver.source

import com.vinicius741.webnovelarchiver.domain.archive.PercentEncoding
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.ChapterInfo
import com.vinicius741.webnovelarchiver.domain.model.NovelMetadata
import com.vinicius741.webnovelarchiver.domain.model.PublicationStatus
import com.vinicius741.webnovelarchiver.domain.model.SourceMetadata
import com.vinicius741.webnovelarchiver.domain.model.SourceMetric
import com.vinicius741.webnovelarchiver.domain.model.SourceMetricKind
import com.vinicius741.webnovelarchiver.source.network.HttpNetworkException
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import com.vinicius741.webnovelarchiver.source.network.NetworkException
import com.vinicius741.webnovelarchiver.source.network.NetworkParseException
import com.vinicius741.webnovelarchiver.source.network.NetworkRequestGate
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/**
 * SpaceBattles stories are forum threads, so only the main Threadmarks category is treated as the
 * novel. Ordinary replies and optional sidestory/apocrypha categories are deliberately excluded.
 */
@Suppress("TooManyFunctions")
object SpaceBattlesProvider : SourceProvider {
    override val descriptor = spaceBattlesSourceDescriptor

    override fun classifyUrl(url: String): SourceUrlKind? =
        when {
            Regex(
                """^https?://(?:(?:forum|forums)\.)?spacebattles\.com/threads/(?:[^/?#]*\.)?\d+/?(?:[?#].*)?$""",
                RegexOption.IGNORE_CASE,
            ).matches(url) -> SourceUrlKind.STORY
            SPACEBATTLES_HOST.containsMatchIn(url) && rawPostId(url) != null -> SourceUrlKind.CHAPTER
            else -> null
        }

    override fun getStoryId(url: String): String =
        THREAD_ID
            .find(url)
            ?.groupValues
            ?.get(1)
            ?.let { "sb_$it" }
            ?: "sb_url_${PercentEncoding.encodeURIComponent(url.lowercase())}"

    override fun getChapterId(url: String): String? = rawPostId(url)?.let { "sb_$it" }

    override fun parseMetadata(html: String): NovelMetadata = parseMetadata(html, null)

    /**
     * Parses metadata when the caller already has the existing main-category RSS response. The
     * optional response is injected rather than fetched here: [parseMetadata] is a synchronous
     * parser, and SpaceBattles' RSS request is already part of latest-chapter sync.
     */
    internal fun parseMetadata(
        html: String,
        mainCategoryRss: String?,
    ): NovelMetadata {
        val doc = Jsoup.parse(html, baseUrl)
        val title =
            doc
                .selectFirst(".p-title-value, h1.p-title-value, h1")
                ?.ownText()
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?: "Unknown Title"
        val author =
            doc
                .selectFirst(".p-description a.username[data-user-id], .p-description a.username")
                ?.text()
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: "Unknown Author"
        val description =
            doc
                .selectFirst(".threadmarkListingHeader-extraInfoChild .bbWrapper")
                ?.blockText()
                ?.takeIf(String::isNotBlank)
        val tags =
            doc
                .select(".tagList a.tagItem")
                .mapNotNull(::cleanTag)
                .distinct()
                .toMutableList()
                .takeIf { it.isNotEmpty() }
        val canonical =
            doc
                .selectFirst("link[rel=canonical]")
                ?.let { safeAbsoluteUrl(it, "href") }
                ?: doc.selectFirst("meta[property=og:url]")?.attr("content")?.ifBlank { null }
        val stats = doc.select(".threadmarkListingHeader-stats dl")
        val words = sourceStatElement(stats, "Words")
        val watchers = sourceStatElement(stats, "Watchers")
        val created = sourceStatElement(stats, "Created")
        val statusText = sourceStatText(stats, "Status")
        val sourceMetadata =
            SourceMetadata(
                metrics =
                    buildList {
                        parseSourceMetricValue(watchers?.text())?.let { value ->
                            add(SourceMetric(SourceMetricKind.WATCHERS, value))
                        }
                        (parseSourceMetricValue(words?.text()) ?: spaceBattlesThreadmarkWordCount(doc))?.let { value ->
                            add(SourceMetric(SourceMetricKind.WORDS, value))
                        }
                    }.toMutableList(),
                createdAt = created?.sourceDateMillis(),
                updatedAt =
                    mainCategoryRss?.let { parseThreadmarkRssUpdatedAt(it) }
                        ?: spaceBattlesLatestMainThreadmarkAt(doc),
                sourceType = sourceStatText(stats, "Type") ?: threadPrefix(doc),
                sourceCategory = sourceStatText(stats, "Category") ?: threadCategory(doc),
                sourceListingState = threadDiscussionState(doc),
                sourceStatus = statusText,
            )
        val coverUrl = spaceBattlesCoverUrl(doc, author)
        return NovelMetadata(
            title = title,
            author = author,
            coverUrl = coverUrl,
            description = description,
            tags = tags,
            canonicalUrl = canonical,
            patreonUrl = null,
            publicationStatus = publicationStatusFromSourceText(statusText) ?: PublicationStatus.unknown,
            sourceMetadata = sourceMetadata,
        )
    }

    override suspend fun enrichMetadata(
        metadata: NovelMetadata,
        html: String,
        url: String,
        network: NetworkClient,
        progress: (String) -> Unit,
    ): NovelMetadata {
        val root = storyRoot(Jsoup.parse(html, url), url)
        progress("Fetching main threadmark RSS...")
        val rss = fetchOptional(network, "${root}threadmarks.rss?threadmark_category=$MAIN_CATEGORY")
        val sourceMetadata = parseMetadata(html, rss).sourceMetadata

        progress("Fetching Story Library engagement...")
        val origin = ORIGIN.find(root)?.value ?: baseUrl
        val libraryUrl = "$origin/library/stories/?starter=${PercentEncoding.encodeURIComponent(metadata.author)}"
        val libraryHtml = fetchOptional(network, libraryUrl)
        val threadId = THREAD_ID.find(root)?.groupValues?.get(1)
        val libraryMetrics =
            if (libraryHtml != null && threadId != null) {
                parseSpaceBattlesLibraryMetrics(libraryHtml, threadId)
            } else {
                emptyList()
            }
        return metadata.copy(sourceMetadata = mergeSpaceBattlesMetrics(sourceMetadata, libraryMetrics))
    }

    private suspend fun fetchOptional(
        network: NetworkClient,
        url: String,
    ): String? =
        try {
            network.fetch(url)
        } catch (error: CancellationException) {
            throw error
        } catch (_: NetworkException) {
            null
        }

    override suspend fun getChapterList(
        html: String,
        url: String,
        network: NetworkClient,
        progress: (String) -> Unit,
    ): List<ChapterInfo> {
        val root = storyRoot(Jsoup.parse(html, url), url)
        val firstUrl = threadmarkListUrl(root, 1)
        progress("Fetching threadmark page 1...")
        val firstHtml = network.fetch(firstUrl)
        val chapters = parseThreadmarks(firstHtml, root).toMutableList()
        val seen = chapters.mapNotNull { it.id }.toMutableSet()
        val lastPage = threadmarkPageCount(firstHtml).coerceAtMost(MAX_THREADMARK_PAGES)
        for (page in 2..lastPage) {
            progress("Fetching threadmark page $page of $lastPage · ${chapterCountLabel(chapters.size)} found...")
            parseThreadmarks(network.fetch(threadmarkListUrl(root, page)), root)
                .filter { it.id != null && seen.add(it.id) }
                .let(chapters::addAll)
        }
        if (chapters.isEmpty()) {
            throw NetworkParseException("No main SpaceBattles threadmarks were found")
        }
        progress("${chapterCountLabel(chapters.size)} found")
        return chapters
    }

    override suspend fun getLatestChapterList(
        html: String,
        url: String,
        network: NetworkClient,
        progress: (String) -> Unit,
    ): List<ChapterInfo> {
        val root = storyRoot(Jsoup.parse(html, url), url)
        progress("Checking latest main threadmarks...")
        val rss = network.fetch("${root}threadmarks.rss?threadmark_category=$MAIN_CATEGORY")
        return parseThreadmarkRss(rss, root)
    }

    override fun parseChapterContent(html: String): String {
        val doc = Jsoup.parse(html, baseUrl)
        val content =
            doc.selectFirst("article.message--post .message-body .bbWrapper, .message-userContent .bbWrapper")
                ?: throw NetworkParseException("SpaceBattles chapter post was not found")
        return sanitizeSpaceBattlesPost(content)
    }

    override suspend fun fetchChapterContent(
        storyUrl: String,
        chapter: Chapter,
        chapterIndex: Int,
        network: NetworkClient,
        requestGate: NetworkRequestGate?,
    ): String {
        val postId = rawPostId(chapter.url) ?: throw NetworkParseException("SpaceBattles post ID was not found")
        val root = storyRoot(null, storyUrl)
        val expectedPage = chapterIndex / READER_POSTS_PER_PAGE + 1
        readerPageCandidates(expectedPage).forEach { page ->
            val readerUrl = "${root}reader/page-$page?threadmark_category=$MAIN_CATEGORY"
            val html =
                try {
                    network.fetchReusablePage(
                        url = readerUrl,
                        cacheKey = readerUrl,
                        maximumAttemptsOverride = 1,
                        requestGate = requestGate,
                        cacheValidator = ::isReaderPage,
                    )
                } catch (error: HttpNetworkException) {
                    if (error.statusCode == 404) return@forEach
                    throw error
                }
            parseSpaceBattlesPost(html, postId, readerUrl)?.let { return it }
        }

        val fallbackHtml =
            network.fetch(
                url = chapter.url,
                maximumAttemptsOverride = 1,
                requestGate = requestGate,
            )
        return parseSpaceBattlesPost(fallbackHtml, postId, chapter.url)
            ?: throw NetworkParseException("SpaceBattles chapter post $postId was not found")
    }

    internal fun parseThreadmarks(
        html: String,
        storyRoot: String,
    ): List<ChapterInfo> {
        val doc = Jsoup.parse(html, storyRoot)
        val scopedRows = doc.select(".block-body--threadmarkBody.category-1 .structItem--threadmark")
        val rows = scopedRows.ifEmpty { doc.select(".structItemContainer .structItem--threadmark") }
        val origin = ORIGIN.find(storyRoot)?.value ?: baseUrl
        return rows.mapNotNull { row ->
            val link =
                row.selectFirst(".structItem-title a[data-tp-primary=on]")
                    ?: row.selectFirst(".structItem-title a[href*=post-]")
                    ?: return@mapNotNull null
            val postId =
                rawPostId(row.attr("data-preview-url"))
                    ?: rawPostId(link.attr("href"))
                    ?: rawPostId(row.html())
                    ?: return@mapNotNull null
            ChapterInfo(
                id = "sb_$postId",
                title = sanitizeTitle(link.text()).ifBlank { "Untitled Chapter" },
                url = "$origin/posts/$postId/",
                publishedAt = row.chapterPublishedAt(),
            )
        }
    }

    private fun chapterCountLabel(count: Int): String = "$count ${if (count == 1) "chapter" else "chapters"}"

    internal fun parseThreadmarkRss(
        xml: String,
        storyRoot: String,
    ): List<ChapterInfo> {
        val doc = Jsoup.parse(xml, storyRoot, Parser.xmlParser())
        val origin = ORIGIN.find(storyRoot)?.value ?: baseUrl
        return doc
            .select("item")
            .mapNotNull { item ->
                val guid = item.childText("guid")
                val link = item.childText("link")
                val postId = rawPostId(guid) ?: rawPostId(link) ?: return@mapNotNull null
                ChapterInfo(
                    id = "sb_$postId",
                    title = sanitizeTitle(item.childText("title")).ifBlank { "Untitled Chapter" },
                    url = "$origin/posts/$postId/",
                    publishedAt = item.rssPublishedAt(),
                )
            }.asReversed()
    }

    /** Returns the newest main-threadmark publication timestamp, ignoring feed generation time. */
    internal fun parseThreadmarkRssUpdatedAt(xml: String): Long? {
        val doc = Jsoup.parse(xml, baseUrl, Parser.xmlParser())
        return doc.select("item").mapNotNull { it.rssPublishedAt() }.maxOrNull()
    }

    private fun storyRoot(
        doc: Document?,
        fallbackUrl: String,
    ): String {
        val candidate =
            doc
                ?.selectFirst("link[rel=canonical]")
                ?.let { safeAbsoluteUrl(it, "href") }
                ?: fallbackUrl
        val match = THREAD_ROOT.find(candidate) ?: THREAD_ROOT.find(fallbackUrl)
        return match?.value?.trimEnd('/')?.plus("/")
            ?: throw NetworkParseException("SpaceBattles thread URL was not recognized")
    }

    private fun threadmarkListUrl(
        root: String,
        page: Int,
    ): String =
        buildString {
            append(root)
            append("threadmarks?threadmark_category=$MAIN_CATEGORY&per_page=$THREADMARKS_PER_PAGE")
            if (page > 1) append("&page=$page")
        }

    private fun threadmarkPageCount(html: String): Int =
        Jsoup
            .parse(html)
            .let { doc ->
                doc
                    .select(".threadmarks-pagenav--wrapper.category-1 a[href*=page=]")
                    .ifEmpty { doc.select(".pageNav-main a[href*=page=]") }
            }.mapNotNull {
                PAGE_QUERY
                    .find(it.attr("href"))
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
            }.maxOrNull()
            ?: 1

    private fun readerPageCandidates(expectedPage: Int): List<Int> =
        listOf(0, 1, -1, 2, -2)
            .map { expectedPage + it }
            .filter { it > 0 }
            .distinct()

    private fun Element.childText(tagName: String): String =
        children()
            .firstOrNull { it.tagName().equals(tagName, ignoreCase = true) }
            ?.text()
            .orEmpty()
            .trim()

    private fun Element.rssPublishedAt(): Long? =
        selectFirst("pubDate")
            ?.text()
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let(::parseSourceDateMillis)

    private const val MAIN_CATEGORY = 1
    private const val THREADMARKS_PER_PAGE = 200
    private const val READER_POSTS_PER_PAGE = 10
    private const val MAX_THREADMARK_PAGES = 500
    private val SPACEBATTLES_HOST =
        Regex("""https?://(?:(?:forum|forums)\.)?spacebattles\.com/""", RegexOption.IGNORE_CASE)
    private val THREAD_ID = Regex("""/threads/(?:[^/?#]*\.)?(\d+)(?:[/?#]|$)""", RegexOption.IGNORE_CASE)
    private val THREAD_ROOT =
        Regex("""^https?://[^/]+/threads/(?:[^/?#]*\.)?\d+""", RegexOption.IGNORE_CASE)
    private val ORIGIN = Regex("""^https?://[^/]+""", RegexOption.IGNORE_CASE)
    private val PAGE_QUERY = Regex("""[?&]page=(\d+)""", RegexOption.IGNORE_CASE)
}
