package com.vinicius741.webnovelarchiver.source

import com.vinicius741.webnovelarchiver.domain.model.ChapterInfo
import com.vinicius741.webnovelarchiver.domain.model.NovelMetadata
import com.vinicius741.webnovelarchiver.domain.model.SourceMetricKind
import com.vinicius741.webnovelarchiver.source.local.LocalSourceProvider
import com.vinicius741.webnovelarchiver.source.local.LocalSourceUrls
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import com.vinicius741.webnovelarchiver.source.network.SourceNetworkPolicy
import kotlinx.coroutines.CancellationException
import java.net.URI
import java.util.Locale

enum class SourceUrlKind {
    STORY,
    CHAPTER,
}

/** Where a source's stories come from. */
enum class SourceKind {
    /** A website read over HTTP(S). */
    WEB,

    /** Files in the user's local folder, addressed by `local://` URLs and never fetched over the network. */
    LOCAL,
}

enum class SourceUserAgentMode {
    DEFAULT,
    DESKTOP,

    /**
     * The system WebView's own User-Agent, unchanged, and page navigations that carry only the
     * headers Chromium writes itself. For a source that is always read through the WebView: the
     * rewritten [DEFAULT] string claims plain Chrome while the WebView's client hints and
     * `X-Requested-With` header still say WebView, a contradiction a bot check can see.
     */
    WEBVIEW,
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
    val kind: SourceKind = SourceKind.WEB,
) {
    val isLocal: Boolean get() = kind == SourceKind.LOCAL
}

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
            SufficientVelocityProvider,
            FanFictionProvider,
            Ao3Provider,
            RanobesProvider,
            LocalSourceProvider,
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
        val (sourceKind, host) = sourceAddress(submitted) ?: return null
        val provider = providers.firstOrNull { it.descriptor.kind == sourceKind && host in it.descriptor.normalizedHosts } ?: return null
        val kind = provider.classifyUrl(submitted) ?: return null
        if (requiredKind != null && kind != requiredKind) return null
        val normalized = if (kind == SourceUrlKind.STORY) provider.normalizeStoryUrl(submitted) else submitted
        val (normalizedKind, normalizedHost) = sourceAddress(normalized) ?: return null
        if (normalizedKind != sourceKind || normalizedHost !in provider.descriptor.normalizedHosts) return null
        return SourceMatch(provider, kind, submitted, normalized)
    }

    fun getProvider(url: String): SourceProvider? = resolve(url)?.provider

    fun getProvider(
        sourceId: String?,
        fallbackUrl: String,
    ): SourceProvider? = getById(sourceId) ?: getProvider(fallbackUrl)

    fun getById(sourceId: String?): SourceProvider? = sourceId?.let { id -> providers.firstOrNull { it.id == id } }

    /** The website that owns [host]; local sources own no network host. */
    fun providerForHost(host: String): SourceProvider? {
        val normalized = normalizeSourceHost(host)
        return web().firstOrNull { normalized in it.descriptor.normalizedHosts }
    }

    fun all(): List<SourceProvider> = providers

    /** Sources read from websites: the ones to browse, pace, and keep web sessions for. */
    fun web(): List<SourceProvider> = providers.filter { it.descriptor.kind == SourceKind.WEB }
}

private val SourceDescriptor.normalizedHosts: Set<String>
    get() = hosts.mapTo(mutableSetOf(), ::normalizeSourceHost)

/** The kind of source a URL's scheme addresses and its normalized host, or null for any other URL. */
private fun sourceAddress(url: String): Pair<SourceKind, String>? =
    runCatching {
        val uri = URI(url)
        val kind =
            when (uri.scheme?.lowercase(Locale.US)) {
                "http", "https" -> SourceKind.WEB
                LocalSourceUrls.SCHEME -> SourceKind.LOCAL
                else -> return null
            }
        uri.host?.let { kind to normalizeSourceHost(it) }
    }.getOrNull()

internal fun sourcePath(url: String): String? = runCatching { URI(url).path?.takeIf { it.startsWith('/') } }.getOrNull()

private fun normalizeSourceHost(host: String): String =
    host
        .trim()
        .lowercase(Locale.US)
        .removeSuffix(".")
        .removePrefix("www.")
