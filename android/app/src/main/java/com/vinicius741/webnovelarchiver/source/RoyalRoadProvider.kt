package com.vinicius741.webnovelarchiver.source

import com.vinicius741.webnovelarchiver.domain.model.ChapterInfo
import com.vinicius741.webnovelarchiver.domain.model.NovelMetadata
import com.vinicius741.webnovelarchiver.domain.model.PublicationStatus
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

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
        val publicationStatus = royalRoadPublicationStatus(doc)
        return NovelMetadata(title, author, cover, description, tags.ifEmpty { null }, score, canonical, patreonUrl, publicationStatus)
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

    private fun royalRoadPublicationStatus(doc: Document): PublicationStatus {
        val labels =
            doc
                .select(".fiction-info .margin-bottom-10 > span.label, .fiction-info .label")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
        return labels.firstNotNullOfOrNull(::publicationStatusFromSourceText)
            ?: PublicationStatus.unknown
    }
}
