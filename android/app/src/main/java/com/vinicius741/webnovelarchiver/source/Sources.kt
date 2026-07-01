package com.vinicius741.webnovelarchiver.source

import com.vinicius741.webnovelarchiver.data.backup.FullBackupPaths
import com.vinicius741.webnovelarchiver.domain.model.ChapterInfo
import com.vinicius741.webnovelarchiver.domain.model.NovelMetadata
import com.vinicius741.webnovelarchiver.ui.row
import com.vinicius741.webnovelarchiver.ui.size
import com.vinicius741.webnovelarchiver.ui.text
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class NetworkClient(
    /** Shared OkHttp client (R6). Cover/image fetches go through the same client as page fetches. */
    val client: OkHttpClient = defaultClient,
) {
    /**
     * Per-host rate-limit gates (R6). Each host has its own [Mutex]; the Scribble Hub gap is enforced
     * inside the lock so concurrent downloads from the activity + foreground service cannot bypass it.
     * The "next allowed at" timestamp is stored alongside the mutex so the lock is held only briefly.
     */
    private data class HostGate(
        val mutex: Mutex,
        var nextAllowedAt: Long = 0L,
    )

    private val hostGates = ConcurrentHashMap<String, HostGate>()
    private val hostGatesLock = Mutex()

    private suspend fun gateFor(host: String): HostGate =
        hostGatesLock.withLock {
            hostGates.getOrPut(host) { HostGate(Mutex()) }
        }

    suspend fun fetch(
        url: String,
        callTimeoutMillis: Long? = null,
    ): String {
        waitForRateLimit(url)
        val request = NetworkRequests.pageRequest(url)
        return executeWithRetries(url, request, callTimeoutMillis) { it.body?.string().orEmpty() }
    }

    suspend fun postForm(
        url: String,
        fields: Map<String, Any>,
        headers: Map<String, String> = emptyMap(),
    ): String {
        waitForRateLimit(url)
        val request = NetworkRequests.formRequest(url, fields, headers)
        return executeWithRetries(url, request) { it.body?.string().orEmpty() }
    }

    /**
     * Fetches a binary response (cover images, R6) through the shared OkHttp client with an
     * optional [maxBytes] cap. Returns null on non-2xx, non-image responses, or oversize bodies.
     * Respects the same per-host rate limit as [fetch] (R6) so cover fetches on Scribble Hub can't
     * stack 403s alongside page fetches.
     */
    suspend fun fetchBytes(
        url: String,
        maxBytes: Long = MAX_IMAGE_BYTES,
    ): ByteArray? {
        waitForRateLimit(url)
        val request = NetworkRequests.binaryRequest(url)
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val contentType = response.header("Content-Type").orEmpty()
                if (contentType.isNotBlank() && !contentType.startsWith("image/")) return@use null
                val body = response.body ?: return@use null
                val length = body.contentLength()
                if (length > maxBytes) return@use null
                val bytes = body.bytes()
                if (bytes.size.toLong() > maxBytes) return@use null
                bytes
            }
        }.getOrNull()
    }

    private suspend fun <T> executeWithRetries(
        url: String,
        request: Request,
        callTimeoutMillis: Long? = null,
        read: (Response) -> T,
    ): T {
        var attempt = 1
        while (attempt <= 3) {
            val call = client.newCall(request)
            callTimeoutMillis?.let { timeout -> call.timeout().timeout(timeout, TimeUnit.MILLISECONDS) }
            val response = call.execute()
            response.use {
                if (it.isSuccessful) return read(it)
                val host = runCatching { URL(url).host }.getOrNull()
                val shouldRetry = host == "www.scribblehub.com" && (it.code == 403 || it.code == 429) && attempt < 3
                if (!shouldRetry) throw IllegalStateException("HTTP ${it.code} for $url")
            }
            delay(2500L * attempt)
            attempt += 1
        }
        error("Failed to fetch $url")
    }

    private suspend fun waitForRateLimit(url: String) {
        val host = runCatching { URL(url).host }.getOrNull() ?: return
        val gap = if (host == "www.scribblehub.com") 1500L else 0L
        if (gap <= 0) return
        val gate = gateFor(host)
        gate.mutex.withLock {
            val now = System.currentTimeMillis()
            val next = gate.nextAllowedAt
            if (next > now) delay(next - now)
            gate.nextAllowedAt = System.currentTimeMillis() + gap
        }
    }

    companion object {
        /** Maximum bytes accepted for a cover/image download (R6 size cap). */
        const val MAX_IMAGE_BYTES = 8_000_000L

        val defaultClient: OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .build()
    }
}

object NetworkRequests {
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"
    const val DEFAULT_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
    const val FORM_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded; charset=UTF-8"

    fun pageRequest(url: String): Request =
        Request
            .Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", DEFAULT_ACCEPT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

    fun formRequest(
        url: String,
        fields: Map<String, Any>,
        headers: Map<String, String> = emptyMap(),
    ): Request {
        val bodyBuilder = FormBody.Builder()
        fields.forEach { (key, value) -> bodyBuilder.add(key, value.toString()) }
        val builder =
            Request
                .Builder()
                .url(url)
                .post(bodyBuilder.build())
                .header("User-Agent", USER_AGENT)
                .header("Accept", FORM_ACCEPT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Content-Type", FORM_CONTENT_TYPE)
                .header("X-Requested-With", "XMLHttpRequest")
        headers.forEach { (key, value) -> builder.header(key, value) }
        return builder.build()
    }

    /** Request builder for binary downloads (cover images) — reuses the shared client (R6). */
    fun binaryRequest(url: String): Request =
        Request
            .Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
}

interface SourceProvider {
    val name: String
    val baseUrl: String
    val supportsLatestChapterSync: Boolean get() = false

    fun isSource(url: String): Boolean

    fun getStoryId(url: String): String

    fun getChapterId(url: String): String?

    fun parseMetadata(html: String): NovelMetadata

    suspend fun getChapterList(
        html: String,
        url: String,
        network: NetworkClient,
        progress: (String) -> Unit = {},
    ): List<ChapterInfo>

    suspend fun getLatestChapterList(
        html: String,
        url: String,
        network: NetworkClient,
        progress: (String) -> Unit = {},
    ): List<ChapterInfo>? = null

    fun parseChapterContent(html: String): String
}

object SourceRegistry {
    private val providers = listOf(RoyalRoadProvider, ScribbleHubProvider)

    fun getProvider(url: String): SourceProvider? = providers.firstOrNull { it.isSource(url) }

    fun all(): List<SourceProvider> = providers
}

object RoyalRoadProvider : SourceProvider {
    override val name = "RoyalRoad"
    override val baseUrl = "https://www.royalroad.com"

    override fun isSource(url: String) = url.contains("royalroad.com", ignoreCase = true)

    override fun getStoryId(url: String) =
        Regex("fiction/(\\d+)")
            .find(url)
            ?.groupValues
            ?.get(1)
            ?.let { "rr_$it" } ?: "rr_${System.currentTimeMillis()}"

    override fun getChapterId(url: String) = Regex("/chapter/(\\d+)").find(url)?.groupValues?.get(1)

    override fun parseMetadata(html: String): NovelMetadata {
        val doc = Jsoup.parse(html)
        val title =
            doc
                .selectFirst("h1")
                ?.text()
                ?.trim()
                .orEmpty()
                .ifBlank { "Unknown Title" }
        val author =
            doc.selectFirst("h4 a")?.text()?.trim()
                ?: doc
                    .selectFirst("h4")
                    ?.text()
                    ?.replace("Author:", "")
                    ?.trim()
                ?: doc.selectFirst("meta[name=author]")?.attr("content")
                ?: doc.selectFirst("meta[property=article:author]")?.attr("content")
                ?: doc.selectFirst("meta[name=twitter:creator]")?.attr("content")
                ?: "Unknown Author"
        val cover =
            (doc.selectFirst(".page-content-inner .col-md-3 img")?.absUrl("src") ?: "")
                .ifBlank { doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty() }
                .ifBlank { null }
        val description =
            doc.selectFirst(".description")?.blockText()
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
                ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
        val genreTags =
            doc
                .select(".tags .label, .tags a, .tag")
                .map {
                    it.text().trim()
                }.filter { it.isNotBlank() && !it.equals("tags", true) }
        // Royal Road surfaces sensitive-content flags in a red "Warning: This fiction contains:" block
        // (div.font-red-sunglo with <strong>Warning</strong> + <ul class="list-inline">). Capture them as
        // tags with a ⚠ prefix so they stay distinct from genre tags while flowing through the tag system.
        val contentWarnings =
            doc
                .select("div.font-red-sunglo:has(strong:containsOwn(Warning)) ul.list-inline li")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .map { "\u26A0 $it" }
        val tags = (genreTags + contentWarnings).distinct().toMutableList()
        val score =
            doc
                .selectFirst(
                    ".list-unstyled li.list-item:contains(Overall Score)",
                )?.nextElementSibling()
                ?.selectFirst("span.star")
                ?.attr("data-content")
        val canonical = doc.selectFirst("link[rel=canonical]")?.absUrl("href") ?: doc.selectFirst("meta[property=og:url]")?.attr("content")
        val patreonUrl = findPatreonUrl(doc)
        return NovelMetadata(title, author, cover, description, tags.ifEmpty { null }, score, canonical, patreonUrl)
    }

    override suspend fun getChapterList(
        html: String,
        url: String,
        network: NetworkClient,
        progress: (String) -> Unit,
    ): List<ChapterInfo> {
        progress("Parsing chapter list...")
        val doc = Jsoup.parse(html, url)
        return doc.select(".chapter-row").mapNotNull { row ->
            val link = row.selectFirst("a[href*=/fiction/]") ?: return@mapNotNull null
            val href = link.absUrl("href").ifBlank { link.attr("href") }
            ChapterInfo(getChapterId(href), sanitizeTitle(link.text()), href)
        }
    }

    override fun parseChapterContent(html: String): String {
        val doc = Jsoup.parse(html)
        // Throw rather than returning a placeholder string: a returned string would be saved as the
        // chapter body and baked into the EPUB. Throwing routes the failure through DownloadEngine →
        // DownloadErrorClassifier, where it shows up on the download job (retryable parse error) and
        // never pollutes the generated book.
        val content = doc.selectFirst(".chapter-inner") ?: error("Chapter content not found on page")
        content.select("div.portlet, script, .bold.uppercase.text-center").remove()
        return content.html()
    }
}

@Suppress("TooManyFunctions")
object ScribbleHubProvider : SourceProvider {
    override val name = "Scribble Hub"
    override val baseUrl = "https://www.scribblehub.com"
    override val supportsLatestChapterSync = true
    private const val AJAX_URL = "https://www.scribblehub.com/wp-admin/admin-ajax.php"
    private const val MAX_TOC_PAGE_SIZE = 50
    private val tocPageSizeHeaders = mapOf("Cookie" to "toc_show=$MAX_TOC_PAGE_SIZE")

    override fun isSource(url: String) =
        Regex("https?://(?:www\\.)?scribblehub\\.com/(series|read)/", RegexOption.IGNORE_CASE).containsMatchIn(url)

    override fun getStoryId(url: String) =
        Regex("/series/(\\d+)", RegexOption.IGNORE_CASE)
            .find(url)
            ?.groupValues
            ?.get(1)
            ?.let { "sh_$it" }
            ?: "sh_url_${FullBackupPaths.encodeURIComponent(url.lowercase())}"

    override fun getChapterId(url: String) =
        Regex("/chapter/(\\d+)", RegexOption.IGNORE_CASE)
            .find(url)
            ?.groupValues
            ?.get(1)
            ?.let { "sh_$it" }

    override fun parseMetadata(html: String): NovelMetadata {
        val doc = Jsoup.parse(html, baseUrl)
        val title =
            firstText(doc, ".fic_title", ".wi_fic_title", "h1").ifBlank {
                doc.selectFirst("meta[property=og:title]")?.attr("content")
                    ?: "Unknown Title"
            }
        val author =
            firstText(doc, ".auth_name_fic a", ".auth_name_fic", "a[href*=/profile/]").ifBlank {
                doc.selectFirst("meta[name=author]")?.attr("content")
                    ?: "Unknown Author"
            }
        val cover =
            (doc.selectFirst(".fic_image img")?.absUrl("src") ?: "")
                .ifBlank { doc.selectFirst(".fic_image img")?.absUrl("data-src").orEmpty() }
                .ifBlank { doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty() }
                .ifBlank { null }
        val description =
            firstStructuredText(doc, ".wi_fic_desc", ".fic_synopsis", "#synopsis")
                .ifBlank {
                    doc.selectFirst("meta[property=og:description]")?.attr("content")
                        ?: doc.selectFirst("meta[name=description]")?.attr("content").orEmpty()
                }.ifBlank { null }
        val canonical = doc.selectFirst("link[rel=canonical]")?.absUrl("href") ?: doc.selectFirst("meta[property=og:url]")?.attr("content")
        val tags =
            doc
                .select(".wi_fic_genre a, .wi_fic_tags a, .fic_genre a, .fic_tags a, a[href*=/genre/], a[href*=/tag/]")
                .map {
                    it.text().trim()
                }.filter { it.isNotBlank() }
                .distinct()
                .toMutableList()
        val score = scribbleHubScore(doc)
        val patreonUrl = findPatreonUrl(doc)
        return NovelMetadata(title, author, cover, description, tags.ifEmpty { null }, score, canonical, patreonUrl)
    }

    override suspend fun getChapterList(
        html: String,
        url: String,
        network: NetworkClient,
        progress: (String) -> Unit,
    ): List<ChapterInfo> {
        progress("Parsing chapter list...")
        val doc = Jsoup.parse(html, url)
        val postId = doc.selectFirst("#mypostid")?.attr("value")
        val chapters = parseToc(doc).toMutableList()
        val seen = chapters.map { it.url }.toMutableSet()
        if (!postId.isNullOrBlank() && chapters.size >= 15) {
            progress("Fetching chapter page 1...")
            val firstPage = fetchTocPage(network, url, postId, 1)
            if (firstPage.size >= chapters.size) {
                chapters.clear()
                chapters.addAll(firstPage)
                seen.clear()
                seen.addAll(chapters.map { it.url })
            }
            for (page in 2..500) {
                progress("Fetching chapter page $page...")
                val pageChapters = fetchTocPage(network, url, postId, page)
                if (pageChapters.isEmpty()) break
                val newOnes = pageChapters.filter { seen.add(it.url) }
                if (newOnes.isEmpty()) break
                chapters.addAll(newOnes)
                if (pageChapters.size < MAX_TOC_PAGE_SIZE) break
            }
        }
        return chapters.asReversed()
    }

    override suspend fun getLatestChapterList(
        html: String,
        url: String,
        network: NetworkClient,
        progress: (String) -> Unit,
    ): List<ChapterInfo>? {
        val doc = Jsoup.parse(html, url)
        val postId = doc.selectFirst("#mypostid")?.attr("value")
        val chapters = parseToc(doc)
        val latest =
            if (!postId.isNullOrBlank() && chapters.size >= 15) {
                progress("Fetching latest chapter page...")
                fetchTocPage(network, url, postId, 1).ifEmpty { chapters }
            } else {
                chapters
            }
        return latest.asReversed()
    }

    override fun parseChapterContent(html: String): String {
        val doc = Jsoup.parse(html)
        // See RoyalRoadProvider.parseChapterContent: throw instead of returning a placeholder so the
        // failure surfaces as a download-job error rather than getting embedded in the EPUB.
        val content = doc.selectFirst("#chp_raw") ?: error("Chapter content not found on page")
        content.select("script, style, .wi_authornotes, .sharedaddy, .code-block").remove()
        return content.html().trim()
    }

    private fun parseToc(doc: Document): List<ChapterInfo> =
        doc.select(".toc_ol li").mapNotNull { li ->
            val link = li.selectFirst("a[href*=/read/][href*=/chapter/]") ?: return@mapNotNull null
            val href = link.absUrl("href").ifBlank { link.attr("href") }
            ChapterInfo(getChapterId(href), sanitizeTitle(link.text()).ifBlank { "Untitled Chapter" }, href)
        }

    private suspend fun fetchTocPage(
        network: NetworkClient,
        url: String,
        postId: String,
        page: Int,
    ): List<ChapterInfo> {
        val pageHtml =
            network
                .postForm(
                    AJAX_URL,
                    mapOf(
                        "action" to "wi_getreleases_pagination",
                        "pagenum" to page,
                        "mypostid" to postId,
                    ),
                    tocPageSizeHeaders,
                ).replace(Regex("0\\s*$"), "")
                .trim()
        return parseToc(Jsoup.parse(pageHtml, url))
    }

    private fun firstText(
        doc: Document,
        vararg selectors: String,
    ): String =
        selectors
            .firstNotNullOfOrNull { selector ->
                doc.selectFirst(selector)?.text()?.trim()?.takeIf {
                    it.isNotBlank()
                }
            }.orEmpty()

    // Like [firstText], but preserves the original paragraph/line-break layout of the matched
    // element via [blockText]. Used for long free-form fields (e.g. synopsis) where collapsing
    // `<p>`/`<br>` into a single space-joined line would discard the author's structure.
    private fun firstStructuredText(
        doc: Document,
        vararg selectors: String,
    ): String =
        selectors
            .firstNotNullOfOrNull { selector ->
                doc.selectFirst(selector)?.blockText()?.takeIf { it.isNotBlank() }
            }.orEmpty()

    private fun scribbleHubScore(doc: Document): String? =
        doc
            .select("script[type=application/ld+json]")
            .asSequence()
            .map { it.data().ifBlank { it.html() } }
            .filter { it.contains("AggregateRating") }
            .mapNotNull {
                Regex(""""ratingValue"\s*:\s*"?([^",}]+)"?""")
                    .find(it)
                    ?.groupValues
                    ?.get(1)
                    ?.trim()
            }.firstOrNull { it.isNotBlank() }
            ?: firstText(doc, ".numscore", "[itemprop=ratingValue]", ".rate_fic_user").ifBlank { null }
            ?: doc
                .selectFirst("#ratefic_user")
                ?.text()
                ?.let { Regex("""\d+(?:\.\d+)?(?=\s*\()""").find(it)?.value }
}

private val descriptionBlockTags =
    setOf("p", "div", "li", "blockquote", "h1", "h2", "h3", "h4", "h5", "h6", "hr")

/**
 * Returns the visible text of [element] while preserving the author's paragraph/line-break layout.
 * Unlike Jsoup's [Element.text] — which flattens every `<p>`/`<div>`/`<br>` into a single
 * space-joined line — this walks the DOM and inserts `\n\n` around block elements and `\n` for
 * `<br>`, then collapses runs of blank lines so the result is a clean, structured string.
 *
 * Used for novel descriptions so the Details screen and EPUB details page render real paragraphs
 * instead of one dumped block of text (EpubContent.details already splits on `\n+`).
 */
private fun Element.blockText(): String {
    val builder = StringBuilder()

    fun walk(node: Node) {
        when (node) {
            is TextNode -> builder.append(node.text())
            is Element -> {
                val tag = node.tagName()
                val isBlock = tag in descriptionBlockTags
                if (isBlock && builder.isNotEmpty() && !builder.endsWith('\n')) {
                    builder.append("\n\n")
                }
                node.childNodes().forEach(::walk)
                if (tag == "br" && !builder.endsWith('\n')) {
                    builder.append('\n')
                } else if (isBlock && builder.isNotEmpty() && !builder.endsWith('\n')) {
                    builder.append("\n\n")
                }
            }
        }
    }
    walk(this)
    return builder
        .toString()
        .replace(Regex("\\u00A0"), " ") // non-breaking space → normal space
        .replace(Regex("[ \\t]+"), " ") // collapse intra-line whitespace
        .replace(Regex("\\n[ \\t]+"), "\n") // trim leading spaces on each line
        .replace(Regex("[ \\t]+\\n"), "\n") // trim trailing spaces on each line
        .replace(Regex("\\n{3,}"), "\n\n") // collapse blank-line runs
        .trim()
}

private fun findPatreonUrl(doc: Document): String? =
    doc
        .select("a[href*=patreon.com]")
        .asSequence()
        .filterNot { link -> link.parents().any { parent -> parent.classNames().any(::isCommentMarker) || isCommentMarker(parent.id()) } }
        .mapNotNull { link ->
            val url = link.absUrl("href").ifBlank { link.attr("href") }
            url
                .takeIf { Regex("https?://(?:www\\.)?patreon\\.com/", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                ?.substringBefore('?')
                ?.let { candidate -> candidate to patreonLinkPriority(link) }
        }.sortedByDescending { (_, priority) -> priority }
        .firstOrNull()
        ?.first

private fun isCommentMarker(value: String): Boolean = value.contains("comment", ignoreCase = true)

private fun patreonLinkPriority(link: org.jsoup.nodes.Element): Int {
    val context = (link.text() + " " + link.parents().take(3).joinToString(" ") { it.className() }).lowercase()
    return when {
        "author" in context -> 3
        "support" in context || "patreon" in link.text().lowercase() -> 2
        "description" in context || "fiction" in context || "profile" in context -> 1
        else -> 0
    }
}

fun sanitizeTitle(value: String): String {
    if (value.isBlank()) return "Untitled"
    val normalized = value.replace(Regex("[\\r\\n\\t]+"), " ").trim()
    val withoutTrailingOverflow =
        normalized
            .replace(Regex("(?:\\.{2,}|[\\u2026\\u22EE\\u22EF])\\s*$"), "")
            .trim()
    val withoutTimeAgo =
        withoutTrailingOverflow
            .replace(
                Regex(
                    "\\s*(?:[-–|]\\s*)?\\(?\\s*(?:\\d+|an?)\\s+(?:second|minute|hour|day|week|month|year)s?\\s+ago\\s*\\)?\\s*$",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            ).trim()
    val withoutDate =
        withoutTimeAgo
            .replace(
                Regex(
                    "\\s*(?:[-–|]\\s*)?\\(?\\s*(?:(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\.?\\s+\\d{1,2}(?:st|nd|rd|th)?,?\\s+\\d{4}|\\d{1,2}\\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\.?\\s+\\d{4})\\s*\\)?\\s*$",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            ).trim()
    return withoutDate.ifBlank { "Untitled" }
}
