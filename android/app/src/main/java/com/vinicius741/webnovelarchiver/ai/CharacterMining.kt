package com.vinicius741.webnovelarchiver.ai

/**
 * Free, on-device groundwork for the Characters tab: finds name-like words in the downloaded
 * chapters and counts how often each character's names appear, so the one paid call that builds
 * the character list reads a short candidate list with snippets instead of the whole book.
 *
 * Chapters are fed one at a time ([Tallies.add], [MentionCounter.add]) so a long novel is never held
 * in memory whole. A candidate is a capitalized word (or a pair of them) that appears mid-sentence,
 * is rarely written in lowercase (which filters terms such as "Mana" vs "mana"), and is not a common
 * capitalized word. Names next to speech verbs ("said Mara", "Ivo asked") rank higher, since places
 * and skills do not talk. The list still holds places, titles and skills; the roster model drops those.
 */
internal object CharacterMining {
    data class ChapterText(
        val chapter: Int,
        val text: String,
    )

    /** Where a snippet comes from: a chapter and the offset of the name in its text. */
    data class Position(
        val chapter: Int,
        val index: Int,
    )

    data class Candidate(
        val name: String,
        val mentions: Int,
        val speech: Int,
        val chapters: Int,
        val firstChapter: Int,
        val lastChapter: Int,
        val positions: List<Position>,
        val snippets: List<Snippet> = emptyList(),
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

    const val MAX_CANDIDATES = 120

    /** Candidates ranked this high get three snippets; the rest get one. */
    const val DETAILED_CANDIDATES = 40
    const val MIN_MENTIONS = 3
    const val SNIPPET_CHARS = 220

    private const val SPEECH_WEIGHT = 2
    private const val MIN_SPEECH = 3
    private const val PAIR_REACH = 40
    private val WORD = Regex("""(?<![\p{L}'’-])\p{Lu}[\p{L}'’-]*\p{Ll}[\p{L}'’-]*""")
    private val LOWERCASE_WORD = Regex("""\b\p{Ll}[\p{L}'’-]*""")
    private val POSSESSIVE = Regex("""['’]s$""")

    /** A lookahead, so "Captain Rhys Hollow" yields both "Captain Rhys" and "Rhys Hollow". */
    private val PAIR = Regex("""(?<![\p{L}'’-])(?=(\p{Lu}[\p{L}'’-]*\p{Ll})\s+(\p{Lu}[\p{L}'’-]*\p{Ll})(?![\p{L}]))""")
    private const val SPEECH_VERBS =
        "said|says|asked|asks|replied|shouted|whispered|muttered|yelled|answered|called|cried|snapped|added|" +
            "continued|sighed|laughed|growled|murmured|exclaimed|demanded|explained|admitted|nodded|smiled|frowned"
    private val SPEECH_AFTER = Regex("""\b(?:$SPEECH_VERBS)\s+(\p{Lu}[\p{L}'’-]*\p{Ll}[\p{L}'’-]*)""")
    private val SPEECH_BEFORE = Regex("""(\p{Lu}[\p{L}'’-]*\p{Ll}[\p{L}'’-]*)\s+(?:$SPEECH_VERBS)\b""")

    /** Per-name counts with constant memory: first, last and one sampled appearance for snippets. */
    private class Tally(
        val firstWord: String? = null,
    ) {
        var total = 0
        var midSentence = 0
        var speech = 0
        var chapters = 0
        var first: Position? = null
        var last: Position? = null
        var sample: Position? = null
    }

    /** Name candidates over chapters fed in reading order. */
    class Tallies {
        private val words = mutableMapOf<String, Tally>()
        private val pairs = mutableMapOf<String, Tally>()
        private val lowercase = mutableMapOf<String, Int>()

        fun add(chapter: ChapterText) {
            val text = chapter.text
            WORD.findAll(text).forEach { match ->
                val word = normalize(match.value)
                if (word.lowercase() in STOPWORDS || word.length < 2) return@forEach
                val tally = words.getOrPut(word) { Tally() }
                seen(tally, chapter.chapter, match.range.first, word)
                if (!sentenceStart(text, match.range.first)) tally.midSentence++
            }
            LOWERCASE_WORD.findAll(text).forEach { match -> lowercase.merge(normalize(match.value), 1, Int::plus) }
            PAIR.findAll(text).forEach { match ->
                val (first, second) = match.destructured
                // "Jake's Perception" is a thing Jake has, not a name.
                if (POSSESSIVE.containsMatchIn(first)) return@forEach
                val head = normalize(first)
                val tail = normalize(second)
                // "Lord Vane" and "Master Chen" are names; "Then Mara" is not.
                if ((head.lowercase() in STOPWORDS && head.lowercase() !in TITLE_WORDS) || tail.lowercase() in STOPWORDS) return@forEach
                val pair = "$head $tail"
                val tally = pairs.getOrPut(pair) { Tally(firstWord = head) }
                seen(tally, chapter.chapter, match.range.first, pair)
                tally.midSentence++
            }
            (SPEECH_AFTER.findAll(text) + SPEECH_BEFORE.findAll(text)).forEach { match ->
                val word = normalize(match.groupValues[1])
                words[word]?.let { it.speech++ }
                // "said Lin Feng" / "Lin Feng said": credit the pair around the word too.
                val before = text.substring(maxOf(0, match.range.first - PAIR_REACH), match.range.first).trimEnd().substringAfterLast(' ')
                val after =
                    text
                        .substring(
                            match.range.last + 1,
                            minOf(text.length, match.range.last + 1 + PAIR_REACH),
                        ).trimStart()
                        .substringBefore(' ')
                pairs["${normalize(before)} $word"]?.let { it.speech++ }
                pairs["$word ${normalize(after.trimEnd(',', '.', '!', '?', ';', ':'))}"]?.let { it.speech++ }
            }
        }

        /** The strongest candidates, without snippets: [withSnippets] reads the chapters they need. */
        fun candidates(): List<Candidate> {
            val single =
                words.filter { (word, tally) ->
                    tally.total >= MIN_MENTIONS &&
                        tally.midSentence >= 2 &&
                        // A name that is also a word ("Will", "Rain") still counts when it often speaks.
                        ((lowercase[word.lowercase()] ?: 0) < tally.midSentence || tally.speech >= MIN_SPEECH)
                }
            // "Did Jason" and "Unlike Jason" start with a word that is mostly lowercase.
            val double =
                pairs.filter { (_, tally) ->
                    tally.total >= MIN_MENTIONS && (lowercase[tally.firstWord.orEmpty().lowercase()] ?: 0) < tally.total
                }
            return (single + double)
                .entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, Tally>> { it.value.total + SPEECH_WEIGHT * it.value.speech }.thenBy { it.key },
                ).take(MAX_CANDIDATES)
                .mapIndexed { rank, (name, tally) ->
                    val all = listOfNotNull(tally.first, tally.sample, tally.last).distinctBy { it.chapter }.sortedBy { it.chapter }
                    Candidate(
                        name = name,
                        mentions = tally.total,
                        speech = tally.speech,
                        chapters = tally.chapters,
                        firstChapter = checkNotNull(tally.first).chapter,
                        lastChapter = checkNotNull(tally.last).chapter,
                        positions = if (rank < DETAILED_CANDIDATES) all else all.take(1),
                    )
                }
        }

        private fun seen(
            tally: Tally,
            chapter: Int,
            index: Int,
            key: String,
        ) {
            tally.total++
            val position = Position(chapter, index)
            if (tally.last?.chapter != chapter) {
                tally.chapters++
                if (tally.first == null) tally.first = position
                // One-item reservoir sample, seeded by the name so runs are repeatable.
                if (Math.floorMod(key.hashCode() * 31 + chapter, tally.chapters) == 0) tally.sample = position
                tally.last = position
            }
        }
    }

    /** All chapters at once; for tests and small inputs. */
    fun candidates(chapters: List<ChapterText>): List<Candidate> {
        val tallies = Tallies()
        chapters.forEach(tallies::add)
        return withSnippets(tallies.candidates(), chapters)
    }

    /** Fills each candidate's snippets from [chapters], which must hold every chapter its positions name. */
    fun withSnippets(
        candidates: List<Candidate>,
        chapters: List<ChapterText>,
    ): List<Candidate> {
        val texts = chapters.associate { it.chapter to it.text }
        return candidates.map { candidate ->
            candidate.copy(
                snippets =
                    candidate.positions.mapNotNull { position ->
                        texts[position.chapter]?.takeIf { position.index < it.length }?.let {
                            Snippet(
                                position.chapter,
                                snippet(it, position.index),
                            )
                        }
                    },
            )
        }
    }

    /**
     * Counts every character's names over chapters fed in order, with one pattern for the whole list:
     * at each place the longest name wins, so "Li Mei" counts for Li Mei and never for a "Li".
     */
    class MentionCounter(
        namesByOwner: Map<String, List<String>>,
    ) {
        private val owners =
            namesByOwner
                .flatMap { (owner, names) -> names.map { normalize(it.trim()) to owner } }
                .filter { it.first.length >= 2 }
                .toMap()
        private val pattern = namePattern(owners.keys.toList())
        private val counts = mutableMapOf<String, MutableMap<Int, Int>>()

        fun add(chapter: ChapterText) {
            val regex = pattern ?: return
            regex.findAll(chapter.text).forEach { match ->
                val owner = owners[normalize(match.value)] ?: return@forEach
                counts.getOrPut(owner) { mutableMapOf() }.merge(chapter.chapter, 1, Int::plus)
            }
        }

        fun result(owner: String): Mentions = Mentions(counts[owner].orEmpty().toMap())
    }

    /** Whole-word counts of one character's [names] per chapter. */
    fun mentions(
        chapters: List<ChapterText>,
        names: List<String>,
    ): Mentions {
        val counter = MentionCounter(mapOf("" to names))
        chapters.forEach(counter::add)
        return counter.result("")
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
    ): Int? = namePattern(names)?.find(text)?.range?.first

    /** Curly and straight apostrophes are the same letter in a name. */
    private fun normalize(word: String): String = stripPossessive(word.replace('’', '\''))

    /** Whole-word pattern for any of [names], longest first, curly and straight apostrophes alike. */
    fun namePattern(names: List<String>): Regex? {
        val alternatives =
            names
                .map { normalize(it.trim()) }
                .filter { it.length >= 2 }
                .distinct()
                .sortedByDescending { it.length }
        if (alternatives.isEmpty()) return null
        val body = alternatives.joinToString("|") { name -> name.split('\'').joinToString("['’]") { Regex.escape(it) } }
        return Regex("""(?<![\p{L}\p{N}])(?:$body)(?![\p{L}\p{N}])""")
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
        while (i >= 0 && text[i] != '\n' && text[i].isWhitespace()) i--
        if (i < 0) return true
        if (text[i] !in SENTENCE_BREAKS) return false
        // '"Hold on," Mara said': a quote closed after a comma does not end the sentence.
        if (text[i] in CLOSING_QUOTES && i > 0 && text[i - 1] == ',') return false
        // "Mr. Darcy": the full stop ends an abbreviation, not a sentence.
        if (text[i] == '.') {
            val wordStart = (i - 1 downTo 0).firstOrNull { !text[it].isLetter() }?.plus(1) ?: 0
            if (text.substring(wordStart, i).lowercase() in HONORIFICS) return false
        }
        return true
    }

    private fun stripPossessive(word: String): String = word.removeSuffix("'s").removeSuffix("’s").trimEnd('\'', '’', '-')

    private const val SENTENCE_BREAKS = ".!?\n\"“”'‘’:;—–-([*"

    private const val CLOSING_QUOTES = "\"”'’"

    /** Honorifics that start a name pair even though they are stopwords alone. */
    private val TITLE_WORDS = setOf("lord", "lady", "master", "sir", "madam", "miss", "mr", "mrs", "ms")

    private val HONORIFICS = setOf("mr", "mrs", "ms", "dr", "st", "prof", "sr", "jr", "lt", "capt", "sgt", "gen", "col")

    private val STOPWORDS =
        (
            "i i'm i'll i'd i've the a an he she it they we you his her hers its their our my your me him them us " +
                "this that these those but and or so then when while what where why how who whom whose which if as at in " +
                "on of for to with from by after before during until since about into over under again yes no oh ah huh " +
                "hmm hey wait well just not now even still there here maybe perhaps please thank thanks sorry okay ok " +
                "let don't can't didn't won't isn't wasn't aren't it's that's there's what's he's she's they're we're " +
                "you're let's mr mrs ms miss sir lady lord madam master chapter part book volume prologue epilogue " +
                "god gods monday tuesday wednesday thursday friday saturday sunday january february march " +
                "july september october november december english status level skill skills class stats " +
                "everyone someone something nothing anything everything one two three four five six seven eight nine ten " +
                "first second third last next another other some any all both each every many much more most few such " +
                "very too also only though although because however therefore instead otherwise meanwhile suddenly " +
                "finally soon once twice never always often sometimes good great right left up down out off did does do " +
                "was were is are am be been had has have could would should shall might must can unlike like"
        ).split(' ').filter { it.isNotBlank() }.toSet()
}
