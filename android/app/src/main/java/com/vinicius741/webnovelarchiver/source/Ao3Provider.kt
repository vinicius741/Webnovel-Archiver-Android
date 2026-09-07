package com.vinicius741.webnovelarchiver.source

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.ChapterInfo
import com.vinicius741.webnovelarchiver.domain.model.NovelMetadata
import com.vinicius741.webnovelarchiver.domain.model.SourceMetricKind
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import com.vinicius741.webnovelarchiver.source.network.NetworkParseException
import com.vinicius741.webnovelarchiver.source.network.NetworkRequestGate
import com.vinicius741.webnovelarchiver.source.network.SourceNetworkPolicy
import org.jsoup.Jsoup

/** AO3's navigation index exposes stable chapter IDs, including for single-chapter works. */
object Ao3Provider : SourceProvider {
    override val descriptor =
        SourceDescriptor(
            id = "ao3",
            displayName = "Archive of Our Own (AO3)",
            browseUrl = "https://archiveofourown.org",
            hosts = setOf("archiveofourown.org"),
            capabilities = SourceCapabilities(maximumDownloadConcurrency = 1, bulkDownloadPreflight = false),
            networkPolicy = SourceNetworkPolicy(minimumRequestGapMillis = 3_000L, maximumRequestsPerWindow = 20),
            featuredMetrics =
                listOf(
                    SourceMetricKind.KUDOS,
                    SourceMetricKind.BOOKMARKS,
                    SourceMetricKind.TOTAL_VIEWS,
                    SourceMetricKind.WORDS,
                ),
        )

    override fun classifyUrl(url: String): SourceUrlKind? = if (workMatch(url) != null) SourceUrlKind.STORY else null

    override fun normalizeStoryUrl(url: String): String = "$baseUrl/works/${requireNotNull(workMatch(url)).groupValues[1]}"

    override fun getStoryId(url: String): String = "ao3_${requireNotNull(workMatch(url)).groupValues[1]}"

    override fun getChapterId(url: String): String? =
        workMatch(url)
            ?.groupValues
            ?.get(2)
            ?.takeIf(String::isNotEmpty)
            ?.let { "ao3_chapter_$it" }

    override fun parseMetadata(html: String): NovelMetadata = parseAo3Metadata(html)

    override suspend fun loadStory(
        url: String,
        preferLatestChapters: Boolean,
        network: NetworkClient,
        progress: (String) -> Unit,
    ): LoadedSourceStory {
        val canonical = normalizeStoryUrl(url)
        progress("Fetching AO3 chapter index...")
        val index = network.fetch("$canonical/navigate?view_adult=true")
        val indexedChapters = parseAo3ChapterIndex(index, canonical)
        val html = network.fetch("${indexedChapters.first().url}?view_adult=true")
        val metadata = parseMetadata(html).copy(canonicalUrl = canonical)
        val chapters = parseAo3ChapterIndex(index, canonical, ao3ChapterCounts(Jsoup.parse(html)).first)
        return LoadedSourceStory(metadata, chapters, false) { chapters }
    }

    override suspend fun getChapterList(
        html: String,
        url: String,
        network: NetworkClient,
        progress: (String) -> Unit,
    ): List<ChapterInfo> {
        val canonical = normalizeStoryUrl(url)
        val counts = ao3ChapterCounts(Jsoup.parse(html))
        progress("Fetching AO3 chapter index...")
        val index = network.fetch("$canonical/navigate?view_adult=true")
        return parseAo3ChapterIndex(index, canonical, counts.first)
    }

    override suspend fun fetchChapterContent(
        storyUrl: String,
        chapter: Chapter,
        chapterIndex: Int,
        network: NetworkClient,
        requestGate: NetworkRequestGate?,
    ): String {
        val match = workMatch(chapter.url)
        if (match == null || getStoryId(chapter.url) != getStoryId(storyUrl) || getChapterId(chapter.url) == null) {
            throw NetworkParseException("AO3 chapter URL does not belong to this work")
        }
        val url = "$baseUrl/works/${match.groupValues[1]}/chapters/${match.groupValues[2]}?view_adult=true"
        return parseChapterContent(network.fetch(url, maximumAttemptsOverride = 1, requestGate = requestGate))
    }

    override fun parseChapterContent(html: String): String {
        val doc = Jsoup.parse(html, baseUrl)
        val content = doc.select("#chapters > .userstuff, #chapters .userstuff[role=article]")
        if (content.size !=
            1
        ) {
            throw NetworkParseException(
                "AO3 chapter text was not found or the page contains multiple chapters. The work may require login or be unavailable.",
            )
        }
        val body = content.single().clone()
        body.select("script, style, noscript, .landmark").remove()
        if (body.text().isBlank() && body.select("img[src], audio, video").isEmpty()) {
            throw NetworkParseException("AO3 returned an empty chapter")
        }
        body.select("[href], [src]").forEach { element ->
            listOf("href", "src").filter(element::hasAttr).forEach { attr ->
                element.absUrl(attr).takeIf(String::isNotEmpty)?.let { element.attr(attr, it) }
            }
        }
        return body.html()
    }

    private fun workMatch(url: String) = WORK_URL.matchEntire(url.trim())

    private val WORK_URL =
        Regex(
            """^https?://(?:www\.)?archiveofourown\.org/works/([1-9]\d*)(?:/chapters/([1-9]\d*))?/?(?:[?#].*)?$""",
            RegexOption.IGNORE_CASE,
        )
}
