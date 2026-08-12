package com.vinicius741.webnovelarchiver.cleanup

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/** HTML-to-text transformations for downloaded chapters and reader display. */
object HtmlCleanup {
    fun htmlToFormattedText(html: String): String {
        if (html.isBlank()) return ""
        val doc = Jsoup.parseBodyFragment(html)
        doc.select("script,style,noscript,iframe").remove()
        val lines = mutableListOf<String>()
        val blockTags = setOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "blockquote", "table")
        val tableCellTags = setOf("td", "th")

        fun walk(element: Element) {
            when (element.tagName().lowercase()) {
                "table", "tbody", "thead", "ul", "ol" -> element.children().forEach(::walk)
                "tr" -> {
                    val row =
                        element
                            .children()
                            .filter { it.tagName().lowercase() in tableCellTags }
                            .joinToString(" | ") { it.inlineText() }
                    lines.emit(row)
                }
                "p", "li" -> lines.emit(element.inlineText())
                "blockquote", "h1", "h2", "h3", "h4", "h5", "h6" -> lines.emit(element.inlineText(), major = true)
                "div" -> {
                    val hasBlockChildren = element.children().any { it.tagName().lowercase() in blockTags }
                    if (hasBlockChildren && element.ownText().isBlank()) {
                        element.children().forEach(::walk)
                    } else {
                        lines.emit(element.inlineText())
                    }
                }
                "body" -> {
                    val hasBlockChildren = element.children().any { it.tagName().lowercase() in blockTags }
                    if (hasBlockChildren) element.children().forEach(::walk) else lines.emit(element.inlineText())
                }
                else -> {
                    if (element.children().isEmpty()) lines.emit(element.inlineText()) else element.children().forEach(::walk)
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

    private fun MutableList<String>.emit(
        text: String,
        major: Boolean = false,
    ) {
        val value = text.replace(Regex("[ \\t]+"), " ").trim()
        if (value.isBlank()) return
        if (major && isNotEmpty() && last().isNotBlank()) add("")
        add(value)
        if (major) add("")
    }

    private fun Element.inlineText(): String =
        childNodes()
            .joinToString("") { child ->
                when (child) {
                    is TextNode -> child.text()
                    is Element -> if (child.tagName().equals("br", true)) "\n" else child.inlineText()
                    else -> ""
                }
            }.replace(Regex("[ \\t]*\\n[ \\t]*"), "\n")
            .replace(Regex("[ \\t]+"), " ")
            .trim()
}
