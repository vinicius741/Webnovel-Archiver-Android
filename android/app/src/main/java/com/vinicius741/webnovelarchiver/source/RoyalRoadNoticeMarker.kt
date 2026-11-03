package com.vinicius741.webnovelarchiver.source

import com.vinicius741.webnovelarchiver.cleanup.InsertedNoticeMarker
import com.vinicius741.webnovelarchiver.cleanup.InsertedNoticeStrip
import java.util.Base64

/**
 * Royal Road injects an anti-piracy notice into each chapter, wrapped in a bare `<span>` whose
 * class is Base64 of `r` + 32 hex digits. Paragraph classes decode to `rs` + hex, so they never
 * match. Expects Jsoup-serialized HTML and scans it as text, since most chapters need no edit and
 * parsing a document per chapter is what makes cleaning a long novel slow.
 */
internal object RoyalRoadNoticeMarker : InsertedNoticeMarker {
    private val openingTag = Regex("""<span class="([A-Za-z0-9+/]{44})">""")
    private val spanTag = Regex("""<(/?)span\b[^>]*>""")
    private val markupOrSpace = Regex("""<[^>]*>|&nbsp;|&#160;""")
    private val decodedMarkerClass = Regex("r[0-9a-f]{32}")

    override fun strip(html: String): InsertedNoticeStrip? {
        var found = false
        var removed = 0
        var cursor = 0
        val cleaned = StringBuilder(html.length)
        for (tag in openingTag.findAll(html)) {
            if (tag.range.first < cursor || !isMarkerClass(tag.groupValues[1])) continue
            val contentStart = tag.range.last + 1
            val contentEnd = closingSpanStart(html, contentStart) ?: continue
            found = true
            // Blank markers stay as they are, so already-clean chapter files are never rewritten.
            if (html.substring(contentStart, contentEnd).replace(markupOrSpace, "").isBlank()) continue
            cleaned.append(html, cursor, contentStart)
            cursor = contentEnd
            removed += 1
        }
        if (!found) return null
        if (removed == 0) return InsertedNoticeStrip(html, 0)
        cleaned.append(html, cursor, html.length)
        return InsertedNoticeStrip(cleaned.toString(), removed)
    }

    private fun isMarkerClass(value: String): Boolean =
        runCatching { String(Base64.getDecoder().decode(value), Charsets.ISO_8859_1) }
            .getOrNull()
            ?.let(decodedMarkerClass::matches) == true

    private fun closingSpanStart(
        html: String,
        from: Int,
    ): Int? {
        var depth = 1
        for (tag in spanTag.findAll(html, from)) {
            depth += if (tag.groupValues[1].isEmpty()) 1 else -1
            if (depth == 0) return tag.range.first
        }
        return null
    }
}
