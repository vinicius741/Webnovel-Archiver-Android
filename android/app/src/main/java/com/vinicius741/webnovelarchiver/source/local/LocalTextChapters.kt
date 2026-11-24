package com.vinicius741.webnovelarchiver.source.local

import org.jsoup.nodes.Entities
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** One chapter cut out of a plain-text book. [title] is null for text before the first heading. */
data class TextChapter(
    val title: String?,
    val body: String,
    /** Stable key of this chapter inside its book (see [LocalAddress.part]). */
    val key: String,
)

/**
 * Plain-text books (TXT, Markdown, and the text extracted from a PDF): decoding, splitting a whole
 * book into chapters at its headings, and turning a chapter's text into reader HTML.
 */
object LocalTextChapters {
    /** A book with no headings is cut into parts of about this many words, so no chapter is enormous. */
    const val PART_WORDS = 6_000

    /** Below this many words a heading-less book stays one chapter. */
    const val SPLIT_WORDS = 12_000

    /** Text before the first heading shorter than this is a title block, not a chapter of its own. */
    const val PREAMBLE_MIN_CHARS = 200

    private const val MAX_HEADING_CHARS = 80
    private const val MAX_HEADING_TITLE_WORDS = 8
    private const val MAX_KEY_CHARS = 60

    private const val NUMBER_WORD =
        "(?:zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|" +
            "seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand)"
    private const val NUMBER =
        "(?:\\s*\\d+(?:\\.\\d+)?|\\s+(?-i:[IVXLCDM]+)|\\s+$NUMBER_WORD(?:[ -]+(?:and[ -]+)?$NUMBER_WORD)*)"

    private val numberedHeading =
        Regex("^(?:chapter|chap\\.?|ch\\.|part|book|volume|vol\\.|arc|episode)$NUMBER\\b(.*)$", RegexOption.IGNORE_CASE)
    private val namedHeading =
        Regex(
            "^(?:prologue|epilogue|interlude|intermission|afterword|foreword|preface|introduction|side story|extra chapter|bonus chapter)\\b(.*)$",
            RegexOption.IGNORE_CASE,
        )
    private val cjkHeading = Regex("^第\\s*[0-9零〇一二三四五六七八九十百千两]+\\s*[章节回話话卷部](.*)$")
    private val markdownHeading = Regex("^#{1,3}\\s+(\\S.*)$")
    private val volumeHeading = Regex("^(?:part|book|volume|vol\\.|arc)\\b", RegexOption.IGNORE_CASE)

    /** A contents line: "Chapter 1 ........ 5". */
    private val contentsLine = Regex("(?:\\.{3,}|…|\\s{3,}|\\t)\\s*\\d+$")
    private val smallWords = setOf("a", "an", "and", "as", "at", "by", "for", "in", "of", "on", "or", "the", "to", "with")

    /** Decodes a text file: a BOM wins, then strict UTF-8, then Windows-1252 (old TXT files). */
    fun decode(bytes: ByteArray): String {
        val (charset, offset) =
            when {
                bytes.startsWith(0xEF, 0xBB, 0xBF) -> Charsets.UTF_8 to 3
                bytes.startsWith(0xFF, 0xFE) -> Charsets.UTF_16LE to 2
                bytes.startsWith(0xFE, 0xFF) -> Charsets.UTF_16BE to 2
                else -> null to 0
            }
        if (charset != null) return String(bytes, offset, bytes.size - offset, charset)
        return try {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
    }

    /** The chapter title a heading line names, or null when [line] is not a heading. */
    fun headingTitle(
        line: String,
        markdown: Boolean = false,
    ): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_HEADING_CHARS) return null
        if (markdown) markdownHeading.matchEntire(trimmed)?.let { return cleanTitle(it.groupValues[1]) }
        if (contentsLine.containsMatchIn(trimmed)) return null
        cjkHeading.matchEntire(trimmed)?.let { return cleanTitle(trimmed) }
        // A heading starts with a capital: a hard-wrapped prose line ("part I could not...") does not.
        if (!trimmed.first().isUpperCase()) return null
        val match = numberedHeading.matchEntire(trimmed) ?: namedHeading.matchEntire(trimmed) ?: return null
        val rest = match.groupValues.last().trim()
        return cleanTitle(trimmed).takeIf { rest.isEmpty() || rest.first() in ":.-–—|)]" || looksLikeTitle(rest) }
    }

    /** "The Fall" is a title; "of the manual says you should" is the rest of a sentence. */
    private fun looksLikeTitle(rest: String): Boolean {
        val words = rest.split(Regex("\\s+")).filter { word -> word.any(Char::isLetter) }
        if (words.size > MAX_HEADING_TITLE_WORDS || rest.last() in ".,;") return false
        val significant = words.filterNot { it.lowercase() in smallWords }
        return significant.isEmpty() || significant.count { it.first { c -> c.isLetter() }.isUpperCase() } * 2 >= significant.size
    }

    /**
     * Splits a whole book at its chapter headings. With fewer than two headings the book is one
     * chapter (titled by a heading on its first line), or several numbered parts when it is long.
     */
    fun split(
        text: String,
        markdown: Boolean = false,
    ): List<TextChapter> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').lines()
        // A PDF's running header repeats the chapter heading on every page: only its first line opens a chapter.
        val headings =
            lines.indices
                .mapNotNull { index -> headingTitle(lines[index], markdown)?.let { index to it } }
                .fold(mutableListOf<Pair<Int, String>>()) { kept, heading ->
                    kept.apply { if (lastOrNull()?.second?.equals(heading.second, ignoreCase = true) != true) add(heading) }
                }
        if (headings.size < 2) return single(lines, headings.firstOrNull())
        val chapters = mutableListOf<TextChapter>()
        val keys = ChapterKeys()
        val preamble = lines.subList(0, headings.first().first).joinToString("\n").trim()
        if (preamble.length >= PREAMBLE_MIN_CHARS) chapters += TextChapter(null, preamble, keys.next("opening"))
        var pendingPrefix: String? = null
        headings.forEachIndexed { position, (lineIndex, title) ->
            val end = headings.getOrNull(position + 1)?.first ?: lines.size
            val body =
                lines
                    .subList(lineIndex + 1, end)
                    .filterNot { headingTitle(it, markdown)?.equals(title, ignoreCase = true) == true }
                    .joinToString("\n")
                    .trim()
            val fullTitle = pendingPrefix?.let { "$it: $title" } ?: title
            when {
                // A "Book Two" line straight before "Chapter 1" names the volume, not a chapter.
                body.isEmpty() && volumeHeading.containsMatchIn(title) -> pendingPrefix = fullTitle
                // A heading with nothing under it is a contents page line.
                body.isEmpty() -> Unit
                else -> {
                    pendingPrefix = null
                    chapters += TextChapter(fullTitle, body, keys.next(fullTitle))
                }
            }
        }
        return chapters
    }

    private fun single(
        lines: List<String>,
        heading: Pair<Int, String>?,
    ): List<TextChapter> {
        val firstLine = lines.indexOfFirst { it.isNotBlank() }
        val titled = heading != null && heading.first == firstLine
        val body = (if (titled) lines.drop(firstLine + 1) else lines).joinToString("\n").trim()
        val title = heading?.second?.takeIf { titled }
        if (body.isEmpty()) return emptyList()
        val paragraphs = paragraphs(body)
        if (wordCount(body) < SPLIT_WORDS) return listOf(TextChapter(title, body, "all"))
        val parts = mutableListOf<MutableList<String>>(mutableListOf())
        var words = 0
        paragraphs.forEach { paragraph ->
            if (words >= PART_WORDS) {
                parts += mutableListOf<String>()
                words = 0
            }
            parts.last() += paragraph
            words += wordCount(paragraph)
        }
        return parts.mapIndexed { index, part -> TextChapter("Part ${index + 1}", part.joinToString("\n\n"), "p${index + 1}") }
    }

    /**
     * Paragraphs of a text: blank-line separated blocks with their hard line wraps joined, or
     * one paragraph per line when the text has no blank lines at all.
     */
    fun paragraphs(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (normalized.isEmpty()) return emptyList()
        return if (Regex("\\n[ \\t]*\\n").containsMatchIn(normalized)) {
            normalized.split(Regex("\\n[ \\t]*\\n+")).map { block -> block.lines().joinToString(" ") { it.trim() }.trim() }
        } else {
            normalized.lines().map(String::trim)
        }.filter(String::isNotEmpty)
    }

    /** Reader HTML for a chapter's text: one `<p>` per paragraph, Markdown headings and emphasis kept. */
    fun toHtml(
        text: String,
        markdown: Boolean = false,
    ): String =
        paragraphs(text).joinToString("\n") { paragraph ->
            val heading = if (markdown) markdownHeading.matchEntire(paragraph) else null
            if (heading != null) {
                "<h3>${inline(heading.groupValues[1], markdown)}</h3>"
            } else {
                "<p>${inline(paragraph, markdown)}</p>"
            }
        }

    /** A title from a file name: no extension, underscores as spaces. */
    fun titleFromFileName(name: String): String = cleanTitle(name.substringBeforeLast('.').replace('_', ' ')).ifBlank { name }

    fun wordCount(text: String): Int = text.split(Regex("\\s+")).count(String::isNotEmpty)

    private fun inline(
        text: String,
        markdown: Boolean,
    ): String {
        val escaped = Entities.escape(text)
        if (!markdown) return escaped
        return escaped
            .replace(Regex("\\*\\*(\\S(?:.*?\\S)?)\\*\\*"), "<strong>$1</strong>")
            .replace(Regex("(?<![\\w*])\\*(\\S(?:.*?\\S)?)\\*(?![\\w*])"), "<em>$1</em>")
            .replace(Regex("(?<!\\w)_(\\S(?:.*?\\S)?)_(?!\\w)"), "<em>$1</em>")
    }

    private fun cleanTitle(value: String): String =
        value
            .replace(Regex("^#+\\s*"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * Chapter keys from titles, so a chapter keeps its id when one is added before it ("Interlude"
     * between chapters 4 and 5 does not move chapter 5). A repeated title gets a counter.
     */
    private class ChapterKeys {
        private val used = mutableMapOf<String, Int>()

        fun next(title: String): String {
            val slug =
                title
                    .lowercase()
                    .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
                    .trim('-')
                    .take(MAX_KEY_CHARS)
                    .ifEmpty { "untitled" }
            val count = (used[slug] ?: 0) + 1
            used[slug] = count
            return if (count == 1) slug else "$slug~$count"
        }
    }

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it].toByte() }
}
