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
import com.vinicius741.webnovelarchiver.source.network.NetworkParseException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/** The novel page, `/novels/<id>-<slug>.html`. */
internal fun parseRanobesMetadata(html: String): NovelMetadata {
    val doc = Jsoup.parse(html, RanobesProvider.baseUrl)
    val headline =
        doc.selectFirst(".r-fullstory h1.title")
            ?: throw NetworkParseException("Ranobes novel page was not recognized")
    val title =
        headline
            .clone()
            .apply { select(".subtitle").remove() }
            .text()
            .trim()
            .takeIf(String::isNotBlank)
            ?: throw NetworkParseException("Ranobes novel title was not found")
    val author =
        doc
            .select(".r-fullstory-spec .tag_list a")
            .mapNotNull(Element::ranobesText)
            .distinct()
            .joinToString(", ")
            .ifBlank {
                // The headline credits one author as "by <name>".
                headline
                    .selectFirst(".subtitle")
                    ?.text()
                    .orEmpty()
                    .replace(Regex("""(?i)^by\s+"""), "")
                    .trim()
            }.ifBlank { "Unknown Author" }
    val spec = ranobesSpecValues(doc)
    val status = doc.selectFirst(".r-fullstory-spec a[href*=/tags/status-end/]")?.ranobesText()
    val genres = ranobesTags(doc, field = "genre", microdata = "genre")
    val events = ranobesTags(doc, field = "events", microdata = "keywords")
    return NovelMetadata(
        title = title,
        author = author,
        coverUrl =
            doc.selectFirst(".r-fullstory-poster img")?.absUrl("src")?.takeIf(String::isNotBlank)
                ?: doc
                    .selectFirst("meta[property=og:image]")
                    ?.attr("content")
                    ?.trim()
                    ?.takeIf(String::isNotBlank),
        description = doc.selectFirst(".r-desription .cont-text")?.let(Element::ranobesSynopsis),
        tags = (genres + events).distinct().toMutableList().takeIf { it.isNotEmpty() },
        score = doc.selectFirst(".bl_full_rate .rate-stat-num .bold")?.ranobesText(),
        canonicalUrl = doc.selectFirst("link[rel=canonical]")?.absUrl("href")?.takeIf(String::isNotBlank),
        patreonUrl = findPatreonUrl(doc),
        publicationStatus = publicationStatusFromSourceText(status) ?: PublicationStatus.unknown,
        sourceMetadata =
            SourceMetadata(
                metrics = ranobesMetrics(doc, spec),
                publishedAt = doc.selectFirst(".r-fullstory .r-date time.grey")?.text()?.let(::ranobesTimestamp),
                updatedAt = doc.selectFirst(".r-fullstory .r-date time[datetime]")?.attr("datetime")?.let(::ranobesTimestamp),
                sourceStatus = status,
                language = doc.selectFirst(".r-fullstory-spec a[href*=/tags/languages/]")?.ranobesText(),
                genres = genres.toMutableList(),
            ),
    )
}

/** The specification rows by lowercase label, e.g. `views` to `264 706`. */
private fun ranobesSpecValues(doc: Document): Map<String, String> =
    doc
        .select(".r-fullstory-spec li")
        .mapNotNull { row ->
            val text = normalizedSourceText(row.text())
            val value = text.substringAfter(':', "").trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
            text.substringBefore(':').trim().lowercase(Locale.US) to value
        }.toMap()

private fun ranobesMetrics(
    doc: Document,
    spec: Map<String, String>,
): MutableList<SourceMetric> =
    buildList<SourceMetric> {
        fun put(
            kind: SourceMetricKind,
            value: String?,
        ) {
            ranobesCounter(value)?.let { add(SourceMetric(kind, it)) }
        }

        // "Views" counts each visitor once, "Total views" every chapter read, "Total comments" the
        // whole novel against "Comments", which only counts the novel page.
        put(SourceMetricKind.READERS, spec["views"])
        put(SourceMetricKind.TOTAL_VIEWS, spec["total views"])
        put(SourceMetricKind.COMMENTS, spec["total comments"] ?: spec["comments"])
        put(SourceMetricKind.RATINGS, doc.selectFirst(".bl_full_rate .rate-stat-num .small")?.text())
        put(SourceMetricKind.BOOKMARKS, doc.selectFirst(".bl_full_fav .mcollapse-title .green")?.text())
    }.toMutableList()

/**
 * A site counter such as `264 706`: it separates thousands with spaces, which
 * [parseSourceMetricValue] would stop at.
 */
internal fun ranobesCounter(value: String?): Long? {
    // normalizedSourceText has already turned any non-breaking space into an ordinary one.
    val digits = normalizedSourceText(value).replace(Regex("""(?<=\d)\s(?=\d)"""), "")
    return parseSourceMetricValue(digits)
}

/** A tag field's links, or the comma-separated microdata the same block publishes. */
private fun ranobesTags(
    doc: Document,
    field: String,
    microdata: String,
): List<String> =
    doc.select("[data-tv-field=$field] a").mapNotNull(Element::ranobesText).distinct().ifEmpty {
        doc
            .select("meta[itemprop=$microdata]")
            .flatMap { it.attr("content").split(',') }
            .map(String::trim)
            .filter(String::isNotBlank)
    }

private fun Element.ranobesText(): String? = normalizedSourceText(text()).takeIf(String::isNotBlank)

/** The synopsis: without the catalog line the site ends it with, paragraphs kept apart. */
private fun Element.ranobesSynopsis(): String? {
    val synopsis = clone()
    synopsis.select(".grey").remove()
    // The site separates paragraphs with two line breaks, which [blockText] folds into the one
    // it gives a <br>. An empty block between them keeps the paragraphs apart.
    synopsis.select("br + br").forEach { secondBreak ->
        secondBreak.previousElementSibling()?.takeIf { it.tagName() == "br" }?.remove()
        secondBreak.replaceWith(Element("p"))
    }
    return synopsis.blockText().takeIf(String::isNotBlank)
}

/**
 * The novel page's own list of its newest chapters, oldest first. Their dates are relative
 * ("2 days ago"), so they are estimates.
 */
internal fun parseRanobesLatestChapters(
    html: String,
    novelId: String,
): List<ChapterInfo> =
    Jsoup
        .parse(html, RanobesProvider.baseUrl)
        .select("#fs-chapters a.chapter-item[rel=chapter]")
        .mapNotNull { link ->
            val address = RanobesAddress.parse(link.absUrl("href")) ?: return@mapNotNull null
            val url = address.chapterUrl ?: return@mapNotNull null
            if (address.novelId != novelId) return@mapNotNull null
            ChapterInfo(
                id = RanobesProvider.getChapterId(url) ?: return@mapNotNull null,
                title = sanitizeTitle(link.selectFirst(".title")?.text() ?: link.text()),
                url = url,
                publishedAt = link.selectFirst(".grey")?.text()?.let(::parseSourceDateMillis),
            )
        }.asReversed()

/** One page of the table of contents. [chapters] keeps the site's order, newest first. */
internal data class RanobesTocPage(
    val chapters: List<ChapterInfo>,
    val pageNumber: Int,
    val pageCount: Int,
    val totalCount: Int,
)

/**
 * Reads the JSON the site embeds in each `/chapters/<id>/` page. A chapter the list cannot place
 * in [novelId] fails the page: a list that cannot be read chapter by chapter is not one to merge
 * from.
 */
internal fun parseRanobesTocPage(
    html: String,
    novelId: String,
): RanobesTocPage {
    val script =
        Jsoup.parse(html).selectFirst("script#chapters-data")
            ?: throw NetworkParseException("Ranobes chapter list was not found")
    val data =
        runCatching { JsonParser.parseString(script.data().ifBlank { script.html() }) }
            .getOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: throw NetworkParseException("Ranobes chapter list could not be read")
    val pageNumber = data.ranobesInt("cstart")
    val pageCount = data.ranobesInt("pages_count")
    val totalCount = data.ranobesInt("count_all")
    if (pageNumber == null || pageCount == null || totalCount == null) {
        throw NetworkParseException("Ranobes chapter list has no page information")
    }
    val chapters =
        data.ranobesObjects("chapters").map { entry ->
            ranobesTocChapter(entry, novelId)
                ?: throw NetworkParseException("Ranobes chapter list names a chapter outside this novel")
        }
    return RanobesTocPage(chapters, pageNumber, pageCount, totalCount)
}

private fun ranobesTocChapter(
    entry: JsonObject,
    novelId: String,
): ChapterInfo? {
    val address = entry.ranobesString("link")?.let(RanobesAddress::parse) ?: return null
    val url = address.chapterUrl ?: return null
    if (address.novelId != novelId) return null
    return ChapterInfo(
        id = RanobesProvider.getChapterId(url) ?: return null,
        title = sanitizeTitle(entry.ranobesString("title").orEmpty()),
        url = url,
        publishedAt = ranobesTimestamp(entry.ranobesString("date")),
    )
}

private fun JsonObject.ranobesString(name: String): String? =
    get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString }.getOrNull() }

private fun JsonObject.ranobesInt(name: String): Int? = ranobesString(name)?.trim()?.toIntOrNull()

private fun JsonObject.ranobesObjects(name: String): List<JsonObject> =
    get(name)
        ?.takeIf { it.isJsonArray }
        ?.asJsonArray
        ?.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
        .orEmpty()

/**
 * A chapter's text, formatted as HTML. The site injects advertisements between the paragraphs;
 * navigation, comments and the title stay on the page.
 */
internal fun ranobesChapterContent(html: String): String {
    val content =
        Jsoup.parse(html, RanobesProvider.baseUrl).selectFirst("#arrticle")
            ?: throw NetworkParseException("Ranobes chapter content not found on page")
    val body = content.clone()
    body.select("script, style, noscript, iframe, .free-support-top, [id^='bg-ssp']").remove()
    if (body.text().isBlank() && body.select("img[src], audio, video, hr").isEmpty()) {
        throw NetworkParseException("Ranobes returned an empty chapter")
    }
    // Illustrations are addressed from the site root.
    body.select("[href], [src]").forEach { element ->
        listOf("href", "src").filter(element::hasAttr).forEach { attribute ->
            element.absUrl(attribute).takeIf(String::isNotEmpty)?.let { element.attr(attribute, it) }
        }
    }
    return body.html().trim()
}

private val ranobesTimestampFormatters =
    listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "d MMMM yyyy, HH:mm:ss", "d MMMM yyyy, HH:mm", "d MMMM yyyy")
        .map { pattern ->
            DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(Locale.US)
        }

/**
 * A site timestamp as epoch millis, or null when it is not one. Naive values are UTC: the
 * `<time datetime>` of a date the site also prints in its `yyyy-MM-dd HH:mm:ss` form carries `+00:00`.
 */
internal fun ranobesTimestamp(value: String?): Long? {
    val text = normalizedSourceText(value)
    if (text.isBlank()) return null
    runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
    return ranobesTimestampFormatters.firstNotNullOfOrNull { formatter ->
        runCatching { LocalDateTime.parse(text, formatter).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
    }
}
