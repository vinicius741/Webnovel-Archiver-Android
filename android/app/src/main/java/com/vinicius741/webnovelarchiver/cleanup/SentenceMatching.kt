package com.vinicius741.webnovelarchiver.cleanup

/**
 * Case-insensitive literal search for the sentence removal list. Folding keeps every character's
 * length, so folded and original text share indices and a match in one is the same range in the
 * other. Runs of whitespace inside an entry match a single space, since Jsoup's `TextNode.text()`
 * has already collapsed them in the chapter text (an entry with a double space never matched before).
 *
 * On Android, one substring scan per entry over every chapter made the list cost ~10x the rest of
 * cleanup, so [SentenceList] first finds the few entries a chapter could contain in a single pass.
 */
internal object SentenceMatching {
    private val whitespaceRun = Regex("\\s+")

    data class Removal(
        val text: String,
        val removed: Int,
    )

    /** Folds a list entry once per settings change; returns "" for entries that can never match. */
    fun prepare(sentence: String): String = fold(sentence.trim().replace(whitespaceRun, " "))

    /**
     * Removes each prepared sentence from [text] in list order, every occurrence, left to right.
     * Returns null when nothing matched, so callers can leave the text node untouched.
     */
    fun remove(
        text: String,
        sentences: List<String>,
    ): Removal? {
        var current = text
        var folded = fold(text)
        var removed = 0
        sentences.forEach { sentence ->
            var at = folded.indexOf(sentence)
            if (sentence.isEmpty() || at < 0) return@forEach
            val kept = StringBuilder(current.length)
            val keptFolded = StringBuilder(current.length)
            var cursor = 0
            while (at >= 0) {
                kept.append(current, cursor, at)
                keptFolded.append(folded, cursor, at)
                cursor = at + sentence.length
                removed += 1
                at = folded.indexOf(sentence, cursor)
            }
            kept.append(current, cursor, current.length)
            keptFolded.append(folded, cursor, folded.length)
            current = kept.toString()
            folded = keptFolded.toString()
        }
        return if (removed == 0) null else Removal(current, removed)
    }

    /**
     * Simple case folding with the same per-code-point comparison as a Unicode case-insensitive
     * regex. Supplementary letters (Deseret, Osage, Adlam...) fold as whole surrogate pairs.
     */
    fun fold(text: String): String {
        val folded = CharArray(text.length)
        var index = 0
        while (index < text.length) {
            val char = text[index]
            val low = if (index + 1 < text.length) text[index + 1] else ' '
            if (Character.isHighSurrogate(char) && Character.isLowSurrogate(low)) {
                val codePoint = Character.toLowerCase(Character.toUpperCase(Character.toCodePoint(char, low)))
                if (Character.charCount(codePoint) == 2) {
                    Character.toChars(codePoint, folded, index)
                } else {
                    folded[index] = char
                    folded[index + 1] = low
                }
                index += 2
            } else {
                folded[index] = fold(char)
                index += 1
            }
        }
        return String(folded)
    }

    fun fold(char: Char): Char =
        when {
            char in 'A'..'Z' -> char + ('a' - 'A')
            char.code < ASCII_LIMIT -> char
            else -> Character.toLowerCase(Character.toUpperCase(char))
        }

    const val ASCII_LIMIT = 0x80
}

/**
 * The prepared sentence list. Each entry is split into anchors: its runs of printable ASCII other
 * than `& < > " '`. An entry inside a chapter's text has every anchor in the chapter's HTML, so a
 * chapter whose HTML lacks one of them cannot contain that entry. [candidatesIn] checks every
 * anchor of every entry in one pass over the HTML instead of one search per entry. Requiring all
 * anchors matters: an entry's longest word alone ("support", "official") is in most chapters.
 *
 * Two things can hide an anchor in HTML, and the scan allows for both: characters `TextNode.text()`
 * drops ([isDroppedByJsoup]) are skipped, and an entity Jsoup never writes, which could spell an
 * anchor character (`&#112;`), makes every entry a candidate.
 */
class SentenceList(
    sentences: List<String>,
) {
    /**
     * Folded entries in list order, which is the order they are removed in. Duplicates stay: a later
     * copy can match text an earlier entry's removal joined together.
     */
    val entries: List<String> = sentences.map(SentenceMatching::prepare).filter { it.isNotEmpty() }

    /** Indexes into [anchors] per entry; an entry with none is always a candidate. */
    private val anchorsOf: Array<IntArray>
    private val anchors: List<String>

    /** Anchors of length >= 2 chained by their first two characters; see [pairKey]. */
    private val pairHead = IntArray(PAIR_KEYS) { -1 }
    private val pairNext: IntArray

    /** One-character anchors, by character. */
    private val singleAnchor = IntArray(SentenceMatching.ASCII_LIMIT) { -1 }

    init {
        val distinct = LinkedHashMap<String, Int>()
        anchorsOf =
            Array(entries.size) { index ->
                anchors(entries[index]).map { anchor -> distinct.getOrPut(anchor) { distinct.size } }.toIntArray()
            }
        anchors = distinct.keys.toList()
        pairNext = IntArray(anchors.size) { -1 }
        anchors.forEachIndexed { index, anchor ->
            if (anchor.length == 1) {
                singleAnchor[anchor[0].code] = index
            } else {
                val key = pairKey(anchor[0], anchor[1])
                pairNext[index] = pairHead[key]
                pairHead[key] = index
            }
        }
    }

    fun isEmpty(): Boolean = entries.isEmpty()

    /** Entries that may occur in [html]'s text, in list order. An entry left out cannot occur there. */
    fun candidatesIn(html: String): List<String> {
        if (entries.isEmpty()) return emptyList()
        val found = findAnchors(html) ?: return entries
        return entries.filterIndexed { index, _ -> anchorsOf[index].all { found[it] } }
    }

    /**
     * The hot loop of cleanup: locals instead of fields and inlined folding keep it cheap on Android.
     * Returns null when an entity could hide an anchor, so nothing can be ruled out.
     */
    private fun findAnchors(html: String): BooleanArray? {
        val found = BooleanArray(anchors.size)
        val heads = pairHead
        val singles = singleAnchor
        val length = html.length
        var remaining = anchors.size
        var index = visibleFrom(html, 0)
        while (index < length && remaining > 0) {
            val char = html[index]
            if (char == '&' && !isJsoupEntityAt(html, index)) return null
            val following = visibleFrom(html, index + 1)
            val first = foldChar(char)
            if (first.code < SentenceMatching.ASCII_LIMIT) {
                val single = singles[first.code]
                if (single >= 0 && !found[single]) {
                    found[single] = true
                    remaining -= 1
                }
                val second = if (following < length) foldChar(html[following]) else NO_CHAR
                if (second.code < SentenceMatching.ASCII_LIMIT) {
                    remaining -= markAnchorsAt(html, index, heads[pairKey(first, second)], found)
                }
            }
            index = following
        }
        return found
    }

    /** Marks the anchors in the chain from [head] that occur at [start]; returns how many were new. */
    private fun markAnchorsAt(
        html: String,
        start: Int,
        head: Int,
        found: BooleanArray,
    ): Int {
        var marked = 0
        var candidate = head
        while (candidate >= 0) {
            if (!found[candidate] && matchesAt(html, start, anchors[candidate])) {
                found[candidate] = true
                marked += 1
            }
            candidate = pairNext[candidate]
        }
        return marked
    }

    private fun matchesAt(
        html: String,
        start: Int,
        anchor: String,
    ): Boolean {
        var position = start
        for (expected in anchor) {
            position = visibleFrom(html, position)
            if (position >= html.length || foldChar(html[position]) != expected) return false
            position += 1
        }
        return true
    }

    private companion object {
        const val PAIR_KEYS = SentenceMatching.ASCII_LIMIT * SentenceMatching.ASCII_LIMIT

        /** Outside the ASCII range, so it never starts or continues an anchor. */
        const val NO_CHAR = '￿'

        fun pairKey(
            first: Char,
            second: Char,
        ): Int = first.code * SentenceMatching.ASCII_LIMIT + second.code

        /** Entities Jsoup writes in text and attributes; each decodes to a character no anchor holds. */
        val jsoupEntities = listOf("&amp;", "&lt;", "&gt;", "&quot;", "&nbsp;", "&apos;", "&#39;", "&#x27;", "&#xa0;", "&#160;")

        fun isJsoupEntityAt(
            html: String,
            index: Int,
        ): Boolean = jsoupEntities.any { html.startsWith(it, index, ignoreCase = true) }

        /** `TextNode.text()` drops these, joining the text around them (checked against Jsoup 1.23). */
        @Suppress("NOTHING_TO_INLINE")
        inline fun isDroppedByJsoup(char: Char): Boolean = char == '\u200B' || char == '\u00AD' || char == '\u0000'

        @Suppress("NOTHING_TO_INLINE")
        inline fun visibleFrom(
            html: String,
            index: Int,
        ): Int {
            var position = index
            while (position < html.length && isDroppedByJsoup(html[position])) position += 1
            return position
        }

        @Suppress("NOTHING_TO_INLINE")
        inline fun foldChar(char: Char): Char =
            when {
                char in 'A'..'Z' -> char + ('a' - 'A')
                char.code < SentenceMatching.ASCII_LIMIT -> char
                else -> SentenceMatching.fold(char)
            }

        /**
         * Runs of printable ASCII other than `& < > " '`. Serializers write those characters as
         * entities, and non-ASCII may be written as entities too; nothing else is escaped.
         */
        fun anchors(entry: String): List<String> {
            val runs = mutableListOf<String>()
            var runStart = -1
            for (index in 0..entry.length) {
                val safe = index < entry.length && isAnchorChar(entry[index])
                if (safe && runStart < 0) runStart = index
                if (!safe && runStart >= 0) {
                    runs += entry.substring(runStart, index)
                    runStart = -1
                }
            }
            return runs
        }

        fun isAnchorChar(char: Char): Boolean = char in '!'..'~' && char !in "&<>\"'"
    }
}
