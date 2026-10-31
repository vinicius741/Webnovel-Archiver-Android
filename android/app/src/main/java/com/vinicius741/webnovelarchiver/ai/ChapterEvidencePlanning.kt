package com.vinicius741.webnovelarchiver.ai

import com.google.gson.JsonObject
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.sha256Hex

/**
 * What a decision-model chapter scan looks for. Each rubric owns its questions, its verdict on one
 * answer, and the order chapters are judged in; the selector owns batching, caching and billing.
 */
internal interface EvidenceRubric {
    /** Usage-ledger feature id for this rubric's decision requests. */
    val usageFeature: String

    /** Progress noun, e.g. "cover evidence". */
    val progressSubject: String

    /** Error shown when every scanned chapter was readable but none qualified. */
    val noEvidenceMessage: String

    /** Positions (into the downloaded-chapter list) to judge, in order; may stop short of [count]. */
    fun scanOrder(count: Int): List<Int>

    /** Chapters judged before the scan may stop, even once the target is met; capped by the scan order. */
    val minScannedChapters: Int get() = 0

    /** What the decision model sees of one chapter; null when there is nothing to judge. */
    fun sample(
        chapter: Int,
        title: String,
        text: String,
    ): ChapterEvidencePlanning.ChapterSample? = ChapterEvidencePlanning.sample(chapter, title, text)

    fun request(
        story: Story,
        sample: ChapterEvidencePlanning.ChapterSample,
        model: String,
    ): JsonObject

    /** Throws [java.io.IOException] when the response lacks a valid answer for any question. */
    fun assess(
        sample: ChapterEvidencePlanning.ChapterSample,
        response: JsonObject,
    ): ChapterEvidencePlanning.Assessment
}

/** Rubric-independent chapter sampling, cache identity and final choice for decision-model selection. */
internal object ChapterEvidencePlanning {
    private const val SAMPLE_CHARS = 2_200

    /** Normalized level 2 of 0..3: at least one specific detail. */
    const val SPECIFIC_EVIDENCE = 2.0 / 3.0

    /**
     * Two excerpts per chapter — the opening and a mid-chapter window — keep one request per chapter.
     * Passage-level rubrics send the whole chapter as [passages] instead and leave the excerpts empty.
     */
    data class ChapterSample(
        val chapter: Int,
        val title: String,
        val opening: String,
        val middle: String,
        val passages: List<String> = emptyList(),
    )

    /** One verbatim passage the decision model flagged; [index] orders passages within a chapter. */
    data class EvidencePassage(
        val chapter: Int,
        val index: Int,
        val text: String,
        val probability: Double,
    )

    data class Assessment(
        val useful: Boolean,
        val utility: Double,
        val passages: List<EvidencePassage> = emptyList(),
    )

    /** Chosen chapter texts plus every flagged passage from all judged chapters, chosen or not. */
    data class Selection(
        val chapters: List<AiDescriptionPlanning.ChapterText>,
        val passages: List<EvidencePassage>,
    )

    data class ScoredChapter(
        val sample: ChapterSample,
        val assessment: Assessment,
    )

    /**
     * Book-spanning order: the opening first (it usually introduces the protagonist), then interval
     * midpoints breadth-first, so every early-stopping batch already spans the whole book instead
     * of only its first chapters.
     */
    fun spreadOrder(count: Int): List<Int> {
        if (count <= 0) return emptyList()
        val order = mutableListOf(0)
        val intervals = ArrayDeque(listOf(1 until count))
        while (intervals.isNotEmpty()) {
            val range = intervals.removeFirst()
            if (range.isEmpty()) continue
            val middle = (range.first + range.last + 1) / 2
            order += middle
            intervals += range.first until middle
            intervals += middle + 1..range.last
        }
        return order
    }

    /**
     * The first [opening] chapters in reading order — where characters are usually introduced and
     * described — then [spreadOrder] over the rest of the book.
     */
    fun openingThenSpread(
        count: Int,
        opening: Int,
    ): List<Int> {
        val head = minOf(maxOf(opening, 0), maxOf(count, 0))
        return (0 until head).toList() + spreadOrder(count - head).map { it + head }
    }

    /**
     * Greedily packs a chapter's lines into passages of at most [maxChars] (a longer single line is
     * hard-split), so a passage is a few paragraphs a decision model can judge on its own.
     */
    fun passages(
        text: String,
        maxChars: Int,
    ): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            if (current.isNotBlank()) result += current.toString().trim()
            current.clear()
        }
        text
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .flatMap { it.chunked(maxChars) }
            .forEach { line ->
                if (current.isNotEmpty() && current.length + 1 + line.length > maxChars) flush()
                if (current.isNotEmpty()) current.append('\n')
                current.append(line)
            }
        flush()
        return result
    }

    fun sample(
        chapter: Int,
        title: String,
        text: String,
    ): ChapterSample? {
        if (text.isBlank()) return null
        if (text.length <= SAMPLE_CHARS) return ChapterSample(chapter, title, text.trim(), "")
        // Chapters shorter than two full windows still have a useful remainder after the opening.
        val middleStart = maxOf(SAMPLE_CHARS, (text.length - SAMPLE_CHARS) / 2)
        return ChapterSample(chapter, title, excerpt(text, 0).trim(), excerpt(text, middleStart).trim())
    }

    private fun excerpt(
        text: String,
        start: Int,
    ): String {
        val limit = minOf(start + SAMPLE_CHARS, text.length)
        val boundary = text.lastIndexOf("\n\n", limit)
        val end = if (limit < text.length && boundary > start + SAMPLE_CHARS / 2) boundary else limit
        return text.substring(start, end)
    }

    /** Shared state shape: background metadata plus the chapter's excerpts. */
    fun state(
        story: Story,
        description: String?,
        sample: ChapterSample,
    ): JsonObject =
        JsonObject().apply {
            addProperty("title", story.title.take(500))
            addProperty("description", description?.take(4_000))
            addProperty(
                "tags",
                story.tags
                    .orEmpty()
                    .joinToString(", ")
                    .take(1_000),
            )
            addProperty("chapter_position", sample.chapter)
            addProperty("chapter_title", sample.title.take(500))
            addProperty("opening", sample.opening)
            if (sample.middle.isNotEmpty()) addProperty("middle", sample.middle)
        }

    fun cacheKey(request: JsonObject): String = sha256Hex(request.toString())

    fun choose(
        useful: List<ScoredChapter>,
        target: Int,
    ): List<ScoredChapter> =
        useful
            .sortedByDescending { it.assessment.utility }
            .take(target)
            .sortedBy { it.sample.chapter }
}
