package com.vinicius741.webnovelarchiver.source

import com.vinicius741.webnovelarchiver.domain.model.ChapterInfo
import com.vinicius741.webnovelarchiver.domain.model.NovelMetadata
import com.vinicius741.webnovelarchiver.domain.model.PublicationStatus
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import com.vinicius741.webnovelarchiver.source.network.NetworkParseException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * FanFiction.net renders story metadata, the complete chapter selector, and the selected chapter on
 * every `/s/{storyId}/{chapterNumber}/{slug}` page. That makes an arbitrary chapter URL importable:
 * metadata is normalized to chapter 1 while downloads retain one URL per chapter.
 */
@Suppress("TooManyFunctions")
object FanFictionProvider : SourceProvider {
    override val name = "FanFiction.net"
    override val baseUrl = "https://www.fanfiction.net"
    override val maximumDownloadConcurrency = 1

    override fun isSource(url: String): Boolean = STORY_URL.matches(url.trim())

    override fun classifyUrl(url: String): SourceUrlKind? = if (STORY_URL.matches(url.trim())) SourceUrlKind.STORY else null

    override fun normalizeStoryUrl(url: String): String {
        val match = storyUrlMatch(url) ?: return url.trim()
        return "$baseUrl/s/${match.groupValues[1]}/${match.groupValues[2]}${match.groupValues[3]}"
    }

    override fun getStoryId(url: String): String =
        storyUrlMatch(url)
            ?.groupValues
            ?.get(1)
            ?.let { "ffn_$it" }
            ?: "ffn_${System.currentTimeMillis()}"

    override fun getChapterId(url: String): String? =
        storyUrlMatch(url)?.let { match ->
            "ffn_${match.groupValues[1]}_${match.groupValues[2]}"
        }

    override fun parseMetadata(html: String): NovelMetadata {
        val doc = Jsoup.parse(html, baseUrl)
        val profile = doc.selectFirst("#profile_top")
        val title =
            profile
                ?.selectFirst("b.xcontrast_txt, b")
                ?.text()
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: doc
                    .title()
                    .substringBefore(" Chapter ")
                    .trim()
                    .takeIf(String::isNotBlank)
                ?: "Unknown Title"
        val author =
            profile
                ?.selectFirst("a[href^=/u/]")
                ?.text()
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: "Unknown Author"
        val cover =
            profile
                ?.selectFirst("img.cimage, img[src^=/image/]")
                ?.absUrl("src")
                ?.takeIf(String::isNotBlank)
        val description =
            profile
                ?.children()
                ?.firstOrNull { it.tagName() == "div" && it.hasClass("xcontrast_txt") }
                ?.blockText()
                ?.takeIf(String::isNotBlank)
        val canonical = canonicalStoryUrl(doc)
        val profileText = profile?.text().orEmpty()
        val tags = fanFictionTags(doc, profileText).toMutableList().takeIf { it.isNotEmpty() }
        val publicationStatus =
            publicationStatusFromSourceText(profileText)
                ?: if (profileText.contains("Published:", ignoreCase = true)) {
                    PublicationStatus.ongoing
                } else {
                    PublicationStatus.unknown
                }
        return NovelMetadata(
            title = title,
            author = author,
            coverUrl = cover,
            description = description,
            tags = tags,
            canonicalUrl = canonical,
            publicationStatus = publicationStatus,
        )
    }

    override suspend fun getChapterList(
        html: String,
        url: String,
        network: NetworkClient,
        progress: (String) -> Unit,
    ): List<ChapterInfo> {
        progress("Parsing chapter list...")
        val doc = Jsoup.parse(html, url)
        val sourceUrl =
            doc.selectFirst("link[rel=canonical]")?.absUrl("href")?.ifBlank { null }
                ?: url
        val match =
            storyUrlMatch(sourceUrl)
                ?: throw NetworkParseException("FanFiction.net story URL was not recognized")
        val storyId = match.groupValues[1]
        val slug = match.groupValues[3]
        val options =
            doc
                .selectFirst("select#chap_select, select[name=chapter]")
                ?.select("option[value]")
                .orEmpty()
        if (options.isEmpty()) {
            throw NetworkParseException("FanFiction.net chapter list was not found")
        }

        val profile = doc.selectFirst("#profile_top")
        val publishedAt = profile?.sourceTimestamps()?.lastOrNull()
        val updatedAt =
            profile
                ?.takeIf { it.text().contains("Updated:", ignoreCase = true) }
                ?.sourceTimestamps()
                ?.firstOrNull()
        val lastChapterNumber = options.mapNotNull { it.attr("value").toIntOrNull() }.maxOrNull()
        val chapters =
            options.mapNotNull { option ->
                val chapterNumber = option.attr("value").toIntOrNull() ?: return@mapNotNull null
                val title =
                    option
                        .text()
                        .replace(Regex("""^\s*\d+\.\s*"""), "")
                        .trim()
                        .ifBlank { "Chapter $chapterNumber" }
                ChapterInfo(
                    id = "ffn_${storyId}_$chapterNumber",
                    title = sanitizeTitle(title),
                    url = "$baseUrl/s/$storyId/$chapterNumber$slug",
                    chapterNumber = chapterNumber,
                    publishedAt =
                        when (chapterNumber) {
                            1 -> publishedAt
                            lastChapterNumber -> updatedAt
                            else -> null
                        },
                )
            }
        if (chapters.isEmpty()) {
            throw NetworkParseException("FanFiction.net chapter list contained no valid chapters")
        }
        progress("${chapterCountLabel(chapters.size)} found")
        return chapters
    }

    override fun parseChapterContent(html: String): String {
        val document = Jsoup.parse(html, baseUrl)
        val content =
            document
                .selectFirst("#storytext, #storytextp, #storycontent")
        val resolvedContent =
            content
                ?: throw NetworkParseException("FanFiction.net chapter content was not found")
        resolvedContent.select("script, noscript").remove()
        return resolvedContent.html()
    }

    private fun canonicalStoryUrl(doc: Document): String? {
        val raw =
            doc.selectFirst("link[rel=canonical]")?.absUrl("href")?.ifBlank { null }
                ?: return null
        val match = storyUrlMatch(raw) ?: return raw
        return "$baseUrl/s/${match.groupValues[1]}/1${match.groupValues[3]}"
    }

    private fun Element.sourceTimestamps(): List<Long> =
        select("span[data-xutime]")
            .mapNotNull { element ->
                element.attr("data-xutime").toLongOrNull()?.times(1_000L)
            }

    private fun fanFictionTags(
        doc: Document,
        profileText: String,
    ): List<String> {
        val fandoms =
            doc
                .select(FANDOM_LINKS)
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
        val details =
            Regex(
                """Rated:.*?\s-\s[^-]+\s-\s(.*?)\s-\sChapters:""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            ).find(profileText)
                ?.groupValues
                ?.get(1)
                ?.split(Regex("""\s+-\s+|\s*,\s*"""))
                .orEmpty()
                .map(String::trim)
                .filter(String::isNotBlank)
        return (fandoms + details).distinct()
    }

    private fun storyUrlMatch(url: String) = STORY_URL.matchEntire(url.trim())

    private fun chapterCountLabel(count: Int): String = "$count chapter${if (count == 1) "" else "s"}"

    private val STORY_URL =
        Regex(
            """^https?://(?:www\.|m\.)?fanfiction\.net/s/(\d+)/(\d+)((?:/[^/?#]+)?/?)(?:[?#].*)?$""",
            RegexOption.IGNORE_CASE,
        )

    private const val FANDOM_LINKS =
        "a[href^=/anime/]:not([href='/anime/']), " +
            "a[href^=/book/]:not([href='/book/']), " +
            "a[href^=/cartoon/]:not([href='/cartoon/']), " +
            "a[href^=/comic/]:not([href='/comic/']), " +
            "a[href^=/game/]:not([href='/game/']), " +
            "a[href^=/misc/]:not([href='/misc/']), " +
            "a[href^=/movie/]:not([href='/movie/']), " +
            "a[href^=/play/]:not([href='/play/']), " +
            "a[href^=/tv/]:not([href='/tv/'])"
}
