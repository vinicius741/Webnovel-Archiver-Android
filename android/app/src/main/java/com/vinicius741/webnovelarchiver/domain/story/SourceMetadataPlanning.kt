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
        preferredKinds: List<SourceMetricKind>,
        metadata: SourceMetadata,
        hasScore: Boolean,
    ): List<SourceMetric> {
        val byKind = metadata.metrics.associateBy { it.kind }
        return preferredKinds.mapNotNull(byKind::get).take(if (hasScore) 3 else 4)
    }

    fun metric(
        metadata: SourceMetadata,
        kind: SourceMetricKind,
    ): SourceMetric? = metadata.metrics.firstOrNull { it.kind == kind }
}
