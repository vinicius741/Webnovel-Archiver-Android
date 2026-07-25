package com.vinicius741.webnovelarchiver.cleanup

import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/**
 * Converts forum-style loose text separated by `<br>` tags into semantic paragraphs.
 *
 * XenForo commonly emits one `<br>` for a hard line break and two for a paragraph boundary. Keeping
 * that content as loose body text makes the reader's sentence annotation fallback flatten every
 * break. Wrapping the runs in `<p>` elements preserves both paragraph spacing and intentional
 * single-line breaks without changing already-structured source HTML.
 */
internal object LooseHtmlStructure {
    private val directBlockTags =
        setOf(
            "address",
            "article",
            "aside",
            "blockquote",
            "div",
            "dl",
            "fieldset",
            "figure",
            "footer",
            "form",
            "h1",
            "h2",
            "h3",
            "h4",
            "h5",
            "h6",
            "header",
            "hr",
            "main",
            "nav",
            "ol",
            "p",
            "pre",
            "section",
            "table",
            "ul",
        )

    fun wrapBreakSeparatedParagraphs(root: Element) {
        val original = root.childNodes().map { it.clone() }
        if (original.none { it is Element && it.tagName().equals("br", ignoreCase = true) }) return

        root.empty()
        var paragraph: Element? = null
        var pendingBreaks = 0

        original.forEach { node ->
            when {
                node is Element && node.tagName().equals("br", ignoreCase = true) -> {
                    pendingBreaks += 1
                }
                node is TextNode && node.text().isBlank() && pendingBreaks > 0 -> Unit
                node is Element && node.tagName().lowercase() in directBlockTags -> {
                    paragraph = null
                    pendingBreaks = 0
                    root.appendChild(node)
                }
                else -> {
                    if (pendingBreaks >= 2) paragraph = null
                    if (pendingBreaks == 1) paragraph?.appendElement("br")
                    val destination = paragraph ?: root.appendElement("p").also { paragraph = it }
                    destination.appendChild(node)
                    pendingBreaks = 0
                }
            }
        }
    }
}
