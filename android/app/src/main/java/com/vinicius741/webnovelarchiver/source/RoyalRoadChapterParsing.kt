package com.vinicius741.webnovelarchiver.source

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.vinicius741.webnovelarchiver.domain.model.ChapterInfo
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.math.max

/**
 * Royal Road chapter-list parsing (split from [RoyalRoadProvider] for file size).
 *
 * The fiction page carries the complete chapter list twice: as server-rendered `.chapter-row`
 * table rows and as the `window.chapters` JSON array the site's own React table renders from.
 * That React table paginates client-side — 10 rows on a phone-width viewport — so after the
 * Cloudflare WebView render the DOM keeps only one page of rows while `window.chapters` survives
 * with every chapter. The JSON array is therefore the primary source; table rows backfill URLs,
 * titles, and dates for entries with missing fields and cover pages without the script.
 */
internal fun royalRoadChapters(doc: Document): List<ChapterInfo> {
    val tableChapters = royalRoadTableChapters(doc)
    val windowChapters = royalRoadWindowChapters(doc)
    if (windowChapters.isEmpty()) return tableChapters

    val tableById = tableChapters.filter { it.id != null }.associateBy { it.id!! }
    val merged =
        windowChapters.mapNotNull { entry ->
            if (entry.id == null || !entry.visible) return@mapNotNull null
            val row = tableById[entry.id.toString()]
            val url =
                entry.url
                    ?.let { resolveRoyalRoadUrl(doc, it) }
                    ?.takeIf { it.isNotBlank() }
                    ?: row?.url.orEmpty()
            if (url.isBlank()) return@mapNotNull null
            ChapterInfo(
                id = entry.id.toString(),
                title = row?.title?.takeIf { it.isNotBlank() } ?: sanitizeTitle(entry.title.orEmpty()),
                url = url,
                chapterNumber = entry.order ?: row?.chapterNumber,
                publishedAt = entry.date?.let(::parseSourceDateMillis) ?: row?.publishedAt,
            )
        }
    if (merged.isEmpty()) return tableChapters

    val windowIds = windowChapters.mapNotNull { it.id }.map(Long::toString).toSet()
    return merged + tableChapters.filter { it.id != null && it.id !in windowIds }
}

/** Resolves a `window.chapters` URL (absolute or site-rooted) against the fiction page URL. */
private fun resolveRoyalRoadUrl(
    doc: Document,
    raw: String,
): String {
    if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
    val base = doc.baseUri().toHttpUrlOrNull() ?: return raw
    return base.resolve(raw)?.toString() ?: raw
}

internal fun royalRoadTableChapters(doc: Document): List<ChapterInfo> {
    val chapterNumbers = royalRoadChapterNumbers(doc)
    return doc.select(".chapter-row").mapNotNull { row ->
        val link = row.selectFirst("a[href*=/fiction/]") ?: return@mapNotNull null
        val href = link.absUrl("href").ifBlank { link.attr("href") }
        ChapterInfo(
            id = RoyalRoadProvider.getChapterId(href),
            title = sanitizeTitle(link.text()),
            url = href,
            chapterNumber =
                royalRoadChapterNumber(row, link)
                    ?: chapterNumbers[RoyalRoadProvider.getChapterId(href)]
                    ?: chapterNumberFromTitle(link.text()),
            publishedAt = row.chapterPublishedAt(),
        )
    }
}

private data class RoyalRoadWindowChapter(
    val id: Long?,
    val order: Int?,
    val title: String?,
    val url: String?,
    val date: String?,
    val visible: Boolean,
)

/**
 * Extracts the `window.chapters = [...]` array from inline scripts. The assignment shares its
 * script with other statements, so the array is cut out by scanning balanced JSON brackets while
 * tracking string/escape state — chapter titles freely contain `]`, `{`, and quotes.
 */
private fun royalRoadWindowChapters(doc: Document): List<RoyalRoadWindowChapter> {
    val entries = mutableListOf<RoyalRoadWindowChapter>()
    doc.select("script").forEach { script ->
        val source = script.data().ifBlank { script.html() }
        val start = source.indexOf("window.chapters")
        if (start < 0) return@forEach
        val open = source.indexOf('[', start)
        if (open < 0) return@forEach
        val arrayText = balancedJsonArrayAt(source, open) ?: return@forEach
        val array = runCatching { JsonParser.parseString(arrayText) }.getOrNull() ?: return@forEach
        if (!array.isJsonArray) return@forEach
        array.asJsonArray.forEach { element ->
            if (element.isJsonObject) entries += royalRoadWindowChapter(element.asJsonObject)
        }
    }
    return entries
}

private fun royalRoadWindowChapter(obj: JsonObject): RoyalRoadWindowChapter =
    RoyalRoadWindowChapter(
        id = obj.stringMember("id")?.toLongOrNull(),
        order = obj.stringMember("order")?.toIntOrNull(),
        title = obj.stringMember("title"),
        url = obj.stringMember("url"),
        date = obj.stringMember("date"),
        // Royal Road marks hidden chapters visible:0; absent or unknown values stay visible.
        visible = obj.stringMember("visible")?.toIntOrNull() != 0,
    )

private fun JsonObject.stringMember(name: String): String? =
    get(name)
        ?.takeIf { it.isJsonPrimitive }
        ?.let { primitive -> runCatching { primitive.asString }.getOrNull() }

/** Returns the `[...]` substring starting at [openIndex], or null when the array never closes. */
private fun balancedJsonArrayAt(
    source: String,
    openIndex: Int,
): String? {
    var depth = 0
    var inString = false
    var escaped = false
    for (index in openIndex until source.length) {
        val c = source[index]
        when {
            escaped -> escaped = false
            inString ->
                when (c) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
            c == '"' -> inString = true
            c == '[' || c == '{' -> depth++
            c == ']' || c == '}' -> {
                depth--
                if (depth == 0) return source.substring(openIndex, index + 1)
            }
        }
    }
    return null
}

/**
 * Scrapes chapter numbers from embedded script objects (`{id: ..., order: ...}`) for pages whose
 * table rows carry no number of their own. Superseded by [royalRoadWindowChapters] when present.
 */
internal fun royalRoadChapterNumbers(doc: Document): Map<String, Int> {
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
