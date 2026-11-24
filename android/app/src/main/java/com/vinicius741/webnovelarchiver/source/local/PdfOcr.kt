package com.vinicius741.webnovelarchiver.source.local

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.abs

/** A line of text recognized on a page image, with its bounding box in the image's pixels. */
data class OcrLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/** Which pages of a PDF have their text recognized from the page image (OCR). */
object PdfOcrPlanning {
    /**
     * A page with fewer letters than this in its text layer shows its text only as an image: a
     * scanner's stamp ("Scanned with …"), a page number or a watermark is all the layer holds.
     */
    private const val MIN_TEXT_CHARS = 50

    /**
     * Every page when the device cannot read the text layer ([layer] is null). Otherwise the pages
     * without text, but only when most pages have none, as in a scan: a text PDF's few blank pages
     * are illustrations or white space, and recognizing them would add stray words.
     */
    fun pagesToRecognize(
        layer: List<String>?,
        pageCount: Int,
    ): Set<Int> =
        when {
            layer == null -> (0 until pageCount).toSet()
            isScan(layer) -> (0 until pageCount).filterNot { hasText(layer.getOrNull(it)) }.toSet()
            else -> emptySet()
        }

    /** True when most pages have no text of their own in the text layer. */
    fun isScan(layer: List<String>): Boolean = layer.isNotEmpty() && layer.count { !hasText(it) } * 2 > layer.size

    private fun hasText(page: String?): Boolean = page != null && page.count { !it.isWhitespace() } >= MIN_TEXT_CHARS
}

/**
 * Turns the lines recognized on one page into page text for [PdfTextReflow]: one visual line per
 * line in reading order, and a blank line where a paragraph visibly starts (an indented first
 * line, or more space above than between the page's other lines).
 * The lines nearest the page's top and bottom never get a blank line, because a running header or
 * page number there is removed later and the blank line would end a sentence that runs on.
 */
object OcrPageLayout {
    /** A first-line indent of at least this many line heights starts a paragraph. */
    private const val INDENT_HEIGHTS = 0.6

    /** Space above a line starts a paragraph when it is more than this many line heights... */
    private const val GAP_HEIGHTS = 1.0

    /** ...and more than this many times the page's usual space between lines (double-spaced pages). */
    private const val GAP_TO_USUAL = 1.5

    /** A line ending more than this many line heights before the column's right edge ends its paragraph. */
    private const val SHORT_LINE_HEIGHTS = 2.0

    /** Equally indented lines in a row that make a block quote rather than paragraphs of their own. */
    private const val BLOCK_QUOTE_LINES = 3

    /** Lines before and after a line that set its left margin; few enough that a skewed scan's drift stays small. */
    private const val NEIGHBOR_LINES = 5

    /** Lines at the page's top and bottom that can be a running header, footer or page number (as in [PdfTextReflow]). */
    private const val EDGE_LINES = 2

    /** A page has two columns only when each holds at least this many lines and this share of them... */
    private const val MIN_COLUMN_LINES = 3
    private const val MIN_COLUMN_SHARE = 0.25

    /** ...and at most this share of its lines (titles, running headers) cross the gap between them. */
    private const val MAX_SPANNING_SHARE = 0.2

    fun text(lines: List<OcrLine>): String {
        val visual = readingRows(lines).map(::joined)
        if (visual.isEmpty()) return ""
        val height = median(visual.map { it.bottom - it.top }).coerceAtLeast(1)
        val gaps =
            visual
                .zipWithNext()
                .filter { (above, below) -> sameColumn(above, below) && below.top > above.top }
                .map { (above, below) -> (below.top - above.bottom).coerceAtLeast(0) }
        val minGap = maxOf(height * GAP_HEIGHTS, (if (gaps.isEmpty()) 0 else median(gaps)) * GAP_TO_USUAL)
        return buildString {
            visual.forEachIndexed { index, line ->
                if (index > 0) {
                    append('\n')
                    val inside = index > EDGE_LINES && index < visual.size - EDGE_LINES
                    if (inside && startsParagraph(visual, index, height, minGap)) append('\n')
                }
                append(line.text)
            }
        }
    }

    /**
     * The lines in reading order. The recognizer's own order can put a running header after the line
     * below it, which would keep the header out of [PdfTextReflow]'s reach at the page's top.
     */
    fun readingOrder(lines: List<OcrLine>): List<OcrLine> = readingRows(lines).flatten()

    /**
     * Rows top to bottom; on a two-column page (or a two-page spread) the left column, then the
     * right, with lines that cross the gap between them (a title, running headers) before the
     * columns when they sit above them and after otherwise.
     */
    private fun readingRows(lines: List<OcrLine>): List<List<OcrLine>> {
        val kept = lines.filter { it.text.isNotBlank() }
        val gutter = gutter(kept) ?: return rows(kept)
        val (spanning, columns) = kept.partition { it.left < gutter && it.right > gutter }
        val columnsTop = columns.minOf { it.top }
        val (above, below) = spanning.partition { it.top < columnsTop }
        return rows(above) + rows(columns.filter { it.right <= gutter }) + rows(columns.filter { it.left >= gutter }) + rows(below)
    }

    /** An x between two columns of lines that sit side by side, or null for a single column. */
    private fun gutter(lines: List<OcrLine>): Int? {
        val minLines = maxOf(MIN_COLUMN_LINES.toDouble(), lines.size * MIN_COLUMN_SHARE)
        return lines
            .map { it.right }
            .distinct()
            .mapNotNull { x ->
                val left = lines.filter { it.right <= x }
                val right = lines.filter { it.left >= x }
                val spanning = lines.size - left.size - right.size
                val sideBySide =
                    left.isNotEmpty() &&
                        right.isNotEmpty() &&
                        minOf(left.maxOf { it.bottom }, right.maxOf { it.bottom }) > maxOf(left.minOf { it.top }, right.minOf { it.top })
                (x to spanning).takeIf {
                    left.size >= minLines && right.size >= minLines && spanning <= lines.size * MAX_SPANNING_SHARE && sideBySide
                }
            }.minByOrNull { it.second }
            ?.first
    }

    /**
     * Lines top to bottom, each row's left to right. A line joins the row above when its middle is
     * within that row's first line and it sits beside every line in it: a skewed scan's next line
     * overlaps the one above but lies under it, not beside it.
     */
    private fun rows(lines: List<OcrLine>): List<List<OcrLine>> {
        val rows = mutableListOf<MutableList<OcrLine>>()
        lines.sortedBy { it.top }.forEach { line ->
            val row = rows.lastOrNull()
            if (row != null && (line.top + line.bottom) / 2 <= row.first().bottom && row.none { sameColumn(it, line) }) {
                row += line
            } else {
                rows += mutableListOf(line)
            }
        }
        return rows.map { row -> row.sortedBy { it.left } }
    }

    /** One row's pieces as one line: the recognizer can split a visual line at a wide space. */
    private fun joined(row: List<OcrLine>): OcrLine =
        OcrLine(
            text = row.joinToString(" ") { it.text.trim() },
            left = row.minOf { it.left },
            top = row.minOf { it.top },
            right = row.maxOf { it.right },
            bottom = row.maxOf { it.bottom },
        )

    /**
     * Extra space above, or a first-line indent (the line starts further right than the lines around
     * it). A line inside a block quote, a run of at least [BLOCK_QUOTE_LINES] equally indented lines,
     * continues the quote when the line above is full; after a line of dialogue that ends short, or
     * in a shorter run (a line of dialogue, then the next paragraph's first line), it is a paragraph.
     */
    private fun startsParagraph(
        lines: List<OcrLine>,
        index: Int,
        height: Int,
        minGap: Double,
    ): Boolean {
        val line = lines[index]
        val previous = lines[index - 1]
        // A line above the previous one is the top of the next column or page half, not a paragraph.
        val gap = sameColumn(line, previous) && line.top > previous.top && line.top - previous.bottom > minGap
        val indent = height * INDENT_HEIGHTS
        val around =
            (maxOf(0, index - NEIGHBOR_LINES)..minOf(lines.lastIndex, index + NEIGHBOR_LINES))
                .map { lines[it] }
                .filter { it === line || sameColumn(it, line) }
        val margin = around.minOf { it.left }
        val edge = around.maxOf { it.right }

        fun inRun(other: OcrLine) = sameColumn(other, line) && other.left - margin > indent && abs(other.left - line.left) <= indent
        val above = previous.takeIf { line.top > it.top && inRun(it) }
        val continuesQuote =
            above != null &&
                edge - above.right <= height * SHORT_LINE_HEIGHTS &&
                runLength(lines, index, ::inRun) >= BLOCK_QUOTE_LINES
        return gap || (line.left - margin > indent && !continuesQuote)
    }

    /** Consecutive lines around [index] (itself included) that are [inRun]. */
    private fun runLength(
        lines: List<OcrLine>,
        index: Int,
        inRun: (OcrLine) -> Boolean,
    ): Int {
        val before = (index - 1 downTo 0).takeWhile { inRun(lines[it]) }.size
        val after = (index + 1..lines.lastIndex).takeWhile { inRun(lines[it]) }.size
        return before + 1 + after
    }

    private fun sameColumn(
        a: OcrLine,
        b: OcrLine,
    ): Boolean = a.left < b.right && b.left < a.right

    private fun median(values: List<Int>): Int = values.sorted()[values.size / 2]
}

/**
 * Recognized page text saved under the app's files, so a scanned PDF is recognized once: rescans,
 * syncs and chapter reads use the saved text, and an interrupted import resumes where it stopped.
 * Books are keyed by their content, so a renamed or moved file keeps its text and an edited one
 * is recognized again. Saving the text also keeps chapter ids steady.
 */
class PdfOcrCache(
    private val root: File,
) {
    /** The saved pages of [file]'s book. */
    fun book(file: File): Pages = Pages(File(root, contentKey(file)))

    class Pages internal constructor(
        private val dir: File,
    ) {
        /** Page [index]'s saved text, or null when it has not been recognized yet. */
        fun page(index: Int): String? = File(dir, "$index.txt").takeIf { it.isFile }?.readText()

        fun save(
            index: Int,
            text: String,
        ) {
            dir.mkdirs()
            // A unique temporary name: two copies of one scan read at once save the same pages.
            val partial = File.createTempFile("page$index-", ".part", dir)
            try {
                partial.writeText(text)
                if (!partial.renameTo(File(dir, "$index.txt"))) throw IOException("Could not save the recognized text of page ${index + 1}")
            } finally {
                partial.delete()
            }
        }
    }

    private fun contentKey(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(KEY_LENGTH)
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val KEY_LENGTH = 32
    }
}
