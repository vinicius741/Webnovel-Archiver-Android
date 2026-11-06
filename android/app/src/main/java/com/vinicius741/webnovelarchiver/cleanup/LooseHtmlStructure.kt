package com.vinicius741.webnovelarchiver.cleanup

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Converts text separated by `<br>` tags into semantic paragraphs.
 *
 * XenForo commonly emits one `<br>` for a hard line break and two for a paragraph boundary, and
 * Royal Road chapters are often a single `<p>` whose paragraphs are separated the same way. Left
 * as is, the reader's sentence annotation rebuilds each multi-sentence block from its text and
 * flattens every break into one wall of prose. Splitting those runs into `<p>` elements preserves
 * both paragraph spacing and intentional single-line breaks without changing already-structured
 * source HTML.
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

    /** Containers whose loose break-separated children are wrapped into `<p>` elements in place. */
    private const val NESTED_CONTAINERS = "div, blockquote, li"

    private sealed interface Segment {
        data class Block(
            val node: Node,
        ) : Segment

        data class Paragraph(
            val nodes: List<Node>,
        ) : Segment
    }

    fun wrapBreakSeparatedParagraphs(root: Element) {
        val rootNodes = root.childNodes().toList()
        if (rootNodes.any { breakCount(it) != null }) wrapLooseRuns(root, rootNodes)

        root
            .select("p")
            .filter { it !== root && splitsIntoParagraphs(it) }
            .forEach(::splitParagraph)
        root
            .select(NESTED_CONTAINERS)
            .filter { it !== root && splitsIntoParagraphs(it) }
            .forEach { wrapLooseRuns(it, it.childNodes().toList()) }
    }

    /** True when [element] holds at least one paragraph boundary (two or more breaks) between content. */
    private fun splitsIntoParagraphs(element: Element): Boolean = segments(element.childNodes()).count { it is Segment.Paragraph } > 1

    private fun wrapLooseRuns(
        container: Element,
        nodes: List<Node>,
    ) {
        val segments = segments(nodes)
        // Detach one by one (not empty()) so each node leaves cleanly and can be re-appended below.
        nodes.forEach(Node::remove)
        segments.forEach { segment ->
            when (segment) {
                is Segment.Block -> container.appendChild(segment.node)
                is Segment.Paragraph -> container.appendElement("p").appendChildren(segment.nodes)
            }
        }
    }

    /** Replaces a `<p>` holding break-separated paragraphs with one sibling `<p>` per paragraph. */
    private fun splitParagraph(paragraph: Element) {
        val segments = segments(paragraph.childNodes().toList())
        segments.forEach { segment ->
            when (segment) {
                is Segment.Block -> paragraph.before(segment.node)
                is Segment.Paragraph -> paragraph.before(paragraph.shallowClone().appendChildren(segment.nodes))
            }
        }
        paragraph.remove()
    }

    private fun segments(nodes: List<Node>): List<Segment> {
        val segments = mutableListOf<Segment>()
        var paragraph: MutableList<Node>? = null
        var pendingBreaks = 0

        fun flush() {
            paragraph?.takeIf { run -> run.any { !it.isBlankText() } }?.let { segments += Segment.Paragraph(it) }
            paragraph = null
        }

        nodes.forEach { node ->
            val breaks = breakCount(node)
            when {
                breaks != null -> pendingBreaks += breaks
                node.isBlankText() && pendingBreaks > 0 -> Unit
                node is Element && node.tagName().lowercase() in directBlockTags -> {
                    flush()
                    pendingBreaks = 0
                    segments += Segment.Block(node)
                }
                else -> {
                    if (pendingBreaks >= 2) flush()
                    if (pendingBreaks == 1) paragraph?.add(Element("br"))
                    val destination = paragraph ?: mutableListOf<Node>().also { paragraph = it }
                    destination.add(node)
                    pendingBreaks = 0
                }
            }
        }
        flush()
        return segments
    }

    /**
     * How many line breaks [node] stands for, or null when it carries content. Besides `<br>`
     * itself, an inline wrapper holding nothing but breaks (Royal Road wraps some in an empty
     * `<span>`) counts, so it cannot glue the paragraphs around it together.
     */
    private fun breakCount(node: Node): Int? {
        if (node !is Element) return null
        val tag = node.tagName().lowercase()
        if (tag == "br") return 1
        if (tag in directBlockTags || node.hasText()) return null
        val descendants = node.getAllElements().drop(1)
        if (descendants.isEmpty() || descendants.any { !it.tagName().equals("br", ignoreCase = true) }) return null
        return descendants.size
    }

    private fun Node.isBlankText(): Boolean = this is TextNode && isBlank

    private fun Element.appendChildren(nodes: List<Node>): Element = apply { nodes.forEach(::appendChild) }
}
