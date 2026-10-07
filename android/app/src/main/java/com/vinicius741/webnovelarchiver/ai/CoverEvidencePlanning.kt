package com.vinicius741.webnovelarchiver.ai

import com.google.gson.JsonObject
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.sha256Hex

/** Pure chapter sampling, scan order, cache identities and usefulness rules for decision-model selection. */
internal object CoverEvidencePlanning {
    const val DEFAULT_TARGET_CHAPTERS = 10

    private const val SAMPLE_CHARS = 2_200

    /** Normalized level 2 of 0..3: at least one specific, drawable detail. */
    private const val USEFUL_EVIDENCE = 2.0 / 3.0
    private const val TEMPORARY_LIMIT = 0.5

    /** Two excerpts per chapter — the opening and a mid-chapter window — keep one request per chapter. */
    data class ChapterSample(
        val chapter: Int,
        val title: String,
        val opening: String,
        val middle: String,
    )

    data class Judgments(
        val appearance: Double,
        val premise: Double,
        val imagery: Double,
        val temporary: Double,
    )

    data class ScoredChapter(
        val sample: ChapterSample,
        val judgments: Judgments,
    )

    /**
     * Order in which downloaded chapters are judged: the opening first (it usually introduces the
     * protagonist), then interval midpoints breadth-first, so every early-stopping batch already
     * spans the whole book instead of only its first chapters.
     */
    fun scanOrder(count: Int): List<Int> {
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

    fun state(
        story: Story,
        sample: ChapterSample,
    ): JsonObject =
        JsonObject().apply {
            addProperty("title", story.title.take(500))
            addProperty("description", AiDescriptionPlanning.activeDescription(story)?.take(4_000))
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

    /** A cover needs identifiable visual evidence that is not confined to a temporary scene. */
    fun isUseful(judgments: Judgments): Boolean =
        maxOf(judgments.appearance, judgments.imagery) >= USEFUL_EVIDENCE && judgments.temporary <= TEMPORARY_LIMIT

    fun utility(judgments: Judgments): Double =
        (judgments.appearance + judgments.premise + judgments.imagery) / 3.0 * (1.0 - judgments.temporary)

    fun choose(
        useful: List<ScoredChapter>,
        target: Int,
    ): List<ScoredChapter> =
        useful
            .sortedByDescending { utility(it.judgments) }
            .take(target)
            .sortedBy { it.sample.chapter }
}
