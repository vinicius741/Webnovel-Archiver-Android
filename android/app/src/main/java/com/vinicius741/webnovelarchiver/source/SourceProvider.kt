package com.vinicius741.webnovelarchiver.source

import com.vinicius741.webnovelarchiver.domain.model.ChapterInfo
import com.vinicius741.webnovelarchiver.domain.model.NovelMetadata
import com.vinicius741.webnovelarchiver.domain.model.PublicationStatus
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

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

// ──────────────────────────────────────────────────────────────────────────────
// Shared source-parsing helpers (Maintainability M1: split out of Sources.kt). These are kept
// package-internal so the per-provider files in this package can use them without exposing them
// as a public API. Previously file-private inside Sources.kt.
// ──────────────────────────────────────────────────────────────────────────────

internal val descriptionBlockTags =
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
        .replace(Regex("\\u00A0"), " ") // non-breaking space → normal space
        .replace(Regex("[ \\t]+"), " ") // collapse intra-line whitespace
        .replace(Regex("\\n[ \\t]+"), "\n") // trim leading spaces on each line
        .replace(Regex("[ \\t]+\\n"), "\n") // trim trailing spaces on each line
        .replace(Regex("\\n{3,}"), "\n\n") // collapse blank-line runs
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
        Regex("""\b(ongoing|active|hiatus|hiatused|dropped|cancelled|canceled)\b""").containsMatchIn(normalized) ->
            PublicationStatus.ongoing
        else -> null
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
