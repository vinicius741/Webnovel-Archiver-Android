package com.vinicius741.webnovelarchiver.source

import com.vinicius741.webnovelarchiver.domain.archive.PercentEncoding
import com.vinicius741.webnovelarchiver.domain.model.NovelMetadata
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import org.jsoup.Jsoup

object SpaceBattlesProvider : XenForoProvider(spaceBattlesSourceDescriptor, "sb") {
    override suspend fun enrichMetadata(
        metadata: NovelMetadata,
        html: String,
        url: String,
        network: NetworkClient,
        progress: (String) -> Unit,
    ): NovelMetadata {
        val enriched = super.enrichMetadata(metadata, html, url, network, progress)
        val root = storyRoot(Jsoup.parse(html, url), url)

        progress("Fetching Story Library engagement...")
        val origin = java.net.URI(root).let { "${it.scheme}://${it.rawAuthority}" }
        val libraryUrl = "$origin/library/stories/?starter=${PercentEncoding.encodeURIComponent(metadata.author)}"
        val libraryHtml = fetchOptional(network, libraryUrl)
        val threadId = getStoryId(root).removePrefix("sb_")
        val libraryMetrics =
            if (libraryHtml != null) {
                parseSpaceBattlesLibraryMetrics(libraryHtml, threadId)
            } else {
                emptyList()
            }
        return enriched.copy(sourceMetadata = mergeSpaceBattlesMetrics(enriched.sourceMetadata, libraryMetrics))
    }
}
