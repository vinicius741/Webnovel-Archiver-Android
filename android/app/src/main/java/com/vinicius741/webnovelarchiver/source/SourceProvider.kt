package com.vinicius741.webnovelarchiver.source

import com.vinicius741.webnovelarchiver.domain.model.ChapterInfo
import com.vinicius741.webnovelarchiver.domain.model.NovelMetadata
import com.vinicius741.webnovelarchiver.domain.model.PublicationStatus
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.util.Locale

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
    return candidates.firstNotNullOfOrNull { parseChapterPublishedAt(it, now) }
}

private fun parseChapterPublishedAt(
    value: String,
    now: Long,
): Long? {
    val normalized =
        value
            .replace(Regex("""\b(\d{1,2})(st|nd|rd|th)\b""", RegexOption.IGNORE_CASE), "$1")
            .replace(Regex("""^\s*[A-Za-z]+,\s+"""), "")
            .trim()
    if (normalized.isBlank()) return null

    parseEpochMillis(normalized)?.let { return it }
    parseRelativeTime(normalized, now)?.let { return it }
    parseInstantMillis(normalized)?.let { return it }
    parseLocalDateTimeMillis(normalized)?.let { return it }
    parseLocalDateMillis(normalized)?.let { return it }
    return parseEmbeddedDateMillis(normalized, now)
}

private fun parseEpochMillis(value: String): Long? {
    val numeric = Regex("""^\d{10,13}$""").find(value)?.value ?: return null
    val raw = numeric.toLongOrNull() ?: return null
    return if (numeric.length <= 10) raw * 1000L else raw
}

private fun parseRelativeTime(
    value: String,
    now: Long,
): Long? {
    val match =
        Regex("""(?i)\b(an?|one|\d+)\s+(second|minute|hour|day|week|month|year)s?\s+ago\b""")
            .find(value)
            ?: return null
    val amount =
        when (val raw = match.groupValues[1].lowercase()) {
            "a", "an", "one" -> 1L
            else -> raw.toLongOrNull() ?: return null
        }
    val unit = match.groupValues[2].lowercase()
    val millis =
        when (unit) {
            "second" -> amount * 1000L
            "minute" -> amount * 60L * 1000L
            "hour" -> amount * 60L * 60L * 1000L
            "day" -> amount * 24L * 60L * 60L * 1000L
            "week" -> amount * 7L * 24L * 60L * 60L * 1000L
            "month" -> amount * 30L * 24L * 60L * 60L * 1000L
            "year" -> amount * 365L * 24L * 60L * 60L * 1000L
            else -> return null
        }
    return now - millis
}

private fun parseInstantMillis(value: String): Long? {
    listOf<(String) -> Long?>(
        { Instant.parse(it).toEpochMilli() },
        { OffsetDateTime.parse(it).toInstant().toEpochMilli() },
        { ZonedDateTime.parse(it).toInstant().toEpochMilli() },
        { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() },
    ).forEach { parser ->
        try {
            return parser(value)
        } catch (_: DateTimeParseException) {
        }
    }
    return null
}

private fun parseLocalDateTimeMillis(value: String): Long? =
    localDateTimeFormatters.firstNotNullOfOrNull { formatter ->
        try {
            LocalDateTime
                .parse(value, formatter)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }

private fun parseLocalDateMillis(value: String): Long? =
    localDateFormatters.firstNotNullOfOrNull { formatter ->
        try {
            LocalDate
                .parse(value, formatter)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }

private fun parseEmbeddedDateMillis(
    value: String,
    now: Long,
): Long? {
    val patterns =
        listOf(
            Regex(
                """(?i)\b(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\.?\s+\d{1,2}(?:st|nd|rd|th)?,?\s+\d{4}\b""",
            ),
            Regex(
                """(?i)\b\d{1,2}(?:st|nd|rd|th)?\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\.?\s+\d{4}\b""",
            ),
        )
    return patterns.firstNotNullOfOrNull { pattern ->
        pattern
            .find(value)
            ?.value
            ?.takeIf { it != value }
            ?.let { parseChapterPublishedAt(it, now) }
    }
}

private val localDateTimeFormatters =
    listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "MMM d, yyyy h:mm a",
        "MMMM d, yyyy h:mm a",
        "MMM d yyyy h:mm a",
        "MMMM d yyyy h:mm a",
    ).map(::sourceDateFormatter)

private val localDateFormatters =
    listOf(
        "yyyy-MM-dd",
        "MMM d, yyyy",
        "MMMM d, yyyy",
        "MMM d yyyy",
        "MMMM d yyyy",
        "d MMM yyyy",
        "d MMMM yyyy",
    ).map(::sourceDateFormatter)

private fun sourceDateFormatter(pattern: String): DateTimeFormatter =
    DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendPattern(pattern)
        .toFormatter(Locale.US)

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
