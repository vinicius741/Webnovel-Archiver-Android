package com.vinicius741.webnovelarchiver.cleanup

import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * HTML-to-text transformation consumed by the download engine and the reader (Maintainability M1:
 * split out of TextCleanup.kt). Owns sentence/regex stripping for downloaded chapter HTML plus the
 * plain-text and structured (paragraph/heading/table-preserving) flattening used by the reader.
 * [TextCleanup] re-exposes these for callers that still go through the stateless entry points.
 */
object HtmlCleanup {
    fun applyDownloadCleanup(
        html: String,
        sentences: List<String>,
        rules: List<RegexCleanupRule>,
    ): String {
        val doc = Jsoup.parseBodyFragment(html)
        val sentencePatterns =
            sentences.mapNotNull { sentence ->
                val escaped = Regex.escape(sentence.trim()).replace("\\ ", "\\s+")
                if (escaped.isBlank()) null else runCatching { Regex(escaped, setOf(RegexOption.IGNORE_CASE)) }.getOrNull()
            }
        val regexRules =
            rules.filter { it.enabled && (it.appliesTo == "both" || it.appliesTo == "download") }.mapNotNull {
                runCatching {
                    Regex(it.pattern, TextCleanup.regexOptions(it.flags))
                }.getOrNull()
            }
        doc.select("script,style,noscript,iframe").remove()
        doc.body().traverseTextNodes().forEach { node ->
            var text = node.text()
            sentencePatterns.forEach { text = it.replace(text, "") }
            regexRules.forEach { text = it.replace(text, "") }
            node.text(text)
        }
        return doc.body().html()
    }

    fun htmlToPlainText(html: String): String {
        val doc = Jsoup.parseBodyFragment(html)
        doc.select("script,style,noscript,iframe").remove()
        doc.select("p,div,br,h1,h2,h3,h4,h5,h6,li").append(" ")
        return doc
            .body()
            .text()
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun htmlToFormattedText(html: String): String {
        if (html.isBlank()) return ""
        val doc = Jsoup.parseBodyFragment(html)
        doc.select("script,style,noscript,iframe").remove()
        val lines = mutableListOf<String>()

        fun emit(
            text: String,
            major: Boolean = false,
        ) {
            val value = text.replace(Regex("[ \\t]+"), " ").trim()
            if (value.isBlank()) return
            if (major && lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
            lines.add(value)
            if (major) lines.add("")
        }

        fun collectInline(element: Element): String {
            val parts = mutableListOf<String>()
            element.childNodes().forEach { child ->
                when (child) {
                    is TextNode -> parts.add(child.text())
                    is Element -> {
                        when (child.tagName().lowercase()) {
                            "br" -> parts.add("\n")
                            "script", "style", "noscript", "iframe" -> Unit
                            "td", "th" -> parts.add(collectInline(child))
                            else -> parts.add(collectInline(child))
                        }
                    }
                }
            }
            return parts
                .joinToString("")
                .replace(Regex("[ \\t]*\\n[ \\t]*"), "\n")
                .replace(Regex("[ \\t]+"), " ")
                .trim()
        }

        val blockTags = setOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "blockquote", "table")

        fun walk(element: Element) {
            when (element.tagName().lowercase()) {
                "table", "tbody", "thead" -> element.children().forEach(::walk)
                "tr" -> {
                    val row =
                        element
                            .children()
                            .filter { it.tagName().equals("td", true) || it.tagName().equals("th", true) }
                            .joinToString(" | ") { collectInline(it) }
                    emit(row)
                }
                "p", "li" -> emit(collectInline(element))
                "blockquote", "h1", "h2", "h3", "h4", "h5", "h6" -> emit(collectInline(element), major = true)
                "div" -> {
                    val blockChildren =
                        element.children().filter { child ->
                            child.tagName().lowercase() in blockTags
                        }
                    if (blockChildren.isNotEmpty() && element.ownText().isBlank()) {
                        element.children().forEach(::walk)
                    } else {
                        emit(collectInline(element))
                    }
                }
                "ul", "ol" -> element.children().forEach(::walk)
                "body" -> {
                    val hasBlockChildren = element.children().any { it.tagName().lowercase() in blockTags }
                    if (hasBlockChildren) element.children().forEach(::walk) else emit(collectInline(element))
                }
                else -> {
                    if (element.children().isEmpty()) emit(collectInline(element)) else element.children().forEach(::walk)
                }
            }
        }

        walk(doc.body())
        return lines
            .joinToString("\n")
            .replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\\n[ \\t]+"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trimEnd()
    }

    private fun Node.traverseTextNodes(): List<TextNode> {
        val result = mutableListOf<TextNode>()

        fun visit(node: Node) {
            if (node is TextNode) {
                result.add(node)
            }
            node.childNodes().forEach(::visit)
        }
        visit(this)
        return result
    }
}
