package com.vinicius741.webnovelarchiver.source

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.ChapterInfo
import com.vinicius741.webnovelarchiver.domain.model.NovelMetadata
import com.vinicius741.webnovelarchiver.domain.model.PublicationStatus
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import com.vinicius741.webnovelarchiver.source.network.NetworkRequestGate
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

interface SourceProvider {
    val descriptor: SourceDescriptor
        get() =
            SourceDescriptor(
                id = "test_source",
                displayName = "Test Source",
                browseUrl = "",
                hosts = emptySet(),
            )

    val id: String get() = descriptor.id
    val name: String get() = descriptor.displayName
    val baseUrl: String get() = descriptor.browseUrl
    val supportsLatestChapterSync: Boolean get() = descriptor.capabilities.latestChapterSync
    val maximumDownloadConcurrency: Int? get() = descriptor.capabilities.maximumDownloadConcurrency

    fun classifyUrl(url: String): SourceUrlKind? = null

    /** Canonicalizes equivalent source URL variants to the one form used for fetch + persistence. */
    fun normalizeStoryUrl(url: String): String = url.trim()

    fun getStoryId(url: String): String

    fun getChapterId(url: String): String?

    fun parseMetadata(html: String): NovelMetadata

    /** Optional supplemental metadata fetch (e.g. Scribble Hub's `/stats/` page), run before
     *  chapter retrieval so a failure can't block the import. */
    suspend fun enrichMetadata(
        metadata: NovelMetadata,
        html: String,
        url: String,
        network: NetworkClient,
        progress: (String) -> Unit = {},
    ): NovelMetadata = metadata

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

    /** Request orchestration; API/auth/JS-backed sources override the HTML pipeline as one unit. */
    suspend fun loadStory(
        url: String,
        preferLatestChapters: Boolean,
        network: NetworkClient,
        progress: (String) -> Unit = {},
    ): LoadedSourceStory = loadHtmlStory(this, url, preferLatestChapters, network, progress)

    /** Fetches one chapter for the queue; forum sources may override to reuse a multi-chapter page. */
    suspend fun fetchChapterContent(
        storyUrl: String,
        chapter: Chapter,
        chapterIndex: Int,
        network: NetworkClient,
        requestGate: NetworkRequestGate? = null,
    ): String =
        parseChapterContent(
            network.fetch(
                url = chapter.url,
                maximumAttemptsOverride = 1,
                requestGate = requestGate,
            ),
        )
}

internal val descriptionBlockTags =
    setOf("p", "div", "li", "blockquote", "h1", "h2", "h3", "h4", "h5", "h6", "hr")

/**
 * Visible text preserving paragraph layout (`\n\n` around block elements, `\n` for `<br>`),
 * unlike Jsoup's [Element.text] which flattens blocks into one line. Consumers (Details, EPUB
 * details) split on `\n+`.
 */
internal fun Element.blockText(): String {
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
        .replace(Regex("\\u00A0"), " ") // NBSP → space
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n[ \\t]+"), "\n")
        .replace(Regex("[ \\t]+\\n"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}

internal fun findPatreonUrl(doc: Document): String? =
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

private fun patreonLinkPriority(link: Element): Int {
    val context = (link.text() + " " + link.parents().take(3).joinToString(" ") { it.className() }).lowercase()
    return when {
        "author" in context -> 3
        "support" in context || "patreon" in link.text().lowercase() -> 2
        "description" in context || "fiction" in context || "profile" in context -> 1
        else -> 0
    }
}

internal fun publicationStatusFromSourceText(text: String?): PublicationStatus? {
    if (text.isNullOrBlank()) return null
    val normalized = text.lowercase()
    return when {
        Regex("""\b(completed|complete)\b""").containsMatchIn(normalized) -> PublicationStatus.completed
        Regex("""\b(hiatus|hiatused|dropped|cancelled|canceled)\b""").containsMatchIn(normalized) -> PublicationStatus.hiatus
        Regex("""\b(ongoing|active)\b""").containsMatchIn(normalized) -> PublicationStatus.ongoing
        else -> null
    }
}

internal fun Element.chapterPublishedAt(now: Long = System.currentTimeMillis()): Long? {
    val candidates = mutableListOf<String>()
    val time = if (tagName().equals("time", ignoreCase = true)) this else selectFirst("time")
    time?.let { node ->
        listOf("unixtime", "data-time", "data-timestamp", "datetime", "title").forEach { attr ->
            node.attr(attr).takeIf { it.isNotBlank() }?.let(candidates::add)
        }
        node.text().takeIf { it.isNotBlank() }?.let(candidates::add)
    }
    listOf("data-time", "data-timestamp", "title").forEach { attr ->
        attr(attr).takeIf { it.isNotBlank() }?.let(candidates::add)
    }
    select("*")
        .asSequence()
        .filterNot { it.tagName().equals("a", ignoreCase = true) || it.tagName().equals("time", ignoreCase = true) }
        .map { it.ownText().trim() }
        .filter { it.isNotBlank() }
        .forEach(candidates::add)
    text().takeIf { it.isNotBlank() }?.let(candidates::add)
    return candidates.firstNotNullOfOrNull { parseSourceDateMillis(it, now) }
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
