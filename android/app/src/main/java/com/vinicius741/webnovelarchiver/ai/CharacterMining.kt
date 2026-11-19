package com.vinicius741.webnovelarchiver.ai

/**
 * Free, on-device groundwork for the Characters tab: finds name-like words in the downloaded
 * chapters and counts how often a character's names appear, so the one paid call that builds the
 * character list reads a short candidate list with snippets instead of the whole book.
 *
 * A candidate is a capitalized word (or a pair of them) that appears mid-sentence, is rarely
 * written in lowercase (which filters terms such as "Mana" vs "mana"), and is not a common
 * capitalized word. Names next to speech verbs ("said Mara", "Ivo asked") rank higher, since
 * places and skills do not talk. The list still holds places, titles and skills; the roster model
 * drops those.
 */
internal object CharacterMining {
    data class ChapterText(
        val chapter: Int,
        val text: String,
    )

    data class Candidate(
        val name: String,
        val mentions: Int,
        val speech: Int,
        val chapters: Int,
        val firstChapter: Int,
        val lastChapter: Int,
        val snippets: List<Snippet>,
    )

    data class Snippet(
        val chapter: Int,
        val text: String,
    )

    /** How often a character's names appear: in total and per chapter. */
    data class Mentions(
        val perChapter: Map<Int, Int>,
    ) {
        val count: Int get() = perChapter.values.sum()
        val first: Int? get() = perChapter.keys.minOrNull()
        val last: Int? get() = perChapter.keys.maxOrNull()
    }

    const val MAX_CANDIDATES = 50
    const val MIN_MENTIONS = 3
    const val SNIPPETS_PER_CANDIDATE = 3
    const val SNIPPET_CHARS = 220

    private const val SPEECH_WEIGHT = 2
    private val WORD = Regex("""\p{Lu}[\p{L}'’-]*\p{Ll}[\p{L}'’-]*""")

    /** A lookahead, so "Captain Rhys Hollow" yields both "Captain Rhys" and "Rhys Hollow". */
    private val PAIR = Regex("""(?<![\p{L}'’-])(?=(\p{Lu}[\p{L}'’-]*\p{Ll})\s+(\p{Lu}[\p{L}'’-]*\p{Ll})(?![\p{L}]))""")
    private const val SPEECH_VERBS =
        "said|says|asked|asks|replied|shouted|whispered|muttered|yelled|answered|called|cried|snapped|added|" +
            "continued|sighed|laughed|growled|murmured|exclaimed|demanded|explained|admitted|nodded|smiled|frowned"
    private val SPEECH_AFTER = Regex("""\b(?:$SPEECH_VERBS)\s+(\p{Lu}[\p{L}'’-]*\p{Ll}[\p{L}'’-]*)""")
    private val SPEECH_BEFORE = Regex("""(\p{Lu}[\p{L}'’-]*\p{Ll}[\p{L}'’-]*)\s+(?:$SPEECH_VERBS)\b""")

    private class Tally {
        var total = 0
        var midSentence = 0
        var speech = 0

        /** First character offset of the word in each chapter it appears in. */
        val firstIndex = linkedMapOf<Int, Int>()
    }

    fun candidates(chapters: List<ChapterText>): List<Candidate> {
        val words = mutableMapOf<String, Tally>()
        val pairs = mutableMapOf<String, Tally>()
        val lowercase = mutableMapOf<String, Int>()
        for (chapter in chapters) {
            val text = chapter.text
            WORD.findAll(text).forEach { match ->
                val word = stripPossessive(match.value)
                if (word.lowercase() in STOPWORDS || word.length < 2) return@forEach
                val tally = words.getOrPut(word) { Tally() }
                tally.total++
                if (!sentenceStart(text, match.range.first)) tally.midSentence++
                tally.firstIndex.putIfAbsent(chapter.chapter, match.range.first)
            }
            Regex("""\b\p{Ll}[\p{L}'’-]*""").findAll(text).forEach { match ->
                lowercase.merge(match.value, 1, Int::plus)
            }
            PAIR.findAll(text).forEach { match ->
                val (first, second) = match.destructured
                if (first.lowercase() in STOPWORDS || stripPossessive(second).lowercase() in STOPWORDS) return@forEach
                val pair = "$first ${stripPossessive(second)}"
                val tally = pairs.getOrPut(pair) { Tally() }
                tally.total++
                tally.midSentence++
                tally.firstIndex.putIfAbsent(chapter.chapter, match.range.first)
            }
            (SPEECH_AFTER.findAll(text) + SPEECH_BEFORE.findAll(text)).forEach { match ->
                words[stripPossessive(match.groupValues[1])]?.let { it.speech++ }
            }
        }
        val single =
            words.filter { (word, tally) ->
                tally.total >= MIN_MENTIONS &&
                    tally.midSentence >= 2 &&
                    (lowercase[word.lowercase()] ?: 0) < tally.midSentence
            }
        val double = pairs.filter { (_, tally) -> tally.total >= MIN_MENTIONS }
        val texts = chapters.associate { it.chapter to it.text }
        return (single + double)
            .entries
            .sortedByDescending { (_, tally) -> tally.total + SPEECH_WEIGHT * tally.speech }
            .take(MAX_CANDIDATES)
            .map { (name, tally) ->
                val appearances = tally.firstIndex.keys.sorted()
                Candidate(
                    name = name,
                    mentions = tally.total,
                    speech = tally.speech,
                    chapters = appearances.size,
                    firstChapter = appearances.first(),
                    lastChapter = appearances.last(),
                    snippets =
                        spread(appearances, SNIPPETS_PER_CANDIDATE).mapNotNull { chapter ->
                            val text = texts[chapter] ?: return@mapNotNull null
                            Snippet(chapter, snippet(text, tally.firstIndex.getValue(chapter)))
                        },
                )
            }
    }

    /** Whole-word, case-sensitive counts of [names] (a character's name and aliases) per chapter. */
    fun mentions(
        chapters: List<ChapterText>,
        names: List<String>,
    ): Mentions {
        val pattern = pattern(names) ?: return Mentions(emptyMap())
        return Mentions(
            chapters
                .associate { it.chapter to pattern.findAll(it.text).count() }
                .filterValues { it > 0 },
        )
    }

    /**
     * Chapters to look for a character's description in: the first that names them (an entrance
     * usually comes with a description), then the rest by how often they name them, up to [limit].
     */
    fun keyChapters(
        mentions: Mentions,
        limit: Int,
    ): List<Int> {
        val first = mentions.first ?: return emptyList()
        val rest =
            mentions.perChapter.entries
                .filter { it.key != first }
                .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
                .map { it.key }
        return (listOf(first) + rest).take(limit)
    }

    /** First offset in [text] where any of [names] appears, or null. */
    fun firstMention(
        text: String,
        names: List<String>,
    ): Int? = pattern(names)?.find(text)?.range?.first

    private fun pattern(names: List<String>): Regex? {
        val alternatives =
            names
                .map { it.trim() }
                .filter { it.length >= 2 }
                .distinct()
                .sortedByDescending { it.length }
        if (alternatives.isEmpty()) return null
        return Regex("""(?<![\p{L}\p{N}])(?:${alternatives.joinToString("|") { Regex.escape(it) }})(?![\p{L}\p{N}])""")
    }

    /** The first, last and evenly spaced items between them. */
    private fun spread(
        items: List<Int>,
        count: Int,
    ): List<Int> {
        if (items.size <= count) return items
        return (0 until count).map { items[it * (items.size - 1) / (count - 1)] }.distinct()
    }

    /** The sentence around [index], trimmed to about [SNIPPET_CHARS] centred on the name. */
    private fun snippet(
        text: String,
        index: Int,
    ): String {
        val half = SNIPPET_CHARS / 2
        val start =
            maxOf(
                text.lastIndexOfAny(charArrayOf('\n', '.', '!', '?'), index - 1).let { if (it < 0) 0 else it + 1 },
                index - half,
            ).coerceAtLeast(0)
        val boundary = text.indexOfAny(charArrayOf('\n', '.', '!', '?'), index).let { if (it < 0) text.length else it + 1 }
        val end = minOf(boundary, start + SNIPPET_CHARS, text.length)
        return text.substring(start, end).replace(Regex("\\s+"), " ").trim()
    }

    private fun sentenceStart(
        text: String,
        index: Int,
    ): Boolean {
        var i = index - 1
        while (i >= 0 && text[i] == ' ') i--
        return i < 0 || text[i] in SENTENCE_BREAKS
    }

    private fun stripPossessive(word: String): String = word.removeSuffix("'s").removeSuffix("’s").trimEnd('\'', '’', '-')

    private const val SENTENCE_BREAKS = ".!?\n\"“”'‘’:;—–-([*"

    private val STOPWORDS =
        (
            "i i'm i'll i'd i've the a an he she it they we you his her hers its their our my your me him them us " +
                "this that these those but and or so then when while what where why how who whom whose which if as at in " +
                "on of for to with from by after before during until since about into over under again yes no oh ah huh " +
                "hmm hey wait well just not now even still there here maybe perhaps please thank thanks sorry okay ok " +
                "let don't can't didn't won't isn't wasn't aren't it's that's there's what's he's she's they're we're " +
                "you're i'd let's mr mrs ms miss sir lady lord madam master chapter part book volume prologue epilogue " +
                "god gods monday tuesday wednesday thursday friday saturday sunday january february march april may june " +
                "july august september october november december english status level skill skills class stats " +
                "everyone someone something nothing anything everything one two three four five six seven eight nine ten " +
                "first second third last next another other some any all both each every many much more most few such " +
                "very too also only though although because however therefore instead otherwise meanwhile suddenly " +
                "finally soon once twice never always often sometimes good great right left up down out off"
        ).split(' ').filter { it.isNotBlank() }.toSet()
}
