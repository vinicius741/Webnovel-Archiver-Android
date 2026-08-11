package com.vinicius741.webnovelarchiver.source

import com.vinicius741.webnovelarchiver.domain.model.SourceMetadata
import com.vinicius741.webnovelarchiver.domain.model.SourceMetric
import com.vinicius741.webnovelarchiver.domain.model.SourceMetricKind
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * XenForo 2.3 moved the main-category word total out of the labeled header pairs and into a summary
 * such as `Statistics (39 threadmarks, 166k words)`. Keep the older labeled `Words` parser in the
 * provider and use this as its current-markup fallback.
 */
internal fun spaceBattlesThreadmarkWordCount(doc: Document): Long? =
    doc
        .select("[aria-labelledby=threadmark-category-1] .block-formSectionHeader")
        .asSequence()
        .map { normalizedSourceText(it.text()) }
        .mapNotNull { summary ->
            Regex("""(?i)\b([0-9][0-9,._]*\s*[KMB]?)\s+words?\b""")
                .find(summary)
                ?.groupValues
                ?.get(1)
                ?.let(::parseSourceMetricValue)
        }.firstOrNull()

/** The base thread page includes the newest main threadmarks even when RSS enrichment degrades. */
internal fun spaceBattlesLatestMainThreadmarkAt(doc: Document): Long? =
    doc
        .select(".block-body--threadmarkBody.category-1 .structItem--threadmark")
        .mapNotNull { it.chapterPublishedAt() }
        .maxOrNull()

/**
 * Finds the exact Story Library card by stable thread id, never by title, then reads its labeled
 * engagement block. The author-filtered page may contain several stories from the same writer.
 */
internal fun parseSpaceBattlesLibraryMetrics(
    html: String,
    threadId: String,
): List<SourceMetric> {
    val doc = Jsoup.parse(html)
    val card =
        doc.selectFirst(".structItem--story.js-threadListItem-$threadId")
            ?: doc
                .select(".structItem--story")
                .firstOrNull { story ->
                    story
                        .select("a[href*=/threads/]")
                        .any { link ->
                            Regex("""\.${Regex.escape(threadId)}(?:[/?#]|$)""")
                                .containsMatchIn(link.attr("href"))
                        }
                }
            ?: return emptyList()
    val stats = card.select(".structItem-story-stats dl")
    return SPACEBATTLES_LIBRARY_METRICS.mapNotNull { (label, kind) ->
        val raw = sourceStatText(stats, label) ?: return@mapNotNull null
        parseSourceMetricValue(raw)?.let { value ->
            SourceMetric(
                kind = kind,
                value = value,
                isEstimated = Regex("""(?i)\d\s*[KMB]\b""").containsMatchIn(raw),
            )
        }
    }
}

/** Base-thread values win because Watchers is exact there while the library rounds it to `5K`. */
internal fun mergeSpaceBattlesMetrics(
    base: SourceMetadata,
    libraryMetrics: List<SourceMetric>,
): SourceMetadata {
    val present = base.metrics.mapTo(mutableSetOf()) { it.kind }
    val additions = libraryMetrics.filter { present.add(it.kind) }
    return base.copy(metrics = (base.metrics + additions).toMutableList())
}

private val SPACEBATTLES_LIBRARY_METRICS =
    listOf(
        "Words" to SourceMetricKind.WORDS,
        "Watchers" to SourceMetricKind.WATCHERS,
        "Replies" to SourceMetricKind.REPLIES,
        "Views" to SourceMetricKind.TOTAL_VIEWS,
        "Likes" to SourceMetricKind.LIKES,
    )
