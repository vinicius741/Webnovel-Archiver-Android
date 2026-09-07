package com.vinicius741.webnovelarchiver.source

import com.vinicius741.webnovelarchiver.domain.model.ChapterInfo
import com.vinicius741.webnovelarchiver.domain.model.NovelMetadata
import com.vinicius741.webnovelarchiver.domain.model.PublicationStatus
import com.vinicius741.webnovelarchiver.domain.model.SourceMetadata
import com.vinicius741.webnovelarchiver.domain.model.SourceMetric
import com.vinicius741.webnovelarchiver.domain.model.SourceMetricKind
import com.vinicius741.webnovelarchiver.source.network.NetworkParseException
import com.vinicius741.webnovelarchiver.source.network.SourceChapterListIncompleteException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

internal fun parseAo3Metadata(html: String): NovelMetadata {
    val doc = Jsoup.parse(html, Ao3Provider.baseUrl)
    val preface = doc.selectFirst("#workskin > .preface")
    val title =
        preface?.selectFirst("h2.title")?.text()?.takeIf(String::isNotBlank)
            ?: throw NetworkParseException("AO3 work was not found. It may require login, be unrevealed, or be unavailable.")
    val counts = ao3ChapterCounts(doc)
    val completed = counts.second == counts.first
    val authors = preface.select(".byline a[rel=author]").map { it.text() }.distinct()
    val metrics =
        listOf(
            "words" to SourceMetricKind.WORDS,
            "hits" to SourceMetricKind.TOTAL_VIEWS,
            "kudos" to SourceMetricKind.KUDOS,
            "bookmarks" to SourceMetricKind.BOOKMARKS,
            "comments" to SourceMetricKind.COMMENTS,
        ).mapNotNull { (css, kind) ->
            parseSourceMetricValue(doc.selectFirst("dl.stats dd.$css")?.text())?.let { SourceMetric(kind, it) }
        }
    return NovelMetadata(
        title = title,
        author =
            authors.joinToString(", ").ifBlank {
                preface
                    .selectFirst(".byline")
                    ?.text()
                    .orEmpty()
                    .ifBlank { "Anonymous" }
            },
        description = preface.selectFirst(".summary blockquote.userstuff")?.blockText(),
        tags =
            doc
                .select("dl.work.meta dd.tags a.tag")
                .map { it.text() }
                .distinct()
                .toMutableList(),
        publicationStatus = if (completed) PublicationStatus.completed else PublicationStatus.ongoing,
        sourceMetadata =
            SourceMetadata(
                metrics = metrics.toMutableList(),
                publishedAt = doc.selectFirst("dl.stats dd.published")?.text()?.let(::parseSourceDateMillis),
                updatedAt = doc.selectFirst("dl.stats dd.status")?.text()?.let(::parseSourceDateMillis),
                contentRating = doc.selectFirst("dd.rating")?.text(),
                contentWarnings = doc.ao3Tags("warning"),
                sourceStatus = if (completed) "Completed" else "Ongoing",
                language = doc.selectFirst("dd.language")?.text(),
                fandoms = doc.ao3Tags("fandom"),
                characters = doc.ao3Tags("character"),
            ),
    )
}

internal fun ao3ChapterCounts(doc: Document): Pair<Int, Int?> {
    val raw =
        doc
            .selectFirst("dl.stats dd.chapters")
            ?.text()
            .orEmpty()
            .replace(",", "")
    val match = Regex("""^(\d+)\s*/\s*(\d+|\?)$""").matchEntire(raw)
    val published = match?.groupValues?.get(1)?.toIntOrNull()
    if (published == null || published < 1) throw NetworkParseException("AO3 published chapter count was not found")
    return published to match.groupValues[2].toIntOrNull()
}

internal fun parseAo3ChapterIndex(
    html: String,
    workUrl: String,
    expectedCount: Int? = null,
): List<ChapterInfo> {
    val doc = Jsoup.parse(html, workUrl)
    val rows = doc.select("ol.chapter.index > li")
    val chapters =
        rows.mapIndexedNotNull { index, row ->
            val link = row.selectFirst("a[href]") ?: return@mapIndexedNotNull null
            val url = link.absUrl("href")
            val id = Ao3Provider.getChapterId(url) ?: return@mapIndexedNotNull null
            if (Ao3Provider.normalizeStoryUrl(url) != workUrl) return@mapIndexedNotNull null
            ChapterInfo(
                id = id,
                title = link.text().replace(Regex("""^\d+\.\s*"""), "").ifBlank { "Chapter ${index + 1}" },
                url = url.substringBefore('?').substringBefore('#'),
                chapterNumber = index + 1,
                publishedAt =
                    row
                        .selectFirst(".datetime")
                        ?.text()
                        ?.trim('(', ')', ' ')
                        ?.let(::parseSourceDateMillis),
            )
        }
    if (chapters.isEmpty() || (expectedCount != null && chapters.size != expectedCount) ||
        rows.size != chapters.size || chapters.map { it.id }.distinct().size != chapters.size
    ) {
        throw SourceChapterListIncompleteException(
            "AO3 chapter index is incomplete: expected ${expectedCount ?: "a complete index of"} chapters, found ${chapters.size}",
        )
    }
    return chapters
}

private fun Document.ao3Tags(css: String): MutableList<String> =
    select("dl.work.meta dd.$css a.tag").map { it.text() }.distinct().toMutableList()
