package com.vinicius741.webnovelarchiver.source

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.vinicius741.webnovelarchiver.domain.model.ChapterInfo
import com.vinicius741.webnovelarchiver.domain.model.NovelMetadata
import com.vinicius741.webnovelarchiver.domain.model.PublicationStatus
import com.vinicius741.webnovelarchiver.domain.model.SourceMetadata
import com.vinicius741.webnovelarchiver.domain.model.SourceMetric
import com.vinicius741.webnovelarchiver.domain.model.SourceMetricKind
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import com.vinicius741.webnovelarchiver.source.network.NetworkParseException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Locale
import kotlin.math.max

@Suppress("TooManyFunctions")
object RoyalRoadProvider : SourceProvider {
    override val descriptor =
        SourceDescriptor(
            id = "royal_road",
            displayName = "RoyalRoad",
            browseUrl = "https://www.royalroad.com",
            hosts = setOf("royalroad.com"),
            featuredMetrics =
                listOf(
                    SourceMetricKind.FOLLOWERS,
                    SourceMetricKind.TOTAL_VIEWS,
                    SourceMetricKind.PAGES,
                ),
            renderedPageRules =
                listOf(
                    SourceRenderedPageRule(pathContains = "/chapter/", requiredSelector = ".chapter-inner"),
                ),
        )

    override fun classifyUrl(url: String): SourceUrlKind? {
        val path = sourcePath(url)?.lowercase() ?: return null
        return when {
            Regex("""^/fiction/\d+(?:/[^/]+)?/?$""").matches(path) -> SourceUrlKind.STORY
            Regex("""^/fiction/\d+(?:/[^/]+)?/chapter/\d+(?:/[^/]+)?/?$""").matches(path) -> SourceUrlKind.CHAPTER
            else -> null
        }
    }

    override fun getStoryId(url: String) =
        Regex("fiction/(\\d+)")
            .find(url)
            ?.groupValues
            ?.get(1)
            ?.let { "rr_$it" }
            ?: error("Royal Road story URL was not recognized")

    override fun getChapterId(url: String) = Regex("/chapter/(\\d+)").find(url)?.groupValues?.get(1)

    override fun parseMetadata(html: String): NovelMetadata {
        val doc = Jsoup.parse(html)
        val title =
            doc
                .selectFirst("h1")
                ?.text()
                ?.trim()
                .orEmpty()
                .ifBlank { "Unknown Title" }
        val author =
            doc.selectFirst("h4 a")?.text()?.trim()
                ?: doc
                    .selectFirst("h4")
                    ?.text()
                    ?.replace("Author:", "")
                    ?.trim()
                ?: doc.selectFirst("meta[name=author]")?.attr("content")
                ?: doc.selectFirst("meta[property=article:author]")?.attr("content")
                ?: doc.selectFirst("meta[name=twitter:creator]")?.attr("content")
                ?: "Unknown Author"
        val cover =
            (doc.selectFirst(".page-content-inner .col-md-3 img")?.absUrl("src") ?: "")
                .ifBlank { doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty() }
                .ifBlank { null }
        val description =
            doc.selectFirst(".description")?.blockText()
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
                ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
        val genreTags =
            doc
                .select(".tags .label, .tags a, .tag")
                .map {
                    it.text().trim()
                }.filter { it.isNotBlank() && !it.equals("tags", true) }
        // Royal Road surfaces sensitive-content flags in a red "Warning: This fiction contains:" block
        // (div.font-red-sunglo with <strong>Warning</strong> + <ul class="list-inline">). Capture them as
        // tags with a ⚠ prefix so they stay distinct from genre tags while flowing through the tag system.
        val contentWarnings = royalRoadContentWarnings(doc)
        val tags = (genreTags + contentWarnings.map { "\u26A0 $it" }).distinct().toMutableList()
        val score =
            doc
                .selectFirst(
                    ".list-unstyled li.list-item:contains(Overall Score)",
                )?.nextElementSibling()
                ?.selectFirst("span.star")
                ?.attr("data-content")
        val canonical = doc.selectFirst("link[rel=canonical]")?.absUrl("href") ?: doc.selectFirst("meta[property=og:url]")?.attr("content")
        val patreonUrl = findPatreonUrl(doc)
        val fictionLabels = royalRoadFictionLabels(doc)
        val publicationStatus = royalRoadPublicationStatus(fictionLabels)
        val jsonLdDates = royalRoadJsonLdDates(doc)
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
                    metrics = royalRoadMetrics(doc),
                    createdAt = jsonLdDates.createdAt,
                    publishedAt = jsonLdDates.publishedAt,
                    updatedAt = jsonLdDates.updatedAt,
                    contentWarnings = contentWarnings.toMutableList(),
                    sourceType = royalRoadSourceType(fictionLabels),
                    sourceListingState = royalRoadListingState(fictionLabels),
                ),
        )
    }

    override suspend fun getChapterList(
        html: String,
        url: String,
        network: NetworkClient,
        progress: (String) -> Unit,
    ): List<ChapterInfo> {
        progress("Parsing chapter list...")
        val doc = Jsoup.parse(html, url)
        val chapterNumbers = royalRoadChapterNumbers(doc)
        return doc.select(".chapter-row").mapNotNull { row ->
            val link = row.selectFirst("a[href*=/fiction/]") ?: return@mapNotNull null
            val href = link.absUrl("href").ifBlank { link.attr("href") }
            ChapterInfo(
                id = getChapterId(href),
                title = sanitizeTitle(link.text()),
                url = href,
                chapterNumber =
                    royalRoadChapterNumber(row, link)
                        ?: chapterNumbers[getChapterId(href)]
                        ?: chapterNumberFromTitle(link.text()),
                publishedAt = row.chapterPublishedAt(),
            )
        }
    }

    override fun parseChapterContent(html: String): String {
        val doc = Jsoup.parse(html)
        // Throw rather than returning a placeholder string: a returned string would be saved as the
        // chapter body and baked into the EPUB. Throwing routes the failure through DownloadEngine →
        // DownloadErrorClassifier, where it shows up on the download job (retryable parse error) and
        // never pollutes the generated book.
        val content = doc.selectFirst(".chapter-inner") ?: throw NetworkParseException("Chapter content not found on page")
        content.select("div.portlet, script, .bold.uppercase.text-center").remove()
        return content.html()
    }

    private fun royalRoadPublicationStatus(labels: List<String>): PublicationStatus =
        labels.firstNotNullOfOrNull(::publicationStatusFromSourceText)
            ?: PublicationStatus.unknown

    private fun royalRoadFictionLabels(doc: Document): List<String> {
        val elements = doc.select(".fiction-info .margin-bottom-10 > span.label, .fiction-info .label")
        val labels = elements.map { normalizedSourceText(it.text()) }
        return labels.filter { it.isNotBlank() }.distinct()
    }

    private fun royalRoadContentWarnings(doc: Document): List<String> {
        val containers =
            doc.select("div.font-red-sunglo").filter { container ->
                val strong = container.selectFirst("strong")
                val text = strong?.text()
                val normalized = text?.trim()
                normalized?.equals("Warning", ignoreCase = true) == true
            }
        val items = containers.flatMap { container -> container.select("ul.list-inline li") }
        val warnings = items.map { it.text().trim() }
        return warnings.filter { it.isNotBlank() }.distinct()
    }

    private fun royalRoadSourceType(labels: List<String>): String? {
        val type =
            labels.firstOrNull {
                it.equals("Original", ignoreCase = true) || it.equals("Fan Fiction", ignoreCase = true)
            }
        return type?.let { if (it.equals("Original", ignoreCase = true)) "Original" else "Fan Fiction" }
    }

    private fun royalRoadListingState(labels: List<String>): String? {
        val states = labels.filter { it.equals("INACTIVE", ignoreCase = true) || it.equals("STUB", ignoreCase = true) }
        val normalizedStates = states.map { it.uppercase(Locale.US) }.distinct()
        return normalizedStates.joinToString(", ").ifBlank { null }
    }

    private data class RoyalRoadStat(
        val label: String,
        val kind: SourceMetricKind,
    )

    private val royalRoadStats =
        listOf(
            RoyalRoadStat("Total Views", SourceMetricKind.TOTAL_VIEWS),
            RoyalRoadStat("Average Views", SourceMetricKind.AVERAGE_VIEWS),
            RoyalRoadStat("Followers", SourceMetricKind.FOLLOWERS),
            RoyalRoadStat("Favorites", SourceMetricKind.FAVORITES),
            RoyalRoadStat("Ratings", SourceMetricKind.RATINGS),
            RoyalRoadStat("Pages", SourceMetricKind.PAGES),
        )

    private fun royalRoadMetrics(doc: Document): MutableList<SourceMetric> {
        val metrics =
            royalRoadStats.mapNotNull { stat ->
                val value = findRoyalRoadStatisticValue(doc, stat.label)?.let(::parseSourceMetricValue)
                value?.let { SourceMetric(kind = stat.kind, value = it) }
            }
        return metrics.toMutableList()
    }

    /**
     * Royal Road has shipped both one-label/one-value rows and adjacent label/value list items.
     * Search by the visible label and then inspect the value following that label; never use the
     * statistics block's current positional order.
     */
    private fun findRoyalRoadStatisticValue(
        doc: Document,
        label: String,
    ): String? {
        val items = doc.select(".list-unstyled li.list-item, .list-unstyled li, .statistics li")
        for (item in items) {
            val itemText = normalizedSourceText(item.text())
            if (!itemText.regionMatches(0, label, 0, label.length, ignoreCase = true)) continue
            val inlineValue = itemText.substring(label.length).trim().trimStart(':', '-', '–')
            metricValueCandidate(inlineValue)?.let { return it }

            val labelElement =
                item
                    .select("strong, b, span, dt, div, p, a")
                    .firstOrNull { normalizedSourceText(it.text()).equals(label, ignoreCase = true) }
            labelElement?.nextElementSibling()?.let { sibling ->
                metricValueCandidate(sibling.text())?.let { return it }
            }

            if (itemText.equals(label, ignoreCase = true)) {
                metricValueCandidate(item.nextElementSibling()?.text())?.let { return it }
            }
        }
        return null
    }

    private fun metricValueCandidate(value: String?): String? {
        val normalized = normalizedSourceText(value).trimStart(':', '-', '–')
        return normalized.takeIf {
            Regex("(?i)^[-+]?\\d+(?:\\.\\d+)?\\s*[KMB]?\\b").containsMatchIn(it)
        }
    }

    private data class RoyalRoadJsonLdDates(
        val createdAt: Long? = null,
        val publishedAt: Long? = null,
        val updatedAt: Long? = null,
    )

    private fun royalRoadJsonLdDates(doc: Document): RoyalRoadJsonLdDates {
        var createdAt: Long? = null
        var publishedAt: Long? = null
        var updatedAt: Long? = null
        doc.select("script[type=application/ld+json]").forEach { script ->
            val json = runCatching { JsonParser.parseString(script.data().ifBlank { script.html() }) }.getOrNull() ?: return@forEach
            visitJsonObjects(json) { objectValue ->
                if (createdAt == null) createdAt = objectValue.sourceDate("dateCreated")
                if (publishedAt == null) publishedAt = objectValue.sourceDate("datePublished")
                if (updatedAt == null) updatedAt = objectValue.sourceDate("dateModified")
            }
        }
        return RoyalRoadJsonLdDates(createdAt, publishedAt, updatedAt)
    }

    private fun visitJsonObjects(
        element: JsonElement,
        visitor: (JsonObject) -> Unit,
    ) {
        when {
            element.isJsonObject -> {
                val objectValue = element.asJsonObject
                visitor(objectValue)
                objectValue.entrySet().forEach { (_, child) -> visitJsonObjects(child, visitor) }
            }
            element.isJsonArray -> element.asJsonArray.forEach { child -> visitJsonObjects(child, visitor) }
        }
    }

    private fun JsonObject.sourceDate(key: String): Long? =
        get(key)
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
            ?.let(::parseSourceDateMillis)

    private fun royalRoadChapterNumbers(doc: Document): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        val objectPattern = Regex("(?s)\\{[^{}]*\\}")
        val idPattern = Regex("(?i)[\"']?(?:id|chapterId|chapter_id)[\"']?\\s*[:=]\\s*[\"']?(\\d+)")
        val numberPattern = Regex("(?i)[\"']?(?:order|chapterNumber|chapter_number|number)[\"']?\\s*[:=]\\s*[\"']?(\\d+)")
        doc.select("script").forEach { script ->
            val source = script.data().ifBlank { script.html() }
            if (!source.contains("chapters", ignoreCase = true)) return@forEach
            objectPattern.findAll(source).forEach { match ->
                val objectText = match.value
                val id = idPattern.find(objectText)?.groupValues?.get(1) ?: return@forEach
                val numberMatch = numberPattern.find(objectText) ?: return@forEach
                val number = numberMatch.groupValues.getOrNull(1)?.toIntOrNull() ?: return@forEach
                result[id] = max(0, number)
            }
        }
        return result
    }

    private fun royalRoadChapterNumber(
        row: Element,
        link: Element,
    ): Int? {
        val attrs = listOf("data-chapter-number", "data-chapter", "data-order", "data-number")
        val rowValues = attrs.mapNotNull { attr -> row.attr(attr).takeIf { it.isNotBlank() } }
        val linkValues = attrs.mapNotNull { attr -> link.attr(attr).takeIf { it.isNotBlank() } }
        val attributeValue = (rowValues + linkValues).firstNotNullOfOrNull { it.toIntOrNull() }
        if (attributeValue != null) return max(0, attributeValue)
        val chapterNumberElement =
            row.selectFirst(".chapter-number, [data-chapter-number], [data-chapter], [data-order], [data-number]")
        val text = chapterNumberElement?.text()
        val normalized = text?.trim()
        val number = normalized?.toIntOrNull()
        return number?.let { max(0, it) }
    }

    private fun chapterNumberFromTitle(title: String): Int? =
        Regex("(?i)^\\s*chapter\\s*#?\\s*(\\d+)\\b")
            .find(title)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
}
