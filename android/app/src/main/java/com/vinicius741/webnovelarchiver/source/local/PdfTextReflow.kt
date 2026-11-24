package com.vinicius741.webnovelarchiver.source.local

/**
 * Turns the text of a PDF's pages, which is laid out in hard-wrapped lines, back into flowing
 * paragraphs: page numbers and running headers/footers go, hyphenated line breaks join, and a
 * paragraph ends at a blank line or at a short line that closes a sentence. Chapter headings stay
 * on lines of their own so [LocalTextChapters.split] finds them.
 */
object PdfTextReflow {
    private const val SHORT_LINE_RATIO = 0.75
    private const val LINE_LENGTH_PERCENTILE = 0.75
    private const val MIN_PAGES_FOR_RUNNING_LINES = 4
    private const val RUNNING_LINE_SHARE = 0.4
    private const val MAX_RUNNING_LINE_CHARS = 60
    private const val EDGE_LINES = 2

    private val pageNumber =
        Regex("^(?:page\\s*)?[-–—(\\[]?\\s*\\d{1,4}\\s*[-–—)\\]]?(?:\\s*(?:of|/)\\s*\\d{1,4})?$", RegexOption.IGNORE_CASE)
    private val sentenceEnd = Regex("[.!?…:\"'”’)\\]*—]$")

    /** One string of paragraphs separated by blank lines. */
    fun reflow(pages: List<String>): String {
        val pageLines =
            pages.map { page ->
                page
                    .replace("\r\n", "\n")
                    .replace('\r', '\n')
                    .lines()
                    .map { it.replace(' ', ' ').trim() }
            }
        val running = runningLines(pageLines)
        val lines = pageLines.flatMap { page -> withoutFurniture(page, running) }
        val typical = typicalLength(lines)
        val paragraphs = mutableListOf<String>()
        val current = StringBuilder()

        fun endParagraph() {
            if (current.isNotBlank()) paragraphs += current.toString().trim()
            current.clear()
        }
        lines.forEach { line ->
            when {
                line.isEmpty() -> endParagraph()
                LocalTextChapters.headingTitle(line) != null -> {
                    endParagraph()
                    paragraphs += line
                }
                else -> {
                    appendLine(current, line)
                    if (line.length < typical * SHORT_LINE_RATIO && sentenceEnd.containsMatchIn(line)) endParagraph()
                }
            }
        }
        endParagraph()
        return paragraphs.joinToString("\n\n")
    }

    private fun appendLine(
        current: StringBuilder,
        line: String,
    ) {
        val hyphenated = current.length >= 2 && current.last() == '-' && current[current.length - 2].isLetter()
        when {
            current.isEmpty() -> current.append(line)
            hyphenated && line.first().isLowerCase() -> {
                current.setLength(current.length - 1)
                current.append(line)
            }
            else -> current.append(' ').append(line)
        }
    }

    /**
     * A page without its page numbers and running header or footer. Only the lines at a page's top
     * and bottom can be furniture: the same words (or a line that is just "1945") inside the text stay.
     */
    private fun withoutFurniture(
        page: List<String>,
        running: Set<String>,
    ): List<String> {
        val lines = page.toMutableList()
        // A page can open with a header and a number, and close with a footer and a number.
        repeat(EDGE_LINES) {
            val first = lines.indexOfFirst { it.isNotEmpty() }
            if (first >= 0 && isFurniture(lines[first], running)) lines.removeAt(first)
            val last = lines.indexOfLast { it.isNotEmpty() }
            if (last >= 0 && isFurniture(lines[last], running)) lines.removeAt(last)
        }
        return lines
    }

    private fun isFurniture(
        line: String,
        running: Set<String>,
    ): Boolean = pageNumber.matches(line) || line.length <= MAX_RUNNING_LINE_CHARS && runningKey(line) in running

    /** Short lines that open or close many pages (a book title, an author), compared without their digits. */
    private fun runningLines(pages: List<List<String>>): Set<String> {
        if (pages.size < MIN_PAGES_FOR_RUNNING_LINES) return emptySet()
        val edges =
            pages.flatMap { page ->
                val content = page.filter { it.isNotEmpty() && !pageNumber.matches(it) }
                listOfNotNull(content.firstOrNull(), content.lastOrNull())
                    .filter { it.length <= MAX_RUNNING_LINE_CHARS && LocalTextChapters.headingTitle(it) == null }
                    .distinct()
                    .map(::runningKey)
            }
        return edges
            .groupingBy { it }
            .eachCount()
            .filter { (key, count) -> key.isNotBlank() && count >= pages.size * RUNNING_LINE_SHARE }
            .keys
    }

    private fun runningKey(line: String): String = line.replace(Regex("\\d+"), "#").lowercase()

    private fun typicalLength(lines: List<String>): Double {
        val lengths = lines.filter { it.isNotEmpty() }.map { it.length }.sorted()
        if (lengths.isEmpty()) return 0.0
        return lengths[((lengths.size - 1) * LINE_LENGTH_PERCENTILE).toInt()].toDouble()
    }
}
