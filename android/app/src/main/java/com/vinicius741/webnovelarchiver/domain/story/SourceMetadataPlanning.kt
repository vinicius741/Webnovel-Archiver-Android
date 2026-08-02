package com.vinicius741.webnovelarchiver.domain.story

import com.vinicius741.webnovelarchiver.domain.model.SourceMetadata
import com.vinicius741.webnovelarchiver.domain.model.SourceMetric
import com.vinicius741.webnovelarchiver.domain.model.SourceMetricKind

/**
 * Selects the small, source-native set that earns space on Details. Parsing and persistence may
 * retain more fields for filtering or future features, but new metrics must not appear in the UI
 * accidentally just because a provider learned how to parse them.
 */
object SourceMetadataPlanning {
    fun detailMetrics(
        sourceName: String?,
        metadata: SourceMetadata,
        hasScore: Boolean,
    ): List<SourceMetric> {
        val preferred =
            when (sourceName?.lowercase()) {
                "royalroad" -> listOf(SourceMetricKind.FOLLOWERS, SourceMetricKind.TOTAL_VIEWS, SourceMetricKind.PAGES)
                "scribble hub" -> listOf(SourceMetricKind.READERS, SourceMetricKind.TOTAL_VIEWS, SourceMetricKind.WORDS)
                "spacebattles" -> listOf(SourceMetricKind.WATCHERS, SourceMetricKind.LIKES, SourceMetricKind.WORDS)
                "fanfiction.net" ->
                    listOf(
                        SourceMetricKind.FAVORITES,
                        SourceMetricKind.FOLLOWS,
                        SourceMetricKind.REVIEWS,
                        SourceMetricKind.WORDS,
                    )
                else -> emptyList()
            }
        val byKind = metadata.metrics.associateBy { it.kind }
        return preferred.mapNotNull(byKind::get).take(if (hasScore) 3 else 4)
    }

    fun metric(
        metadata: SourceMetadata,
        kind: SourceMetricKind,
    ): SourceMetric? = metadata.metrics.firstOrNull { it.kind == kind }
}
