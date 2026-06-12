package com.vinicius741.webnovelarchiver.core

import kotlinx.coroutines.delay
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URL
import java.util.concurrent.TimeUnit

class NetworkClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()
    private val nextAllowedByHost = mutableMapOf<String, Long>()

    suspend fun fetch(url: String): String {
        waitForRateLimit(url)
        val request = NetworkRequests.pageRequest(url)
        return executeWithRetries(url, request)
    }

    suspend fun postForm(url: String, fields: Map<String, Any>): String {
        waitForRateLimit(url)
        val request = NetworkRequests.formRequest(url, fields)
        return executeWithRetries(url, request)
    }

    private suspend fun executeWithRetries(url: String, request: Request): String {
        var attempt = 1
        while (attempt <= 3) {
            val response = client.newCall(request).execute()
            response.use {
                if (it.isSuccessful) return it.body?.string().orEmpty()
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
        val now = System.currentTimeMillis()
        val next = nextAllowedByHost[host] ?: 0L
        if (next > now) delay(next - now)
        nextAllowedByHost[host] = System.currentTimeMillis() + gap
    }

}

object NetworkRequests {
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"
    const val DEFAULT_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
    const val FORM_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded; charset=UTF-8"

    fun pageRequest(url: String): Request = Request.Builder()
        .url(url)
        .header("User-Agent", USER_AGENT)
        .header("Accept", DEFAULT_ACCEPT)
        .header("Accept-Language", "en-US,en;q=0.9")
        .build()

    fun formRequest(url: String, fields: Map<String, Any>): Request {
        val bodyBuilder = FormBody.Builder()
        fields.forEach { (key, value) -> bodyBuilder.add(key, value.toString()) }
        return Request.Builder()
            .url(url)
            .post(bodyBuilder.build())
            .header("User-Agent", USER_AGENT)
            .header("Accept", FORM_ACCEPT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Content-Type", FORM_CONTENT_TYPE)
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
    }
}

interface SourceProvider {
    val name: String
    val baseUrl: String
    fun isSource(url: String): Boolean
    fun getStoryId(url: String): String
    fun getChapterId(url: String): String?
    fun parseMetadata(html: String): NovelMetadata
    suspend fun getChapterList(html: String, url: String, network: NetworkClient, progress: (String) -> Unit = {}): List<ChapterInfo>
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
    override fun getStoryId(url: String) = Regex("fiction/(\\d+)").find(url)?.groupValues?.get(1)?.let { "rr_$it" } ?: "rr_${System.currentTimeMillis()}"
    override fun getChapterId(url: String) = Regex("/chapter/(\\d+)").find(url)?.groupValues?.get(1)

    override fun parseMetadata(html: String): NovelMetadata {
        val doc = Jsoup.parse(html)
        val title = doc.selectFirst("h1")?.text()?.trim().orEmpty().ifBlank { "Unknown Title" }
        val author = doc.selectFirst("h4 a")?.text()?.trim()
            ?: doc.selectFirst("h4")?.text()?.replace("Author:", "")?.trim()
            ?: doc.selectFirst("meta[name=author]")?.attr("content")
            ?: doc.selectFirst("meta[property=article:author]")?.attr("content")
            ?: doc.selectFirst("meta[name=twitter:creator]")?.attr("content")
            ?: "Unknown Author"
        val cover = (doc.selectFirst(".page-content-inner .col-md-3 img")?.absUrl("src") ?: "")
            .ifBlank { doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty() }
            .ifBlank { null }
        val description = doc.selectFirst(".description")?.text()?.trim()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
        val tags = doc.select(".tags .label, .tags a, .tag").map { it.text().trim() }.filter { it.isNotBlank() && !it.equals("tags", true) }.distinct().toMutableList()
        val score = doc.selectFirst(".list-unstyled li.list-item:contains(Overall Score)")?.nextElementSibling()?.selectFirst("span.star")?.attr("data-content")
        val canonical = doc.selectFirst("link[rel=canonical]")?.absUrl("href") ?: doc.selectFirst("meta[property=og:url]")?.attr("content")
        return NovelMetadata(title, author, cover, description, tags.ifEmpty { null }, score, canonical)
    }

    override suspend fun getChapterList(html: String, url: String, network: NetworkClient, progress: (String) -> Unit): List<ChapterInfo> {
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
        val content = doc.selectFirst(".chapter-inner") ?: return "No content found"
        content.select("div.portlet, script, .bold.uppercase.text-center").remove()
        return content.html()
    }
}

object ScribbleHubProvider : SourceProvider {
    override val name = "Scribble Hub"
    override val baseUrl = "https://www.scribblehub.com"
    private const val ajaxUrl = "https://www.scribblehub.com/wp-admin/admin-ajax.php"

    override fun isSource(url: String) = Regex("https?://(?:www\\.)?scribblehub\\.com/(series|read)/", RegexOption.IGNORE_CASE).containsMatchIn(url)
    override fun getStoryId(url: String) = Regex("/series/(\\d+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.let { "sh_$it" } ?: "sh_url_${FullBackupPaths.encodeURIComponent(url.lowercase())}"
    override fun getChapterId(url: String) = Regex("/chapter/(\\d+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.let { "sh_$it" }

    override fun parseMetadata(html: String): NovelMetadata {
        val doc = Jsoup.parse(html, baseUrl)
        val title = firstText(doc, ".fic_title", ".wi_fic_title", "h1").ifBlank { doc.selectFirst("meta[property=og:title]")?.attr("content") ?: "Unknown Title" }
        val author = firstText(doc, ".auth_name_fic a", "a[href*=/profile/]").ifBlank { doc.selectFirst("meta[name=author]")?.attr("content") ?: "Unknown Author" }
        val cover = (doc.selectFirst(".fic_image img")?.absUrl("src") ?: "")
            .ifBlank { doc.selectFirst(".fic_image img")?.absUrl("data-src").orEmpty() }
            .ifBlank { doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty() }
            .ifBlank { null }
        val description = firstText(doc, ".wi_fic_desc", ".fic_synopsis", "#synopsis")
            .ifBlank { doc.selectFirst("meta[property=og:description]")?.attr("content") ?: doc.selectFirst("meta[name=description]")?.attr("content").orEmpty() }
            .ifBlank { null }
        val canonical = doc.selectFirst("link[rel=canonical]")?.absUrl("href") ?: doc.selectFirst("meta[property=og:url]")?.attr("content")
        val tags = doc.select(".wi_fic_genre a, .wi_fic_tags a, .fic_genre a, .fic_tags a, a[href*=/genre/], a[href*=/tag/]").map { it.text().trim() }.filter { it.isNotBlank() }.distinct().toMutableList()
        val score = firstText(doc, ".numscore", "[itemprop=ratingValue]", ".rate_fic_user").ifBlank { null }
        return NovelMetadata(title, author, cover, description, tags.ifEmpty { null }, score, canonical)
    }

    override suspend fun getChapterList(html: String, url: String, network: NetworkClient, progress: (String) -> Unit): List<ChapterInfo> {
        progress("Parsing chapter list...")
        val doc = Jsoup.parse(html, url)
        val postId = doc.selectFirst("#mypostid")?.attr("value")
        val chapters = parseToc(doc, url).toMutableList()
        val seen = chapters.map { it.url }.toMutableSet()
        if (!postId.isNullOrBlank() && chapters.size >= 15) {
            for (page in 2..500) {
                progress("Fetching chapter page $page...")
                val pageHtml = network.postForm(ajaxUrl, mapOf("action" to "wi_getreleases_pagination", "pagenum" to page, "mypostid" to postId)).replace(Regex("0\\s*$"), "").trim()
                val pageChapters = parseToc(Jsoup.parse(pageHtml, url), url)
                if (pageChapters.isEmpty()) break
                val newOnes = pageChapters.filter { seen.add(it.url) }
                if (newOnes.isEmpty()) break
                chapters.addAll(newOnes)
                if (pageChapters.size < 15) break
            }
        }
        return chapters.asReversed()
    }

    override fun parseChapterContent(html: String): String {
        val doc = Jsoup.parse(html)
        val content = doc.selectFirst("#chp_raw") ?: return "No content found"
        content.select("script, style, .wi_authornotes, .sharedaddy, .code-block").remove()
        return content.html().trim()
    }

    private fun parseToc(doc: Document, base: String): List<ChapterInfo> = doc.select(".toc_ol li").mapNotNull { li ->
        val link = li.selectFirst("a[href*=/read/][href*=/chapter/]") ?: return@mapNotNull null
        val href = link.absUrl("href").ifBlank { link.attr("href") }
        ChapterInfo(getChapterId(href), sanitizeTitle(link.text()).ifBlank { "Untitled Chapter" }, href)
    }

    private fun firstText(doc: Document, vararg selectors: String): String {
        return selectors.firstNotNullOfOrNull { selector -> doc.selectFirst(selector)?.text()?.trim()?.takeIf { it.isNotBlank() } }.orEmpty()
    }
}

fun sanitizeTitle(value: String): String {
    if (value.isBlank()) return "Untitled"
    val normalized = value.replace(Regex("[\\r\\n\\t]+"), " ").trim()
    val withoutTrailingOverflow = normalized
        .replace(Regex("(?:\\.{2,}|[\\u2026\\u22EE\\u22EF])\\s*$"), "")
        .trim()
    val withoutTimeAgo = withoutTrailingOverflow
        .replace(
            Regex("\\s*(?:[-–|]\\s*)?\\(?\\s*(?:\\d+|an?)\\s+(?:second|minute|hour|day|week|month|year)s?\\s+ago\\s*\\)?\\s*$", RegexOption.IGNORE_CASE),
            "",
        )
        .trim()
    val withoutDate = withoutTimeAgo
        .replace(
            Regex("\\s*(?:[-–|]\\s*)?\\(?\\s*(?:(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\.?\\s+\\d{1,2}(?:st|nd|rd|th)?,?\\s+\\d{4}|\\d{1,2}\\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\.?\\s+\\d{4})\\s*\\)?\\s*$", RegexOption.IGNORE_CASE),
            "",
        )
        .trim()
    return withoutDate.ifBlank { "Untitled" }
}
