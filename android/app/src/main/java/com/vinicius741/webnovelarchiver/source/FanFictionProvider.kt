package com.vinicius741.webnovelarchiver.source

import com.vinicius741.webnovelarchiver.domain.model.ChapterInfo
import com.vinicius741.webnovelarchiver.domain.model.NovelMetadata
import com.vinicius741.webnovelarchiver.domain.model.PublicationStatus
import com.vinicius741.webnovelarchiver.domain.model.SourceMetadata
import com.vinicius741.webnovelarchiver.domain.model.SourceMetric
import com.vinicius741.webnovelarchiver.domain.model.SourceMetricKind
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
    override val descriptor =
        SourceDescriptor(
            id = "fanfiction_net",
            displayName = "FanFiction.net",
            browseUrl = "https://www.fanfiction.net",
            hosts = setOf("fanfiction.net", "m.fanfiction.net"),
            capabilities = SourceCapabilities(maximumDownloadConcurrency = 1),
            userAgentMode = SourceUserAgentMode.DESKTOP,
            featuredMetrics =
                listOf(
                    SourceMetricKind.FAVORITES,
                    SourceMetricKind.FOLLOWS,
                    SourceMetricKind.REVIEWS,
                    SourceMetricKind.WORDS,
                ),
        )

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
            ?: error("FanFiction.net story URL was not recognized")

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
        val facets = fanFictionFacets(profileText)
        val fandoms = fanFictionFandoms(doc)
        val tags = fanFictionTags(doc, profileText).toMutableList().takeIf { it.isNotEmpty() }
        val status = sourceField(profileText, "Status")
        val sourceMetadata =
            SourceMetadata(
                metrics = fanFictionMetrics(profileText),
                publishedAt = profile?.sourceTimestamp("Published"),
                updatedAt = profile?.sourceTimestamp("Updated"),
                contentRating = facets.contentRating,
                sourceStatus = status,
                language = facets.language,
                genres = facets.genres.toMutableList(),
                fandoms = fandoms.toMutableList(),
                characters = facets.characters.toMutableList(),
            )
        return NovelMetadata(
            title = title,
            author = author,
            coverUrl = cover,
            description = description,
            tags = tags,
            canonicalUrl = canonical,
            publicationStatus = publicationStatusFromSourceText(status) ?: PublicationStatus.unknown,
            sourceMetadata = sourceMetadata,
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
        val publishedAt = profile?.sourceTimestamp("Published")
        val updatedAt = profile?.sourceTimestamp("Updated")
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

    private fun Element.sourceTimestamp(label: String): Long? {
        val fieldValue = sourceField(text(), label) ?: return null
        val timestamp =
            select("span[data-xutime]")
                .firstOrNull { candidate ->
                    normalizedSourceText(fieldValue).contains(normalizedSourceText(candidate.text()))
                }
        val sourceTimestamp = timestamp?.attr("data-xutime")?.toLongOrNull()?.times(1_000L)
        return sourceTimestamp ?: parseSourceDateMillis(fieldValue)
    }

    private fun fanFictionMetrics(profileText: String): MutableList<SourceMetric> =
        buildList {
            addMetric(profileText, "Words", SourceMetricKind.WORDS)
            addMetric(profileText, "Reviews", SourceMetricKind.REVIEWS)
            addMetric(profileText, "Favs", SourceMetricKind.FAVORITES)
            addMetric(profileText, "Follows", SourceMetricKind.FOLLOWS)
        }.toMutableList()

    private fun MutableList<SourceMetric>.addMetric(
        profileText: String,
        label: String,
        kind: SourceMetricKind,
    ) {
        parseSourceMetricValue(sourceField(profileText, label))?.let { value ->
            add(SourceMetric(kind, value))
        }
    }

    private fun fanFictionFandoms(doc: Document): List<String> =
        doc
            .select(FANDOM_LINKS)
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

    private fun fanFictionTags(
        doc: Document,
        profileText: String,
    ): List<String> {
        val parts = fanFictionProfileParts(profileText)
        val details =
            parts
                .drop(2)
                .take(2)
                .joinToString(" - ")
                .split(Regex("""\s+-\s+|\s*,\s*"""))
                .map(String::trim)
                .filter(String::isNotBlank)
        return (fanFictionFandoms(doc) + details).distinct()
    }

    private fun fanFictionFacets(profileText: String): FanFictionFacets {
        val parts = fanFictionProfileParts(profileText)
        if (parts.size < 2) return FanFictionFacets()
        return FanFictionFacets(
            contentRating = parts[0].takeIf(String::isNotBlank),
            language = parts[1].takeIf(String::isNotBlank),
            genres = parts.getOrNull(2)?.let(::splitProfileGenres).orEmpty(),
            characters = parts.getOrNull(3)?.let(::splitProfileCharacters).orEmpty(),
        )
    }

    private fun fanFictionProfileParts(profileText: String): List<String> {
        val normalizedProfile = normalizedSourceText(profileText)
        val marker = "Rated: Fiction"
        val markerStart = normalizedProfile.indexOf(marker, ignoreCase = true)
        if (markerStart < 0) return emptyList()
        val detailsStart = markerStart + marker.length
        val chaptersStart = normalizedProfile.indexOf(" - Chapters:", detailsStart, ignoreCase = true)
        if (chaptersStart < 0) return emptyList()
        return normalizedProfile
            .substring(detailsStart, chaptersStart)
            .split(Regex("""\s+-\s+"""), limit = 4)
            .map(String::trim)
    }

    private fun splitProfileGenres(value: String): List<String> =
        value
            .split(Regex("""\s*/\s*"""))
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()

    private fun splitProfileCharacters(value: String): List<String> =
        value
            .split(Regex("""\s*,\s*"""))
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()

    private fun sourceField(
        profileText: String,
        label: String,
    ): String? =
        Regex(
            """(?i)(?:^|\s-?\s)${Regex.escape(label)}\s*:\s*""" +
                """(.*?)(?=\s+-\s+(?:Chapters|Words|Reviews|Favs|Follows|Updated|Published|Status|id)\s*:|$)""",
        ).find(normalizedSourceText(profileText))
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeIf(String::isNotBlank)

    private data class FanFictionFacets(
        val contentRating: String? = null,
        val language: String? = null,
        val genres: List<String> = emptyList(),
        val characters: List<String> = emptyList(),
    )

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
