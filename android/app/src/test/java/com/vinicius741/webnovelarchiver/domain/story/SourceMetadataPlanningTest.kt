package com.vinicius741.webnovelarchiver.domain.story

import com.vinicius741.webnovelarchiver.domain.model.SourceMetadata
import com.vinicius741.webnovelarchiver.domain.model.SourceMetric
import com.vinicius741.webnovelarchiver.domain.model.SourceMetricKind
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceMetadataPlanningTest {
    @Test
    fun scoredSourcesReserveRatingContextAndThreeNativeFacts() {
        val metadata =
            SourceMetadata(
                metrics =
                    mutableListOf(
                        SourceMetric(SourceMetricKind.FOLLOWERS, 50),
                        SourceMetric(SourceMetricKind.TOTAL_VIEWS, 8_500),
                        SourceMetric(SourceMetricKind.PAGES, 182),
                        SourceMetric(SourceMetricKind.FAVORITES, 20),
                    ),
            )

        assertEquals(
            listOf(SourceMetricKind.FOLLOWERS, SourceMetricKind.TOTAL_VIEWS, SourceMetricKind.PAGES),
            SourceMetadataPlanning
                .detailMetrics(
                    listOf(SourceMetricKind.FOLLOWERS, SourceMetricKind.TOTAL_VIEWS, SourceMetricKind.PAGES),
                    metadata,
                    hasScore = true,
                ).map { it.kind },
        )
    }

    @Test
    fun unscoredSourcesUseTheirNativeFourValueBudgetAndHideUnpreferredMetrics() {
        val metadata =
            SourceMetadata(
                metrics =
                    mutableListOf(
                        SourceMetric(SourceMetricKind.FAVORITES, 24_000),
                        SourceMetric(SourceMetricKind.FOLLOWS, 23_100),
                        SourceMetric(SourceMetricKind.REVIEWS, 26_500),
                        SourceMetric(SourceMetricKind.WORDS, 716_431),
                        SourceMetric(SourceMetricKind.TOTAL_VIEWS, 99),
                    ),
            )

        assertEquals(
            listOf(
                SourceMetricKind.FAVORITES,
                SourceMetricKind.FOLLOWS,
                SourceMetricKind.REVIEWS,
                SourceMetricKind.WORDS,
            ),
            SourceMetadataPlanning
                .detailMetrics(
                    listOf(
                        SourceMetricKind.FAVORITES,
                        SourceMetricKind.FOLLOWS,
                        SourceMetricKind.REVIEWS,
                        SourceMetricKind.WORDS,
                    ),
                    metadata,
                    hasScore = false,
                ).map { it.kind },
        )
    }
}
