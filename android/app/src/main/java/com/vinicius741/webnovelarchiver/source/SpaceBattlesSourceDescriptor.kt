package com.vinicius741.webnovelarchiver.source

import com.vinicius741.webnovelarchiver.domain.model.SourceMetricKind
import com.vinicius741.webnovelarchiver.source.network.SourceNetworkPolicy

/** Kept beside the large forum parser so its source policy remains provider-owned and reviewable. */
internal val spaceBattlesSourceDescriptor =
    SourceDescriptor(
        id = "space_battles",
        displayName = "SpaceBattles",
        browseUrl = "https://forums.spacebattles.com",
        hosts = setOf("spacebattles.com", "forums.spacebattles.com", "forum.spacebattles.com"),
        capabilities =
            SourceCapabilities(
                latestChapterSync = true,
                bulkDownloadPreflight = false,
                maximumDownloadConcurrency = 1,
            ),
        networkPolicy =
            SourceNetworkPolicy(
                minimumRequestGapMillis = 1_500L,
                maximumAttempts = 2,
                retryableStatusCodes = setOf(429, 503),
                maximumRequestsPerWindow = 30,
            ),
        managesBrowserSession = true,
        featuredMetrics =
            listOf(
                SourceMetricKind.WATCHERS,
                SourceMetricKind.LIKES,
                SourceMetricKind.WORDS,
            ),
    )
