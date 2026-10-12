package com.vinicius741.webnovelarchiver.source

import com.vinicius741.webnovelarchiver.domain.model.NovelMetadata
import com.vinicius741.webnovelarchiver.domain.model.SourceMetricKind
import com.vinicius741.webnovelarchiver.source.network.NetworkParseException
import com.vinicius741.webnovelarchiver.source.network.SourceNetworkPolicy
import org.jsoup.Jsoup

object SufficientVelocityProvider : XenForoProvider(
    SourceDescriptor(
        id = "sufficient_velocity",
        displayName = "Sufficient Velocity",
        browseUrl = "https://forums.sufficientvelocity.com",
        hosts = setOf("forums.sufficientvelocity.com"),
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
        featuredMetrics = listOf(SourceMetricKind.WATCHERS, SourceMetricKind.WORDS),
    ),
    "sv",
) {
    override fun getStoryId(url: String): String {
        val id = super.getStoryId(url)
        check(!id.startsWith("sv_url_")) { "Sufficient Velocity thread URL was not recognized" }
        return id
    }

    override fun parseMetadata(html: String): NovelMetadata {
        val metadata = super.parseMetadata(html)
        if (metadata.title == "Unknown Title" || metadata.author == "Unknown Author") {
            throw NetworkParseException("Sufficient Velocity story metadata was not found")
        }
        return metadata.copy(patreonUrl = findPatreonUrl(Jsoup.parse(html, baseUrl)))
    }
}
