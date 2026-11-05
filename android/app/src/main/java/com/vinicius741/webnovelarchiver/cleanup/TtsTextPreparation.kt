package com.vinicius741.webnovelarchiver.cleanup

import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule
import com.vinicius741.webnovelarchiver.tts.TtsEngine
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.text.BreakIterator
import java.util.Locale

/**
 * TTS text preparation: the flat chunk list the engine speaks and the chunk-tagged HTML the reader
 * highlights. Kept under `cleanup/` to share [RegexRuleCleanup.regexRunner].
 */
object TtsTextPreparation {
    /** Bumped when sentence numbering changes; saved positions older than this get remapped. */
    const val CHUNKING_VERSION = 2

    private val HEADING_TAGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
    private const val SCENE_BREAK_MARKS = "*~=_#•·◆◇★☆+-—–"

    fun prepareTtsChunks(
        html: String,
        regexRules: List<RegexCleanupRule>,
    ): List<String> = prepareTtsNarration(html, regexRules).map { it.text }

    /** The spoken chunks plus what kind of break follows each, aligned 1:1 with [prepareTtsChunks]. */
    fun prepareTtsNarration(
        html: String,
        regexRules: List<RegexCleanupRule>,
    ): List<TtsNarrationChunk> {
        if (html.isBlank()) return emptyList()

        val doc = Jsoup.parseBodyFragment(html)
        LooseHtmlStructure.wrapBreakSeparatedParagraphs(doc.body())
        return buildUnits(doc, regexRules, contributingElements(doc)).map { TtsNarrationChunk(it.text, it.boundary) }
    }

    /** The numbering used before [CHUNKING_VERSION] 2, only for remapping positions saved back then. */
    fun legacyChunkTexts(
        html: String,
        regexRules: List<RegexCleanupRule>,
    ): List<String> {
        if (html.isBlank()) return emptyList()

        val doc = Jsoup.parseBodyFragment(html)
        LooseHtmlStructure.wrapBreakSeparatedParagraphs(doc.body())
        return buildUnits(doc, regexRules, contributingElements(doc), legacy = true).map { it.text }
    }

    /** Result of [prepareTtsAnnotatedHtml]: the chunk-tagged HTML plus the aligned chunk list. */
    data class TtsAnnotatedHtml(
        val annotatedHtml: String,
        val chunks: List<String>,
    )

    /**
     * Produces TTS-aware reader HTML; [chunks] aligns 1:1 with [prepareTtsChunks], so an engine
     * chunk index maps directly to a `data-tts-group`. Single-sentence elements are tagged in place
     * (inline formatting kept); multi-sentence ones become per-sentence spans (formatting dropped).
     */
    fun prepareTtsAnnotatedHtml(
        html: String,
        regexRules: List<RegexCleanupRule>,
    ): TtsAnnotatedHtml {
        if (html.isBlank()) return TtsAnnotatedHtml("", emptyList())

        val doc = Jsoup.parseBodyFragment(html)
        LooseHtmlStructure.wrapBreakSeparatedParagraphs(doc.body())
        val elements = contributingElements(doc)
        val units = buildUnits(doc, regexRules, elements)
        val plan = planChunks(units)
        // Collect the chunk groups each element owns; multi-sentence elements own several.
        val groupsByElement = linkedMapOf<Int, MutableList<Int>>()
        units.forEachIndexed { unitIndex, unit ->
            val elementIndex = unit.elementIndex ?: return@forEachIndexed
            groupsByElement.getOrPut(elementIndex) { mutableListOf() }.add(plan.unitGroups[unitIndex])
        }
        val displayTextByGroup = mutableMapOf<Int, String>()
        units.forEachIndexed { unitIndex, unit ->
            unit.displayText?.let { displayTextByGroup[plan.unitGroups[unitIndex]] = it }
        }

        if (groupsByElement.isNotEmpty()) {
            groupsByElement.forEach { (elementIndex, groups) ->
                val element = elements[elementIndex]
                if (groups.size == 1) {
                    // Single sentence: tag the element directly, keeping inline formatting.
                    val group = groups.first()
                    element
                        .attr("data-tts-group", group.toString())
                        .attr("data-tts-groups", group.toString())
                        .addClass("tts-chunk")
                } else if (groups.all { displayTextByGroup[it] != null }) {
                    // Multiple sentences: per-sentence spans; inline formatting is dropped.
                    element.empty()
                    groups.forEachIndexed { position, group ->
                        element
                            .appendElement("span")
                            .attr("data-tts-group", group.toString())
                            .attr("data-tts-groups", group.toString())
                            .addClass("tts-chunk")
                            .text(displayTextByGroup.getValue(group))
                        if (position < groups.lastIndex) {
                            element.appendText(" ")
                        }
                    }
                } else {
                    // Sentence mismatch: keep original HTML; never rewrite visible prose from spoken chunks.
                    element
                        .attr("data-tts-groups", groups.joinToString(" "))
                        .addClass("tts-chunk")
                }
            }
            return TtsAnnotatedHtml(doc.body().html(), plan.chunks)
        }

        return if (plan.chunks.isEmpty()) {
            TtsAnnotatedHtml(doc.body().html(), emptyList())
        } else {
            val fallbackDoc = Jsoup.parseBodyFragment("")
            plan.chunks.forEachIndexed { index, chunk ->
                fallbackDoc
                    .body()
                    .appendElement("span")
                    .attr("data-tts-group", index.toString())
                    .attr("data-tts-groups", index.toString())
                    .addClass("tts-chunk")
                    .text(chunk)
                if (index < plan.chunks.lastIndex) {
                    fallbackDoc.body().appendText(" ")
                }
            }
            TtsAnnotatedHtml(fallbackDoc.body().html(), plan.chunks)
        }
    }

    private data class TtsUnit(
        val text: String,
        val displayText: String?,
        val elementIndex: Int?,
        val boundary: TtsChunkBoundary = TtsChunkBoundary.SENTENCE,
    )

    private data class ChunkPlan(
        val chunks: List<String>,
        val unitGroups: List<Int>,
    )

    private fun contributingElements(doc: org.jsoup.nodes.Document): List<Element> {
        doc.select("script,style,noscript,iframe").remove()
        // Drop containers with block children: their .text() duplicates the inner elements, and
        // rebuilding them would detach the inner nodes' data-tts-group from the rendered HTML.
        val containers = setOf("div", "blockquote", "li")
        return doc
            .select("p,h1,h2,h3,h4,h5,h6,li,blockquote,div,hr")
            .filterNot { element ->
                element.tagName().lowercase(Locale.ROOT) in containers &&
                    element.select("> p, > div, > h1, > h2, > h3, > h4, > h5, > h6, > ul, > ol, > blockquote").isNotEmpty()
            }
    }

    private fun buildUnits(
        doc: org.jsoup.nodes.Document,
        regexRules: List<RegexCleanupRule>,
        elements: List<Element>,
        legacy: Boolean = false,
    ): List<TtsUnit> {
        val cleanupForDisplay = RegexRuleCleanup.regexRunner(regexRules, "download")
        val cleanupForTts = RegexRuleCleanup.regexRunner(regexRules, "tts")
        val units = mutableListOf<TtsUnit>()

        elements.forEachIndexed { index, element ->
            if (!legacy && element.tagName().equals("hr", ignoreCase = true)) {
                markLastUnitAsSceneEnd(units)
                return@forEachIndexed
            }
            val displayText = normalize(cleanupForDisplay(element.text()))
            if (displayText.isBlank()) return@forEachIndexed

            val ttsText = normalize(cleanupForTts(displayText))
            if (ttsText.isBlank()) return@forEachIndexed
            if (!legacy && isSceneBreakText(ttsText)) {
                markLastUnitAsSceneEnd(units)
                return@forEachIndexed
            }

            val spokenSentences = TtsSentenceSplitting.splitIntoChunkableSentences(ttsText, legacy)
            val displaySentences = TtsSentenceSplitting.splitIntoChunkableSentences(displayText, legacy)
            val displaySentencesAlign = spokenSentences.size == displaySentences.size
            val endBoundary = boundaryAfterElement(element)
            spokenSentences.forEachIndexed { sentenceIndex, sentence ->
                units +=
                    TtsUnit(
                        text = sentence,
                        displayText = if (displaySentencesAlign) displaySentences[sentenceIndex] else null,
                        elementIndex = index,
                        boundary = if (sentenceIndex == spokenSentences.lastIndex) endBoundary else TtsChunkBoundary.SENTENCE,
                    )
            }
        }

        if (units.isNotEmpty()) return units

        val fallback = normalize(cleanupForTts(cleanupForDisplay(doc.body().text())))
        return TtsSentenceSplitting.splitIntoChunkableSentences(fallback, legacy).map {
            TtsUnit(text = it, displayText = it, elementIndex = null)
        }
    }

    private fun markLastUnitAsSceneEnd(units: MutableList<TtsUnit>) {
        if (units.isNotEmpty()) units[units.lastIndex] = units.last().copy(boundary = TtsChunkBoundary.SCENE)
    }

    private fun boundaryAfterElement(element: Element): TtsChunkBoundary {
        val tag = element.tagName().lowercase(Locale.ROOT)
        return when {
            tag in HEADING_TAGS -> TtsChunkBoundary.SCENE
            tag == "li" -> TtsChunkBoundary.SENTENCE
            else -> TtsChunkBoundary.PARAGRAPH
        }
    }

    /** A line like `* * *` or `-----`: it marks a scene change and has nothing to speak. */
    private fun isSceneBreakText(text: String): Boolean = !TtsSentenceSplitting.isSpeakable(text) && text.any { it in SCENE_BREAK_MARKS }

    /** One sentence per chunk, so the reader can highlight exactly the sentence being spoken. */
    private fun planChunks(units: List<TtsUnit>): ChunkPlan {
        if (units.isEmpty()) return ChunkPlan(emptyList(), emptyList())
        val chunks = units.map { it.text }
        val unitGroups = units.indices.toList()
        return ChunkPlan(chunks, unitGroups)
    }
}

/** Breaks text into the sentences narration speaks, one per chunk. */
private object TtsSentenceSplitting {
    private const val MIN_CHUNK_SIZE = 100
    private const val MAX_CHUNK_SIZE = 800
    private val NON_TERMINAL_ABBREVIATION =
        Regex("""(^|[\s"“‘'(])(Mr|Mrs|Ms|Mx|Dr|Prof|St|Sr|Jr|Lt|Capt|Sgt|Rev|Hon|Lv|Lvl|Vol|vs)\.$""")

    fun isSpeakable(text: String): Boolean = text.any { it.isLetterOrDigit() }

    private fun splitSentences(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val iterator = BreakIterator.getSentenceInstance(Locale.getDefault())
        iterator.setText(text)
        val sentences = mutableListOf<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val sentence = normalize(text.substring(start, end))
            if (sentence.isNotBlank()) sentences += sentence
            start = end
            end = iterator.next()
        }
        return sentences.ifEmpty { listOf(text) }
    }

    fun splitIntoChunkableSentences(
        text: String,
        legacy: Boolean = false,
    ): List<String> {
        val sentences = splitSentences(text)
        val joined = if (legacy) sentences else mergeSentenceFragments(sentences).filter(::isSpeakable)
        return joined.flatMap { sentence ->
            if (sentence.length <= MAX_CHUNK_SIZE) {
                listOf(sentence)
            } else {
                splitLongSentence(sentence)
            }
        }
    }

    /**
     * Rejoins what the sentence breaker wrongly split, each piece of which would otherwise get its
     * own closing intonation: `"Stop!"` + `the guard shouted.` and `Dr.` + `Venn's seal was red.`
     */
    private fun mergeSentenceFragments(sentences: List<String>): List<String> {
        val merged = mutableListOf<String>()
        sentences.forEach { sentence ->
            val previous = merged.lastOrNull()
            if (previous != null && (continuesPreviousSentence(sentence) || NON_TERMINAL_ABBREVIATION.containsMatchIn(previous))) {
                merged[merged.lastIndex] = "$previous $sentence"
            } else {
                merged += sentence
            }
        }
        return merged
    }

    private fun continuesPreviousSentence(sentence: String): Boolean =
        sentence.trimStart('"', '“', '”', '\'', '‘', '’', ' ').firstOrNull()?.isLowerCase() == true

    private fun splitLongSentence(text: String): List<String> {
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val remaining = text.length - start
            if (remaining <= MAX_CHUNK_SIZE) {
                chunks += normalize(text.substring(start))
                break
            }

            val end = bestBoundary(text, start, start + MAX_CHUNK_SIZE)
            chunks += normalize(text.substring(start, end))
            start = end
            while (start < text.length && text[start].isWhitespace()) start += 1
        }
        return chunks.filter { it.isNotBlank() }
    }

    private fun bestBoundary(
        text: String,
        start: Int,
        maxEnd: Int,
    ): Int {
        val lowerBound = (start + MIN_CHUNK_SIZE).coerceAtMost(maxEnd)
        var best = -1
        var index = start
        while (index < maxEnd) {
            if (isClauseBoundary(text, index)) {
                val candidate = index + 1
                if (candidate in lowerBound..maxEnd) best = candidate
            }
            index += 1
        }
        if (best > start) return best

        val lastSpace = text.lastIndexOf(' ', maxEnd.coerceAtMost(text.lastIndex))
        if (lastSpace >= lowerBound) return lastSpace

        return maxEnd
    }

    private fun isClauseBoundary(
        text: String,
        index: Int,
    ): Boolean {
        val char = text[index]
        if (char == ';' || char == ':' || char == '\u2014' || char == '\u2013') return true
        if (char != ',') return false

        var next = index + 1
        while (next < text.length && text[next].isWhitespace()) next += 1
        val tail = text.substring(next).lowercase(Locale.ROOT)
        return listOf("and ", "but ", "or ", "so ", "yet ", "for ", "nor ").any { tail.startsWith(it) }
    }
}

/** The break after a spoken chunk, longest last. */
enum class TtsChunkBoundary {
    SENTENCE,
    PARAGRAPH,
    SCENE,
}

data class TtsNarrationChunk(
    val text: String,
    val boundaryAfter: TtsChunkBoundary = TtsChunkBoundary.SENTENCE,
)

private fun normalize(text: String): String = text.replace(Regex("\\s+"), " ").trim()
