package com.vinicius741.webnovelarchiver.source

import com.vinicius741.webnovelarchiver.domain.archive.PercentEncoding
import com.vinicius741.webnovelarchiver.domain.model.ChapterInfo
import com.vinicius741.webnovelarchiver.domain.model.NovelMetadata
import com.vinicius741.webnovelarchiver.domain.model.PublicationStatus
import com.vinicius741.webnovelarchiver.domain.model.SourceMetadata
import com.vinicius741.webnovelarchiver.domain.model.SourceMetric
import com.vinicius741.webnovelarchiver.domain.model.SourceMetricKind
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import com.vinicius741.webnovelarchiver.source.network.NetworkParseException
import com.vinicius741.webnovelarchiver.source.network.SourceAccessBlockedException
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import timber.log.Timber

@Suppress("TooManyFunctions")
object ScribbleHubProvider : SourceProvider {
    override val name = "Scribble Hub"
    override val baseUrl = "https://www.scribblehub.com"
    override val supportsLatestChapterSync = true
    private const val AJAX_URL = "https://www.scribblehub.com/wp-admin/admin-ajax.php"
    private const val MAX_TOC_PAGE_SIZE = 50

    override fun isSource(url: String) =
        Regex("https?://(?:www\\.)?scribblehub\\.com/(series|read)/", RegexOption.IGNORE_CASE).containsMatchIn(url)

    override fun classifyUrl(url: String): SourceUrlKind? =
        when {
            Regex("""https?://(?:www\.)?scribblehub\.com/series/\d+""", RegexOption.IGNORE_CASE)
                .containsMatchIn(url) -> SourceUrlKind.STORY
            Regex("""https?://(?:www\.)?scribblehub\.com/read/[^/]+/chapter/\d+""", RegexOption.IGNORE_CASE)
                .containsMatchIn(url) -> SourceUrlKind.CHAPTER
            else -> null
        }

    override fun getStoryId(url: String) =
        Regex("/series/(\\d+)", RegexOption.IGNORE_CASE)
            .find(url)
            ?.groupValues
            ?.get(1)
            ?.let { "sh_$it" }
            ?: "sh_url_${PercentEncoding.encodeURIComponent(url.lowercase())}"

    override fun getChapterId(url: String) =
        Regex("/chapter/(\\d+)", RegexOption.IGNORE_CASE)
            .find(url)
            ?.groupValues
            ?.get(1)
            ?.let { "sh_$it" }

    override fun parseMetadata(html: String): NovelMetadata {
        val doc = Jsoup.parse(html, baseUrl)
        val title =
            firstText(doc, ".fic_title", ".wi_fic_title", "h1").ifBlank {
                doc.selectFirst("meta[property=og:title]")?.attr("content")
                    ?: "Unknown Title"
            }
        val author =
            firstText(doc, ".auth_name_fic a", ".auth_name_fic", "a[href*=/profile/]").ifBlank {
                doc.selectFirst("meta[name=author]")?.attr("content")
                    ?: "Unknown Author"
            }
        val cover =
            (doc.selectFirst(".fic_image img")?.absUrl("src") ?: "")
                .ifBlank { doc.selectFirst(".fic_image img")?.absUrl("data-src").orEmpty() }
                .ifBlank { doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty() }
                .ifBlank { null }
        val description =
            (
                firstStructuredText(doc, ".wi_fic_desc", ".fic_synopsis", "#synopsis")
                    .ifBlank {
                        doc.selectFirst("meta[property=og:description]")?.attr("content")
                            ?: doc.selectFirst("meta[name=description]")?.attr("content").orEmpty()
                    }.stripScribbleHubSynopsisToggle()
            ).ifBlank { null }
        val canonical = doc.selectFirst("link[rel=canonical]")?.absUrl("href") ?: doc.selectFirst("meta[property=og:url]")?.attr("content")
        val tags =
            doc
                .select(".wi_fic_genre a, .wi_fic_tags a, .fic_genre a, .fic_tags a, a[href*=/genre/], a[href*=/tag/]")
                .map {
                    it.text().trim()
                }.filter { it.isNotBlank() }
                .distinct()
                .toMutableList()
        val score = scribbleHubScore(doc)
        val patreonUrl = findPatreonUrl(doc)
        val publicationStatus = scribbleHubPublicationStatus(doc)
        val contentWarnings =
            doc
                .select("li.mature_contains > a[href*=/content/]")
                .map { normalizedSourceText(it.text()) }
                .filter { it.isNotBlank() }
                .distinct()
                .toMutableList()
        return NovelMetadata(
            title = title,
            author = author,
            coverUrl = cover,
            description = description,
            tags = tags.ifEmpty { null },
            score = score,
            canonicalUrl = canonical,
            patreonUrl = patreonUrl,
            publicationStatus = publicationStatus,
            sourceMetadata =
                SourceMetadata(
                    publishedAt = scribbleHubParsePublicationDate(doc),
                    contentWarnings = contentWarnings,
                ),
        )
    }

    override suspend fun enrichMetadata(
        metadata: NovelMetadata,
        html: String,
        url: String,
        network: NetworkClient,
        progress: (String) -> Unit,
    ): NovelMetadata {
        val statsUrl = scribbleHubCanonicalSeriesUrl(metadata.canonicalUrl ?: url)?.let { "$it/stats/" } ?: return metadata
        return try {
            progress("Fetching Scribble Hub statistics...")
            val stats = parseStatsMetadata(network.fetch(statsUrl))
            if (stats.metrics.isEmpty()) {
                metadata
            } else {
                metadata.copy(sourceMetadata = mergeScribbleHubSourceMetadata(metadata.sourceMetadata, stats))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Statistics are supplemental. A blocked, missing, or changed stats page must not make
            // the base series import fail, but log so regressions in stats parsing don't vanish.
            Timber.w(error, "Scribble Hub stats enrichment failed for %s; continuing without metrics.", statsUrl)
            metadata
        }
    }

    /** Parses the public `/stats/` page without relying on its current positional layout. */
    internal fun parseStatsMetadata(html: String): SourceMetadata {
        val doc = Jsoup.parse(html, baseUrl)
        val labeledValues = scribbleHubExtractLabeledValues(doc)
        val metrics =
            scribbleHubStatLabels
                .values
                .distinct()
                .mapNotNull { kind ->
                    labeledValues[kind]?.let { value ->
                        parseSourceMetricValue(value)?.let { SourceMetric(kind = kind, value = it) }
                    }
                }.toMutableList()
        val ratings = scribbleHubParseRatingsCount(doc)
        if (ratings != null && metrics.none { it.kind == SourceMetricKind.RATINGS }) {
            metrics += SourceMetric(kind = SourceMetricKind.RATINGS, value = ratings)
        }
        return SourceMetadata(metrics = metrics)
    }

    override suspend fun getChapterList(
        html: String,
        url: String,
        network: NetworkClient,
        progress: (String) -> Unit,
    ): List<ChapterInfo> {
        progress("Parsing chapter list...")
        val doc = Jsoup.parse(html, url)
        val postId = doc.selectFirst("#mypostid")?.attr("value")
        val chapters = parseToc(doc).toMutableList()
        val seen = chapters.map { it.url }.toMutableSet()
        if (!postId.isNullOrBlank() && chapters.size >= 15) {
            progress("Fetching chapter page 1 · ${chapterCountLabel(chapters.size)} found...")
            val firstPage =
                try {
                    fetchTocPage(network, url, postId, 1)
                } catch (_: SourceAccessBlockedException) {
                    emptyList()
                }
            if (firstPage.size >= chapters.size) {
                chapters.clear()
                chapters.addAll(firstPage)
                seen.clear()
                seen.addAll(chapters.map { it.url })
            }
            for (page in 2..500) {
                progress("Fetching chapter page $page · ${chapterCountLabel(chapters.size)} found...")
                val pageChapters =
                    try {
                        fetchTocPage(network, url, postId, page)
                    } catch (_: SourceAccessBlockedException) {
                        break
                    }
                if (pageChapters.isEmpty()) break
                val newOnes = pageChapters.filter { seen.add(it.url) }
                if (newOnes.isEmpty()) break
                chapters.addAll(newOnes)
                if (pageChapters.size < MAX_TOC_PAGE_SIZE) break
            }
        }
        progress("${chapterCountLabel(chapters.size)} found")
        return chapters.asReversed()
    }

    override suspend fun getLatestChapterList(
        html: String,
        url: String,
        network: NetworkClient,
        progress: (String) -> Unit,
    ): List<ChapterInfo>? {
        val doc = Jsoup.parse(html, url)
        val postId = doc.selectFirst("#mypostid")?.attr("value")
        val chapters = parseToc(doc)
        val latest =
            if (!postId.isNullOrBlank() && chapters.size >= 15) {
                progress("Fetching latest chapter page...")
                try {
                    fetchTocPage(network, url, postId, 1).ifEmpty { chapters }
                } catch (_: SourceAccessBlockedException) {
                    chapters
                }
            } else {
                chapters
            }
        return latest.asReversed()
    }

    override fun parseChapterContent(html: String): String {
        val doc = Jsoup.parse(html)
        // See RoyalRoadProvider.parseChapterContent: throw instead of returning a placeholder so the
        // failure surfaces as a download-job error rather than getting embedded in the EPUB.
        val content = doc.selectFirst("#chp_raw") ?: throw NetworkParseException("Chapter content not found on page")
        content.select("script, style, .wi_authornotes, .sharedaddy, .code-block").remove()
        return content.html().trim()
    }

    private fun parseToc(doc: Document): List<ChapterInfo> =
        doc.select(".toc_ol li").mapNotNull { li ->
            val link = li.selectFirst("a[href*=/read/][href*=/chapter/]") ?: return@mapNotNull null
            val href = link.absUrl("href").ifBlank { link.attr("href") }
            ChapterInfo(
                getChapterId(href),
                sanitizeTitle(link.text()).ifBlank {
                    "Untitled Chapter"
                },
                href,
                publishedAt = li.chapterPublishedAt(),
            )
        }

    private fun chapterCountLabel(count: Int): String = "$count ${if (count == 1) "chapter" else "chapters"}"

    private suspend fun fetchTocPage(
        network: NetworkClient,
        url: String,
        postId: String,
        page: Int,
    ): List<ChapterInfo> {
        // toc_show=50 is seeded into CookieManager at app startup and carried by the shared
        // AndroidCookieJar; no manual Cookie header here (it would be overwritten by OkHttp's
        // BridgeInterceptor once a cf_clearance cookie is also in the jar).
        val pageHtml =
            network
                .postForm(
                    AJAX_URL,
                    mapOf(
                        "action" to "wi_getreleases_pagination",
                        "pagenum" to page,
                        "mypostid" to postId,
                    ),
                ).replace(Regex("0\\s*$"), "")
                .trim()
        return parseToc(Jsoup.parse(pageHtml, url))
    }

    private fun firstText(
        doc: Document,
        vararg selectors: String,
    ): String =
        selectors
            .firstNotNullOfOrNull { selector ->
                doc.selectFirst(selector)?.text()?.trim()?.takeIf {
                    it.isNotBlank()
                }
            }.orEmpty()

    // Like [firstText], but preserves the original paragraph/line-break layout of the matched
    // element via [blockText]. Used for long free form fields (e.g. synopsis) where collapsing
    // `<p>`/`<br>` into a single space-joined line would discard the author's structure.
    private fun firstStructuredText(
        doc: Document,
        vararg selectors: String,
    ): String =
        selectors
            .firstNotNullOfOrNull { selector ->
                doc.selectFirst(selector)?.blockText()?.takeIf { it.isNotBlank() }
            }.orEmpty()

    private fun scribbleHubScore(doc: Document): String? =
        doc
            .select("script[type=application/ld+json]")
            .asSequence()
            .map { it.data().ifBlank { it.html() } }
            .filter { it.contains("AggregateRating") }
            .mapNotNull {
                Regex(""""ratingValue"\s*:\s*"?([^",}]+)"?""")
                    .find(it)
                    ?.groupValues
                    ?.get(1)
                    ?.trim()
            }.firstOrNull { it.isNotBlank() }
            ?: firstText(doc, ".numscore", "[itemprop=ratingValue]", ".rate_fic_user").ifBlank { null }
            ?: doc
                .selectFirst("#ratefic_user")
                ?.text()
                ?.let { Regex("""\d+(?:\.\d+)?(?=\s*\()""").find(it)?.value }

    private fun scribbleHubPublicationStatus(doc: Document): PublicationStatus {
        val explicit =
            doc
                .select(
                    ".wi_fic_status, .fic_status, .fic_status_info, .fic_status_label, " +
                        ".series-status, .story-status, .post-status",
                ).map { it.text().trim() }
                .firstNotNullOfOrNull(::publicationStatusFromSourceText)
        if (explicit != null) return explicit

        val bodyText = doc.body()?.text().orEmpty()
        val updatedLineStatus =
            Regex("""(?i)\b(Completed|Complete|Ongoing|Hiatus|Hiatused|Dropped|Cancelled|Canceled)\b\s*[-–—]\s*Updated\b""")
                .find(bodyText)
                ?.groupValues
                ?.getOrNull(1)
        val rightsStatusPattern =
            Regex(
                """(?i)\bAll\s+Rights\s+Reserved\b[\s;·•|,.-]{0,40}""" +
                    """(Completed|Complete|Ongoing|Hiatus|Hiatused|Dropped|Cancelled|Canceled)\b""",
            )
        val rightsSidebarStatus =
            rightsStatusPattern
                .find(bodyText)
                ?.groupValues
                ?.getOrNull(1)
        return publicationStatusFromSourceText(updatedLineStatus)
            ?: publicationStatusFromSourceText(rightsSidebarStatus)
            ?: PublicationStatus.unknown
    }
}
