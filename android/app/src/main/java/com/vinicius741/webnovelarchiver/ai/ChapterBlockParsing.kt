package com.vinicius741.webnovelarchiver.ai

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.security.MessageDigest

/**
 * One sanitized top-level element. Rewrites address blocks by [id]; protected blocks must return
 * byte-identical (after whitespace normalization) and can never be merged across.
 */
data class ChapterBlock(
    val id: String,
    val tag: String,
    /** Sanitized, self-contained HTML using only the input allowlist, no attributes. */
    val html: String,
    val protected: Boolean,
    /** Classification reason, surfaced in run reports. */
    val reason: String,
    val protectedHash: String? = null,
)

/** A chapter sanitized, split, classified, and id-stamped for the rewrite pipeline. */
data class ParsedChapter(
    val blocks: List<ChapterBlock>,
    /** SHA-256 over the whitespace-normalized sanitized chapter HTML. */
    val sourceSha256: String,
) {
    val addressable: List<ChapterBlock> get() = blocks.filter { !it.protected }

    fun htmlOf(blocks: List<ChapterBlock> = this.blocks): String = blocks.joinToString("\n") { it.html }
}

/**
 * Chapter HTML → ordered [ParsedChapter]: sanitize to the input allowlist, split into top-level
 * blocks (`b0001`…), classify protected blocks, and hash what must be preserved. Stray content
 * stays at its document position (never hoisted to the chapter head).
 */
object ChapterBlockParsing {
    /** Tags kept when sanitizing chapter HTML for the pipeline. Everything else is unwrapped. */
    val BLOCK_TAGS =
        setOf(
            "p",
            "h1",
            "h2",
            "h3",
            "h4",
            "h5",
            "h6",
            "blockquote",
            "ul",
            "ol",
            "li",
            "table",
            "thead",
            "tbody",
            "tr",
            "th",
            "td",
            "hr",
        )

    val INLINE_TAGS = setOf("br", "strong", "b", "em", "i", "u", "sup", "sub")

    /** The prose-only output allowlist a rewrite must obey for addressable blocks. */
    val PROSE_OUTPUT_TAGS = setOf("p", "br", "strong", "em", "blockquote")

    private val VOID_TAGS = setOf("br", "hr")
    private val TOP_LEVEL_START = Regex("<(p|h[1-6]|blockquote|ul|ol|table|hr)\\b[^>]*>", RegexOption.IGNORE_CASE)

    fun sanitizeChapterHtml(html: String): String {
        val body = Jsoup.parse(html).body()
        val out = StringBuilder()
        body.childNodes().forEach { appendSanitized(it, out, BLOCK_TAGS + INLINE_TAGS) }
        return out.toString()
    }

    fun parseChapter(html: String): ParsedChapter {
        val sanitized = sanitizeChapterHtml(html)
        val blocks = mutableListOf<ChapterBlock>()
        var pos = 0
        while (pos < sanitized.length) {
            val match =
                TOP_LEVEL_START.find(sanitized, pos) ?: run {
                    addStrayBlock(blocks, sanitized.substring(pos, sanitized.length))
                    break
                }
            if (match.range.first > pos) addStrayBlock(blocks, sanitized.substring(pos, match.range.first))
            val tag = match.groupValues[1].lowercase()
            if (tag == "hr") {
                blocks.add(makeBlock("hr", "<hr>", blocks.size))
                pos = match.range.last + 1
                continue
            }
            val end = findBalancedEnd(sanitized, match.range.last + 1, tag)
            val inner = sanitized.substring(match.range.last + 1, end)
            blocks.add(makeBlock(tag, "<$tag>$inner</$tag>", blocks.size))
            pos = end + tag.length + 3
        }
        return ParsedChapter(blocks = blocks, sourceSha256 = sourceSha256(sanitized))
    }

    private fun addStrayBlock(
        blocks: MutableList<ChapterBlock>,
        raw: String,
    ) {
        val text = raw.trim()
        if (text.isEmpty()) return
        blocks.add(
            ChapterBlock(
                id = blockId(blocks.size),
                tag = "pre",
                html = text,
                protected = true,
                reason = "loose content outside block tags",
                protectedHash = protectedHash(text),
            ),
        )
    }

    fun blockId(index: Int): String = "b%04d".format(index + 1)

    /** Visible text of an HTML fragment: tags stripped, entities decoded, `<br>` as newline. */
    fun textOf(html: String): String {
        val out = StringBuilder()
        for (token in TOKEN.findAll(html)) {
            val value = token.value
            when {
                !value.startsWith("<") -> out.append(value)
                value.startsWith("</") || value.endsWith("/>") -> Unit
                else -> if (value.startsWith("<br", ignoreCase = true)) out.append('\n')
            }
        }
        return unescape(out.toString())
    }

    /** Canonical form for protected-block byte-for-byte comparison after normalization. */
    fun normalizeForCompare(html: String): String =
        html
            .replace(WHITESPACE, " ")
            .replace(">\\s+<".toRegex(), "><")
            .trim()
            .lowercase()

    fun protectedHash(html: String): String = sha256Hex(normalizeForCompare(html))

    fun sourceSha256(sanitizedHtml: String): String = sha256Hex(WHITESPACE.replace(sanitizedHtml, " ").trim())

    fun assembleChapterHtml(blocks: List<ChapterBlock>): String = blocks.joinToString("\n") { it.html }

    /** Strips non-prose tags from a rewritten block; returns HTML plus notes naming removed hazards. */
    fun sanitizeOutputBlock(html: String): Pair<String, List<String>> {
        val notes = mutableListOf<String>()
        val body = Jsoup.parse(html).body()
        val out = StringBuilder()

        fun serialize(node: Node) {
            when (node) {
                is TextNode -> appendEscaped(node.wholeText, out)
                is Element -> {
                    val tag = node.tagName().lowercase()
                    if (tag == "script" || tag == "style") return
                    if (tag in PROSE_OUTPUT_TAGS) {
                        if (tag in VOID_TAGS || tag == "br") {
                            out.append("<br>")
                        } else {
                            out.append("<$tag>")
                            node.childNodes().forEach(::serialize)
                            out.append("</$tag>")
                        }
                    } else {
                        if (tag in HAZARD_TAGS) notes.add("removed <$tag>")
                        node.childNodes().forEach(::serialize)
                    }
                }
            }
        }
        body.childNodes().forEach(::serialize)
        return out.toString() to notes
    }

    private fun makeBlock(
        tag: String,
        html: String,
        index: Int,
    ): ChapterBlock {
        val text = textOf(html)
        val (protected, reason) = ChapterBlockClassification.classify(tag, html, text)
        return ChapterBlock(
            id = blockId(index),
            tag = tag,
            html = html,
            protected = protected,
            reason = reason,
            protectedHash = if (protected) protectedHash(html) else null,
        )
    }

    private fun findBalancedEnd(
        source: String,
        start: Int,
        tag: String,
    ): Int {
        val open = Regex("<$tag\\b[^>]*>", RegexOption.IGNORE_CASE)
        val close = Regex("</$tag\\s*>", RegexOption.IGNORE_CASE)
        var depth = 1
        var pos = start
        while (pos < source.length) {
            val nextClose = close.find(source, pos) ?: return source.length - tag.length - 3
            val nextOpen = open.find(source, pos)?.takeIf { it.range.first < nextClose.range.first }
            if (nextOpen != null) {
                depth++
                pos = nextOpen.range.last + 1
                continue
            }
            depth--
            if (depth == 0) return nextClose.range.first
            pos = nextClose.range.last + 1
        }
        return source.length - tag.length - 3
    }

    private fun appendSanitized(
        node: Node,
        out: StringBuilder,
        keep: Set<String>,
    ) {
        when (node) {
            is TextNode -> appendEscaped(node.wholeText, out)
            is Element -> {
                val tag = node.tagName().lowercase()
                if (tag == "script" || tag == "style") return
                if (tag in keep) {
                    if (tag == "br" || tag == "hr") {
                        out.append("<$tag>")
                        return
                    }
                    out.append("<$tag>")
                    node.childNodes().forEach { appendSanitized(it, out, keep) }
                    out.append("</$tag>")
                } else {
                    node.childNodes().forEach { appendSanitized(it, out, keep) }
                }
            }
        }
    }

    private fun appendEscaped(
        text: String,
        out: StringBuilder,
    ) {
        for (char in text) {
            when (char) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                else -> out.append(char)
            }
        }
    }

    private fun unescape(value: String): String =
        value
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")

    private fun sha256Hex(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private val TOKEN = Regex("<[^>]+>|[^<]+")
    private val WHITESPACE = Regex("\\s+")
    private val HAZARD_TAGS = setOf("script", "style", "iframe", "object", "embed", "img", "a")
}

/** Conservative classifier: false positives leave awkward prose; false negatives may change game rules. */
object ChapterBlockClassification {
    private val STAT_LINE =
        Regex(
            """^\s*(\[.*\]|[A-Z][A-Za-z /_]{1,24}\s*:|[-\u2013\u2014=*\u2022]{3,}|\d[\d.,/%\s]*(\s*(HP|MP|SP|XP|DMG|STR|INT|VIT|LV|LVL))?\b.*)$""",
        )
    private val NUMERIC_LINE = Regex("""^[\s\d.,:%/()+\-\u2013\u2014xX\u00d7\[\]|#*]+$""")
    private val STRONG_SPAN = Regex("""<(?:strong|b)>(.*?)</(?:strong|b)>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    fun classify(
        tag: String,
        html: String,
        text: String,
    ): Pair<Boolean, String> =
        when {
            tag == "hr" -> true to "divider"
            tag in setOf("table", "ul", "ol") -> true to "interface block ($tag)"
            tag in setOf("h1", "h2", "h3", "h4", "h5", "h6") -> true to "heading"
            tag == "blockquote" -> true to "blockquote (System panel convention)"
            text.isBlank() -> true to "spacer"
            looksLikeSystemText(text) -> true to "stat/system-like text"
            text.lines().size >= 3 && strongShare(html) >= 0.8 -> true to "bold multi-line panel"
            else -> false to "prose"
        }

    internal fun looksLikeSystemText(text: String): Boolean {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return false
        val statish =
            lines.count { line ->
                val value = line.trim()
                NUMERIC_LINE.matches(value) || STAT_LINE.matches(value) || (value.startsWith("[") && value.endsWith("]"))
            }
        return statish.toDouble() / lines.size >= 0.5
    }

    private fun strongShare(html: String): Double {
        val spans = STRONG_SPAN.findAll(html).map { it.groupValues[1] }.toList()
        if (spans.isEmpty()) return 0.0
        val strongChars = spans.sumOf { ChapterBlockParsing.textOf(it).length }
        val total = ChapterBlockParsing.textOf(html).trim().length
        return if (total > 0) strongChars.toDouble() / total else 0.0
    }
}
