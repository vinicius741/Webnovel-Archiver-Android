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
}
