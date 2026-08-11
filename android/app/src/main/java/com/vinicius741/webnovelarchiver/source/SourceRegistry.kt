package com.vinicius741.webnovelarchiver.source

import com.vinicius741.webnovelarchiver.domain.model.ChapterInfo
import com.vinicius741.webnovelarchiver.domain.model.NovelMetadata
import com.vinicius741.webnovelarchiver.domain.model.SourceMetricKind
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import com.vinicius741.webnovelarchiver.source.network.SourceNetworkPolicy
import kotlinx.coroutines.CancellationException
import java.net.URI
import java.util.Locale

enum class SourceUrlKind {
    STORY,
    CHAPTER,
}

enum class SourceUserAgentMode {
    DEFAULT,
    DESKTOP,
}

data class SourceCapabilities(
    val latestChapterSync: Boolean = false,
    val bulkDownloadPreflight: Boolean = true,
    val maximumDownloadConcurrency: Int? = null,
)

data class SourceCookieSeed(
    val url: String,
    val value: String,
)

data class SourceRenderedPageRule(
    val pathContains: String? = null,
    val pathSuffix: String? = null,
    val requiredSelector: String? = null,
    val allowEmptyBody: Boolean = false,
) {
    fun matches(path: String): Boolean =
        (pathContains == null || path.contains(pathContains, ignoreCase = true)) &&
            (pathSuffix == null || path.endsWith(pathSuffix, ignoreCase = true))
}

/** Stable identity and cross-cutting policy for one compiled-in story source. */
data class SourceDescriptor(
    val id: String,
    val displayName: String,
    val browseUrl: String,
    val hosts: Set<String>,
    val capabilities: SourceCapabilities = SourceCapabilities(),
    val networkPolicy: SourceNetworkPolicy = SourceNetworkPolicy(),
    val userAgentMode: SourceUserAgentMode = SourceUserAgentMode.DEFAULT,
    val managesBrowserSession: Boolean = false,
    val cookieSeeds: List<SourceCookieSeed> = emptyList(),
    val renderedPageRules: List<SourceRenderedPageRule> = emptyList(),
    val featuredMetrics: List<SourceMetricKind> = emptyList(),
)

data class SourceMatch(
    val provider: SourceProvider,
    val kind: SourceUrlKind,
    val submittedUrl: String,
    val normalizedUrl: String,
)

fun SourceProvider.isSource(url: String): Boolean = SourceRegistry.resolve(url)?.provider === this

/** A transient source read which can reuse its first response for a full-list fallback. */
class LoadedSourceStory(
    val metadata: NovelMetadata,
    val chapters: List<ChapterInfo>,
    val chaptersAreLatestOnly: Boolean,
    private val fullChapterLoader: suspend ((String) -> Unit) -> List<ChapterInfo>,
) {
    suspend fun loadFullChapterList(progress: (String) -> Unit = {}): List<ChapterInfo> = fullChapterLoader(progress)
}

internal suspend fun loadHtmlStory(
    provider: SourceProvider,
    url: String,
    preferLatestChapters: Boolean,
    network: NetworkClient,
    progress: (String) -> Unit,
): LoadedSourceStory {
    val html = network.fetch(url)
    val parsedMetadata = provider.parseMetadata(html)
    val metadata =
        try {
            provider.enrichMetadata(parsedMetadata, html, url, network, progress)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            parsedMetadata
        }
    progress("Parsing chapters...")
    val latest =
        if (preferLatestChapters && provider.supportsLatestChapterSync) {
            provider.getLatestChapterList(html, url, network, progress)
        } else {
            null
        }
    val chapters = latest ?: provider.getChapterList(html, url, network, progress)
    return LoadedSourceStory(
        metadata = metadata,
        chapters = chapters,
        chaptersAreLatestOnly = latest != null,
        fullChapterLoader = { fullProgress -> provider.getChapterList(html, url, network, fullProgress) },
    )
}

object SourceRegistry {
    private val providers =
        listOf(
            RoyalRoadProvider,
            ScribbleHubProvider,
            SpaceBattlesProvider,
            FanFictionProvider,
        )

    init {
        check(providers.map { it.id }.distinct().size == providers.size) { "Source IDs must be unique" }
        check(providers.all { it.id.matches(Regex("[a-z][a-z0-9_]*")) }) {
            "Source IDs must be stable lowercase identifiers"
        }
        check(providers.all { it.descriptor.hosts.isNotEmpty() }) { "Registered sources must own at least one host" }
    }

    fun resolve(
        url: String,
        requiredKind: SourceUrlKind? = null,
    ): SourceMatch? {
        val submitted = url.trim()
        val host = sourceHost(submitted) ?: return null
        val provider = providers.firstOrNull { host in it.descriptor.normalizedHosts } ?: return null
        val kind = provider.classifyUrl(submitted) ?: return null
        if (requiredKind != null && kind != requiredKind) return null
        val normalized = if (kind == SourceUrlKind.STORY) provider.normalizeStoryUrl(submitted) else submitted
        if (sourceHost(normalized) !in provider.descriptor.normalizedHosts) return null
        return SourceMatch(provider, kind, submitted, normalized)
    }

    fun getProvider(url: String): SourceProvider? = resolve(url)?.provider

    fun getProvider(
        sourceId: String?,
        fallbackUrl: String,
    ): SourceProvider? = getById(sourceId) ?: getProvider(fallbackUrl)

    fun getById(sourceId: String?): SourceProvider? = sourceId?.let { id -> providers.firstOrNull { it.id == id } }

    fun providerForHost(host: String): SourceProvider? {
        val normalized = normalizeSourceHost(host)
        return providers.firstOrNull { normalized in it.descriptor.normalizedHosts }
    }

    fun sourceIdForPersistedKey(key: String): String? =
        getById(key)?.id
            ?: providers.firstOrNull { it.name.equals(key, ignoreCase = true) }?.id

    fun all(): List<SourceProvider> = providers
}

private val SourceDescriptor.normalizedHosts: Set<String>
    get() = hosts.mapTo(mutableSetOf(), ::normalizeSourceHost)

private fun sourceHost(url: String): String? =
    runCatching {
        val uri = URI(url)
        if (uri.scheme?.lowercase(Locale.US) !in setOf("http", "https")) return null
        uri.host?.let(::normalizeSourceHost)
    }.getOrNull()

internal fun sourcePath(url: String): String? = runCatching { URI(url).path?.takeIf { it.startsWith('/') } }.getOrNull()

private fun normalizeSourceHost(host: String): String =
    host
        .trim()
        .lowercase(Locale.US)
        .removeSuffix(".")
        .removePrefix("www.")
