package com.vinicius741.webnovelarchiver.source

import com.vinicius741.webnovelarchiver.domain.model.SourceMetadata
import com.vinicius741.webnovelarchiver.domain.model.SourceMetricKind
import org.jsoup.nodes.Document

internal val scribbleHubStatLabels =
    linkedMapOf(
        "Readers" to SourceMetricKind.READERS,
        "Favorites" to SourceMetricKind.FAVORITES,
        "Total Views (All)" to SourceMetricKind.TOTAL_VIEWS,
        "Total Views" to SourceMetricKind.TOTAL_VIEWS,
        "Total Views (Chapters)" to SourceMetricKind.TOTAL_VIEWS_CHAPTERS,
        "Chapter Views" to SourceMetricKind.TOTAL_VIEWS_CHAPTERS,
        "Average Views" to SourceMetricKind.AVERAGE_VIEWS,
        "Word Count" to SourceMetricKind.WORDS,
        "Words" to SourceMetricKind.WORDS,
        "Average Words" to SourceMetricKind.AVERAGE_WORDS,
        "Pages" to SourceMetricKind.PAGES,
        "Chapters/Week" to SourceMetricKind.CHAPTERS_PER_WEEK,
        "Chapters / Week" to SourceMetricKind.CHAPTERS_PER_WEEK,
        "Chapters per Week" to SourceMetricKind.CHAPTERS_PER_WEEK,
        "Ratings" to SourceMetricKind.RATINGS,
        "Reviews" to SourceMetricKind.REVIEWS,
    )

internal fun scribbleHubCanonicalSeriesUrl(value: String): String? {
    val candidate =
        value
            .substringBefore('#')
            .substringBefore('?')
            .trim()
            .trimEnd('/')
            .removeSuffix("/stats")
            .trimEnd('/')
    return candidate.takeIf {
        Regex("""(?i)^https?://[^/]+/series/\d+(?:/[^/?#]+)?$""").matches(it)
    }
}

internal fun mergeScribbleHubSourceMetadata(
    base: SourceMetadata,
    supplement: SourceMetadata,
): SourceMetadata {
    val mergedMetrics =
        (base.metrics + supplement.metrics)
            .distinctBy { it.kind }
            .toMutableList()
    return base.copy(
        metrics = mergedMetrics,
        createdAt = base.createdAt ?: supplement.createdAt,
        publishedAt = base.publishedAt ?: supplement.publishedAt,
        updatedAt = base.updatedAt ?: supplement.updatedAt,
        contentRating = base.contentRating ?: supplement.contentRating,
        contentWarnings = (base.contentWarnings + supplement.contentWarnings).distinct().toMutableList(),
        sourceType = base.sourceType ?: supplement.sourceType,
        sourceCategory = base.sourceCategory ?: supplement.sourceCategory,
        sourceListingState = base.sourceListingState ?: supplement.sourceListingState,
        sourceStatus = base.sourceStatus ?: supplement.sourceStatus,
        language = base.language ?: supplement.language,
        genres = (base.genres + supplement.genres).distinct().toMutableList(),
        fandoms = (base.fandoms + supplement.fandoms).distinct().toMutableList(),
        characters = (base.characters + supplement.characters).distinct().toMutableList(),
        ratingDistribution = (base.ratingDistribution + supplement.ratingDistribution).toMutableMap(),
    )
}

internal fun scribbleHubExtractLabeledValues(doc: Document): Map<SourceMetricKind, String> {
    val values = linkedMapOf<SourceMetricKind, String>()

    fun record(
        label: String,
        value: String?,
    ) {
        val kind = scribbleHubStatKind(label) ?: return
        val normalizedValue = normalizedSourceText(value)
        if (normalizedValue.isNotBlank() && kind !in values) values[kind] = normalizedValue
    }

    doc.select("tr").forEach { row ->
        val cells = row.select("th, td")
        if (cells.size >= 2) {
            cells.firstOrNull()?.let { first ->
                record(first.text(), cells.drop(1).joinToString(" ") { it.text() })
            }
        }
    }
    doc.select("dt").forEach { label ->
        record(label.text(), label.nextElementSibling()?.text())
    }
    doc.select("*").asReversed().forEach { element ->
        val ownText = normalizedSourceText(element.ownText())
        if (scribbleHubStatKind(ownText) != null) {
            record(ownText, element.nextElementSibling()?.text())
        }
        val text = normalizedSourceText(element.text())
        scribbleHubStatLabels.keys.forEach { label ->
            val value =
                Regex("""(?i)^${Regex.escape(label)}(?:\s*[:\-]\s*|\s+)(.+)$""")
                    .matchEntire(text)
                    ?.groupValues
                    ?.getOrNull(1)
            if (value != null) record(label, value)
        }
    }
    return values
}

private fun scribbleHubStatKind(value: String): SourceMetricKind? {
    val normalized = normalizedSourceText(value).trimEnd(':').trim()
    return scribbleHubStatLabels.entries.firstOrNull { normalized.equals(it.key, ignoreCase = true) }?.value
}

internal fun scribbleHubParseRatingsCount(doc: Document): Long? {
    val ratingBlocks =
        doc
            .select("#ratefic_user, [id*=rating], [class*=rating], [id*=rate], [class*=rate]")
            .map { normalizedSourceText(it.text()) }
            .filter { it.isNotBlank() }
    val candidates = (ratingBlocks + normalizedSourceText(doc.text())).distinct()
    val patterns =
        listOf(
            Regex("""(?i)\(\s*([0-9][0-9,._]*\s*[KMB]?)\s+ratings?\s*\)"""),
            Regex("""(?i)\b([0-9][0-9,._]*\s*[KMB]?)\s+ratings?\b"""),
        )
    return candidates
        .asSequence()
        .flatMap { text -> patterns.asSequence().mapNotNull { it.find(text)?.groupValues?.getOrNull(1) } }
        .mapNotNull(::parseSourceMetricValue)
        .firstOrNull()
}

internal fun scribbleHubParsePublicationDate(doc: Document): Long? {
    val jsonLdDate =
        doc
            .select("script[type=application/ld+json]")
            .asSequence()
            .map { it.data().ifBlank { it.html() } }
            .mapNotNull {
                Regex("""(?i)"datePublished"\s*:\s*"([^"]+)"""")
                    .find(it)
                    ?.groupValues
                    ?.getOrNull(1)
            }.firstOrNull()
    val fallbackDate =
        doc
            .selectFirst("meta[itemprop=datePublished], meta[property=article:published_time]")
            ?.attr("content")
    return parseSourceDateMillis(jsonLdDate ?: fallbackDate)
}

internal fun String.stripScribbleHubSynopsisToggle(): String =
    replace(Regex("""(?i)(?:(?:\.{3,}|…)\s*){1,2}more\s*>>\s*|\s*<<\s*less\s*"""), " ")
        .replace(Regex("""[ \t]+\n"""), "\n")
        .replace(Regex("""\n[ \t]+"""), "\n")
        .replace(Regex("""[ \t]{2,}"""), " ")
        .trim()
