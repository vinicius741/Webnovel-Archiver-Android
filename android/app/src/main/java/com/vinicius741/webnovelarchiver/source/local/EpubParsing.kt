package com.vinicius741.webnovelarchiver.source.local

import com.vinicius741.webnovelarchiver.source.blockText
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.parser.Parser
import org.jsoup.safety.Safelist
import java.net.URLDecoder

data class EpubMetadata(
    val title: String? = null,
    val author: String? = null,
    val description: String? = null,
    val subjects: List<String> = emptyList(),
)

/** One table-of-contents entry: a title and the spine document (and anchor) it points at. */
data class EpubTocEntry(
    val title: String,
    val path: String,
    val fragment: String?,
)

/** A piece of a spine document: from the [from] anchor (or the top) up to the [to] anchor (or the end). */
data class EpubSegment(
    val path: String,
    val from: String? = null,
    val to: String? = null,
)

data class EpubChapterPlan(
    val title: String,
    /** Stable key of the chapter in its book, the first segment's target. */
    val key: String,
    val segments: List<EpubSegment>,
)

data class EpubPackage(
    val metadata: EpubMetadata,
    /** Readable spine documents in reading order, as zip entry paths. */
    val spine: List<String>,
    val toc: List<EpubTocEntry>,
    val coverPath: String?,
)

/** What chapter planning needs to know about one spine document. */
data class EpubDocInfo(
    val textLength: Int,
    val heading: String?,
)

/**
 * Reads an EPUB's package (metadata, spine, table of contents, cover) and turns it into chapters:
 * every table-of-contents entry starts a chapter, which runs to the next entry, across spine
 * documents the table of contents does not list. Entries that point into the middle of a document
 * split it at their anchors. Pure: [read] returns a zip entry's bytes.
 */
object EpubParsing {
    /** A spine document before the first table-of-contents entry needs this much text to be a chapter. */
    const val FRONT_MATTER_MIN_CHARS = 500

    private val htmlTypes = setOf("application/xhtml+xml", "text/html", "application/x-dtbook+xml")
    private val containerTags = setOf("body", "div", "section", "article", "main", "header", "footer", "aside", "blockquote")
    private val safelist: Safelist = Safelist.relaxed().removeTags("img", "a")

    fun readPackage(read: (String) -> ByteArray?): EpubPackage {
        val container = read("META-INF/container.xml")?.let(::xml) ?: error("Not an EPUB: META-INF/container.xml is missing")
        val opfPath =
            elements(container, "rootfile").firstOrNull()?.attr("full-path")?.takeIf { it.isNotBlank() }
                ?: error("Not an EPUB: no package document")
        val opf = read(opfPath)?.let(::xml) ?: error("Not an EPUB: $opfPath is missing")
        val opfDir = opfPath.substringBeforeLast('/', "")
        val manifest =
            elements(opf, "item").associate { item ->
                item.attr("id") to ManifestItem(resolve(opfDir, item.attr("href")), item.attr("media-type"), item.attr("properties"))
            }
        val spineElement = elements(opf, "spine").firstOrNull()
        val spine =
            elements(opf, "itemref")
                .filterNot { it.attr("linear").equals("no", ignoreCase = true) }
                .mapNotNull { manifest[it.attr("idref")] }
                .filter { it.mediaType in htmlTypes || it.path.substringAfterLast('.').lowercase() in setOf("xhtml", "html", "htm") }
                .map { it.path }
                .distinct()
        val navPath = manifest.values.firstOrNull { "nav" in it.properties.split(' ') }?.path
        val ncxPath =
            spineElement?.attr("toc")?.let { manifest[it]?.path }
                ?: manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }?.path
        val toc =
            navPath?.let { path -> read(path)?.let { navToc(String(it, Charsets.UTF_8), path) } }?.takeIf { it.isNotEmpty() }
                ?: ncxPath?.let { path -> read(path)?.let { ncxToc(xml(it), path) } }.orEmpty()
        return EpubPackage(metadata(opf), spine, toc, coverPath(opf, manifest))
    }

    /** Chapters in reading order. [info] describes a spine document (its text length and first heading). */
    fun planChapters(
        book: EpubPackage,
        info: (String) -> EpubDocInfo,
    ): List<EpubChapterPlan> {
        val spineSet = book.spine.toSet()
        val tocByPath =
            book.toc
                .filter { it.path in spineSet }
                // A part and its first chapter often share one target: the deeper (later) title wins.
                .associateBy { it.path to it.fragment }
                .values
                .groupBy { it.path }
        if (tocByPath.isEmpty()) return untitledChapters(book.spine, info)
        val chapters = mutableListOf<Builder>()
        var current: Builder? = null
        book.spine.forEach { path ->
            val entries = sortedEntries(tocByPath[path], book.toc)
            if (entries.isEmpty()) {
                current?.segments?.add(EpubSegment(path)) ?: frontMatter(path, info)?.let {
                    chapters += it
                    current = it
                }
                return@forEach
            }
            val first = entries.first()
            if (first.fragment != null) current?.segments?.add(EpubSegment(path, null, first.fragment))
            entries.forEachIndexed { index, entry ->
                val next = entries.getOrNull(index + 1)?.fragment
                val key = entry.fragment?.let { "$path#$it" } ?: path
                Builder(entry.title, key, mutableListOf(EpubSegment(path, entry.fragment, next))).also {
                    chapters += it
                    current = it
                }
            }
        }
        return chapters.map { EpubChapterPlan(it.title, it.key, it.segments.toList()) }
    }

    /** A document before the first table-of-contents entry: a chapter only with enough text. */
    private fun frontMatter(
        path: String,
        info: (String) -> EpubDocInfo,
    ): Builder? {
        val doc = info(path)
        return Builder(doc.heading ?: "Front matter", path, mutableListOf(EpubSegment(path)))
            .takeIf { doc.textLength >= FRONT_MATTER_MIN_CHARS }
    }

    /** Text length and first heading of a spine document, for [planChapters]. */
    fun docInfo(html: String): EpubDocInfo {
        val doc = Jsoup.parse(html)
        val heading =
            doc
                .body()
                .selectFirst("h1, h2, h3")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        return EpubDocInfo(doc.body().text().length, heading ?: doc.title().trim().takeIf { it.isNotEmpty() })
    }

    /** Reader HTML for a planned chapter. [read] returns a spine document's markup. */
    fun chapterHtml(
        plan: EpubChapterPlan,
        read: (String) -> String?,
    ): String =
        plan.segments
            .joinToString("\n") { segment -> read(segment.path)?.let { segmentHtml(it, segment) }.orEmpty() }
            .let { Jsoup.clean(it, safelist) }
            .let(::dropEmptyParagraphs)

    /** The markup of one segment: the body, cut at its anchors. */
    fun segmentHtml(
        html: String,
        segment: EpubSegment,
    ): String {
        val body = Jsoup.parse(html).body()
        if (segment.from == null && segment.to == null) return body.html()
        // The next chapter starts at the very top: nothing of this document belongs to this one.
        if (segment.to != null && body.isAnchor(segment.to)) return ""
        val anchors = setOfNotNull(segment.from, segment.to)
        val blocks = blocks(body, anchors, emptySet())

        fun indexOf(id: String) = blocks.indexOfFirst { id in it.opens || it.node.holdsAnchor(id) }
        val start = segment.from?.let(::indexOf)?.takeIf { it >= 0 } ?: 0
        // An end anchor on the first block leaves an empty segment, never the whole document.
        val end = segment.to?.let(::indexOf)?.takeIf { it >= start } ?: blocks.size
        return blocks.subList(start, end).joinToString("") { it.node.outerHtml() }
    }

    fun resolve(
        baseDir: String,
        href: String,
    ): String {
        val decoded = runCatching { URLDecoder.decode(href.substringBefore('#').replace("+", "%2B"), "UTF-8") }.getOrDefault(href)
        val parts = (if (baseDir.isEmpty() || decoded.startsWith('/')) emptyList() else baseDir.split('/')).toMutableList()
        decoded.trimStart('/').split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull()
                else -> parts += part
            }
        }
        return parts.joinToString("/")
    }

    private data class ManifestItem(
        val path: String,
        val mediaType: String,
        val properties: String,
    )

    private class Builder(
        val title: String,
        val key: String,
        val segments: MutableList<EpubSegment>,
    )

    /** Entries of one document in table-of-contents order, a whole-document entry first. */
    private fun sortedEntries(
        entries: List<EpubTocEntry>?,
        toc: List<EpubTocEntry>,
    ): List<EpubTocEntry> {
        entries ?: return emptyList()
        val order = entries.sortedBy { entry -> toc.indexOfLast { it.path == entry.path && it.fragment == entry.fragment } }
        val whole = order.filter { it.fragment == null }
        return if (whole.isEmpty()) order else listOf(whole.last()) + order.filter { it.fragment != null }
    }

    private fun untitledChapters(
        spine: List<String>,
        info: (String) -> EpubDocInfo,
    ): List<EpubChapterPlan> =
        spine
            .map { it to info(it) }
            .filter { (_, doc) -> doc.textLength > 0 }
            .mapIndexed { index, (path, doc) -> EpubChapterPlan(doc.heading ?: "Chapter ${index + 1}", path, listOf(EpubSegment(path))) }

    private fun metadata(opf: Document): EpubMetadata {
        fun first(name: String) =
            elements(opf, name)
                .firstOrNull()
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        val description =
            first("description")?.let { raw ->
                Jsoup
                    .parse(Parser.unescapeEntities(raw, false))
                    .body()
                    .blockText()
                    .takeIf { it.isNotBlank() }
            }
        return EpubMetadata(
            title = first("title"),
            author =
                elements(opf, "creator")
                    .map { it.text().trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString(", ")
                    .ifEmpty { null },
            description = description,
            subjects = elements(opf, "subject").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct(),
        )
    }

    private fun coverPath(
        opf: Document,
        manifest: Map<String, ManifestItem>,
    ): String? {
        val images = manifest.filterValues { it.mediaType.startsWith("image/") }
        return images.values.firstOrNull { "cover-image" in it.properties.split(' ') }?.path
            ?: elements(opf, "meta").firstOrNull { it.attr("name") == "cover" }?.attr("content")?.let { images[it]?.path }
            ?: images.entries
                .firstOrNull { (id, item) -> "cover" in id.lowercase() || "cover" in item.path.lowercase() }
                ?.value
                ?.path
    }

    private fun navToc(
        html: String,
        path: String,
    ): List<EpubTocEntry> {
        val doc = Jsoup.parse(html)
        val nav = doc.select("nav").firstOrNull { "toc" in it.attr("epub:type") } ?: doc.selectFirst("nav") ?: return emptyList()
        val dir = path.substringBeforeLast('/', "")
        return nav.select("a[href]").mapNotNull { link ->
            val title = link.text().trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            tocEntry(title, dir, link.attr("href"))
        }
    }

    private fun ncxToc(
        ncx: Document,
        path: String,
    ): List<EpubTocEntry> {
        val dir = path.substringBeforeLast('/', "")
        return elements(ncx, "navPoint").mapNotNull { point ->
            val title =
                point
                    .children()
                    .firstOrNull { it.localName() == "navLabel" }
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val src = point.children().firstOrNull { it.localName() == "content" }?.attr("src") ?: return@mapNotNull null
            tocEntry(title, dir, src)
        }
    }

    private fun tocEntry(
        title: String,
        dir: String,
        href: String,
    ): EpubTocEntry? {
        if (href.isBlank() || href.contains("://")) return null
        val fragment = href.substringAfter('#', "").takeIf { it.isNotEmpty() }
        return EpubTocEntry(title.replace(Regex("\\s+"), " "), resolve(dir, href), fragment)
    }

    /** A top-level piece of a document, and the anchors of the containers opened at it. */
    private class Block(
        val node: Node,
        val opens: Set<String>,
    )

    /**
     * The element's blocks, opening every container that holds a split anchor below itself. A
     * container that is itself an anchor ("part 1" around "chapter 1") is opened too, and its
     * anchor moves to its first block, so both entries split it.
     */
    private fun blocks(
        element: Element,
        anchors: Set<String>,
        opening: Set<String>,
    ): List<Block> {
        val result = mutableListOf<Block>()
        var pending = opening
        element.childNodes().forEach { child ->
            if (child is Element &&
                child.tagName().lowercase() in containerTags &&
                anchors.any { id -> child.select("*").any { it !== child && it.isAnchor(id) } }
            ) {
                val inner = blocks(child, anchors, pending + anchors.filter { child.isAnchor(it) })
                if (inner.isNotEmpty()) {
                    result += inner
                    pending = emptySet()
                }
            } else {
                result += Block(child, pending)
                pending = emptySet()
            }
        }
        return result
    }

    private fun Node.holdsAnchor(id: String): Boolean = this is Element && (isAnchor(id) || select("*").any { it.isAnchor(id) })

    private fun Element.isAnchor(id: String): Boolean = id() == id || (tagName().equals("a", ignoreCase = true) && attr("name") == id)

    private fun Element.localName(): String = tagName().substringAfter(':')

    private fun elements(
        doc: Document,
        localName: String,
    ): List<Element> = doc.allElements.filter { it.localName().equals(localName, ignoreCase = true) }

    private fun xml(bytes: ByteArray): Document = Jsoup.parse(String(bytes, Charsets.UTF_8), "", Parser.xmlParser())

    private fun dropEmptyParagraphs(html: String): String {
        val body = Jsoup.parseBodyFragment(html).body()
        body.select("p, div, span").filter { it.text().isBlank() && it.select("br, hr, table").isEmpty() }.forEach { it.remove() }
        return body.html()
    }
}
