package com.vinicius741.webnovelarchiver.cleanup

import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule
import com.vinicius741.webnovelarchiver.tts.TtsEngine
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.text.BreakIterator
import java.util.Locale

/**
 * TTS text preparation consumed by the TTS engine and the reader (Maintainability M1: split out of
 * TextCleanup.kt). Produces both the flat chunk list the engine speaks and the chunk-tagged HTML the
 * reader highlights. Kept under `cleanup/` (rather than `tts/`) so it shares the package-private
 * [TextCleanup.regexRunner] with the rest of the cleanup domain. [TextCleanup] re-exposes these for
 * callers that still go through the stateless entry points.
 */
object TtsTextPreparation {
    private const val MIN_CHUNK_SIZE = 100
    private const val MAX_CHUNK_SIZE = 800

    // `chunkSize` stays in the facade for caller/storage compatibility; the chunking policy is internal.
    @Suppress("UNUSED_PARAMETER")
    fun prepareTtsChunks(
        html: String,
        regexRules: List<RegexCleanupRule>,
        chunkSize: Int,
    ): List<String> {
        if (html.isBlank()) return emptyList()

        val doc = Jsoup.parseBodyFragment(html)
        return planChunks(buildUnits(doc, regexRules, contributingElements(doc))).chunks
    }

    /** Result of [prepareTtsAnnotatedHtml]: the chunk-tagged HTML plus the aligned chunk list. */
    data class TtsAnnotatedHtml(
        val annotatedHtml: String,
        val chunks: List<String>,
    )

    /**
     * Produces TTS-aware reader HTML (parity gap 3): a port of the legacy RN `prepareTTSContent`.
     *
     * Walks the same block elements as [prepareTtsChunks] using the identical grouping rule, and the
     * returned [chunks] list is byte-for-byte aligned with [prepareTtsChunks]'s output — meaning a
     * chunk index the engine reports (e.g. via [TtsEngine.playFromChunk] / the playback snapshot)
     * maps 1:1 to the `data-tts-group` the reader's highlight script highlights.
     *
     * Because each chunk is a single sentence, the reader can highlight exactly the sentence being
     * spoken: a single-sentence block element is tagged directly (its inline formatting is kept),
     * while a multi-sentence element is rebuilt as per-sentence `<span data-tts-group>` children so
     * each sentence highlights independently (inline formatting within it is dropped).
     *
     * When no block elements match, falls back to escaped inline spans with matching group indices,
     * so plain-text fragments still highlight and support tap-to-start.
     */
    @Suppress("UNUSED_PARAMETER")
    fun prepareTtsAnnotatedHtml(
        html: String,
        regexRules: List<RegexCleanupRule>,
        chunkSize: Int,
    ): TtsAnnotatedHtml {
        if (html.isBlank()) return TtsAnnotatedHtml("", emptyList())

        val doc = Jsoup.parseBodyFragment(html)
        val elements = contributingElements(doc)
        val units = buildUnits(doc, regexRules, elements)
        val plan = planChunks(units)
        // Collect the chunk group(s) each contributing element owns. With one-sentence-per-chunk, a
        // multi-sentence element owns several groups.
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
                    // Single sentence in this element: tag the element directly and keep its inline
                    // formatting (bold/italic/links). Highlighting tints the whole element.
                    val group = groups.first()
                    element
                        .attr("data-tts-group", group.toString())
                        .attr("data-tts-groups", group.toString())
                        .addClass("tts-chunk")
                } else if (groups.all { displayTextByGroup[it] != null }) {
                    // Multiple sentences: rebuild the element as per-sentence spans so the reader can
                    // highlight each sentence independently. The element's tag is preserved, but its
                    // inline formatting is dropped (each span holds plain display text, not the
                    // TTS-cleaned spoken chunk).
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
                    // If TTS-only cleanup changes the sentence structure, preserve the original
                    // reader HTML rather than rewriting visible prose from spoken chunks.
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
    )

    private data class ChunkPlan(
        val chunks: List<String>,
        val unitGroups: List<Int>,
    )

    private fun contributingElements(doc: org.jsoup.nodes.Document): List<Element> {
        doc.select("script,style,noscript,iframe").remove()
        // A container (`div`/`blockquote`/`li`) that itself holds block-level prose children would
        // contribute duplicated text — its `.text()` flattens the same descendants the inner elements
        // already cover. Drop such containers so only the innermost elements contribute; this also
        // keeps the per-sentence rebuild below from `empty()`ing a parent and detaching those inner
        // nodes, whose `data-tts-group` would then never reach the rendered reader HTML.
        val containers = setOf("div", "blockquote", "li")
        return doc
            .select("p,h1,h2,h3,h4,h5,h6,li,blockquote,div")
            .filterNot { element ->
                element.tagName().lowercase(Locale.ROOT) in containers &&
                    element.select("> p, > div, > h1, > h2, > h3, > h4, > h5, > h6, > ul, > ol, > blockquote").isNotEmpty()
            }
    }

    private fun buildUnits(
        doc: org.jsoup.nodes.Document,
        regexRules: List<RegexCleanupRule>,
        elements: List<Element>,
    ): List<TtsUnit> {
        val cleanupForDisplay = TextCleanup.regexRunner(regexRules, "download")
        val cleanupForTts = TextCleanup.regexRunner(regexRules, "tts")
        val units = mutableListOf<TtsUnit>()

        elements.forEachIndexed { index, element ->
            val displayText = normalize(cleanupForDisplay(element.text()))
            if (displayText.isBlank()) return@forEachIndexed

            val ttsText = normalize(cleanupForTts(displayText))
            if (ttsText.isBlank()) return@forEachIndexed

            val spokenSentences = splitIntoChunkableSentences(ttsText)
            val displaySentences = splitIntoChunkableSentences(displayText)
            val displaySentencesAlign = spokenSentences.size == displaySentences.size
            spokenSentences.forEachIndexed { sentenceIndex, sentence ->
                units +=
                    TtsUnit(
                        text = sentence,
                        displayText = if (displaySentencesAlign) displaySentences[sentenceIndex] else null,
                        elementIndex = index,
                    )
            }
        }

        if (units.isNotEmpty()) return units

        val fallback = normalize(cleanupForTts(cleanupForDisplay(doc.body().text())))
        return splitIntoChunkableSentences(fallback).map {
            TtsUnit(text = it, displayText = it, elementIndex = null)
        }
    }

    /**
     * One sentence per chunk. Each [TtsUnit] built by [buildUnits] is already a single sentence, so a
     * chunk maps 1:1 to a sentence — the reader can then highlight exactly the sentence being spoken.
     */
    private fun planChunks(units: List<TtsUnit>): ChunkPlan {
        if (units.isEmpty()) return ChunkPlan(emptyList(), emptyList())
        val chunks = units.map { it.text }
        val unitGroups = units.indices.toList()
        return ChunkPlan(chunks, unitGroups)
    }

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

    private fun splitIntoChunkableSentences(text: String): List<String> =
        splitSentences(text).flatMap { sentence ->
            if (sentence.length <= MAX_CHUNK_SIZE) {
                listOf(sentence)
            } else {
                splitLongSentence(sentence)
            }
        }

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

private fun normalize(text: String): String = text.replace(Regex("\\s+"), " ").trim()
