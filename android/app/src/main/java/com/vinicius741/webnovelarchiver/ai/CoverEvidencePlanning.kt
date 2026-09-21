package com.vinicius741.webnovelarchiver.ai

import com.google.gson.JsonObject
import com.vinicius741.webnovelarchiver.domain.model.Story
import java.security.MessageDigest

/** Pure passage boundaries, cache identities and complementary evidence selection. */
internal object CoverEvidencePlanning {
    const val MODEL = "jev-1.13.0"
    const val PASSAGE_CHARS = 4_000
    const val CONTEXT_CHARS = 24_000
    private const val OVERLAP_CHARS = 400
    private const val MAX_PASSAGES = 12

    data class Passage(
        val chapter: Int,
        val title: String,
        val offset: Int,
        val text: String,
    )

    data class Judgments(
        val appearance: Double,
        val premise: Double,
        val imagery: Double,
        val temporary: Double,
    ) {
        fun dimensions(): List<Double> = listOf(appearance, premise, imagery)
    }

    data class Candidate(
        val passage: Passage,
        val judgments: Judgments,
    )

    fun passages(
        chapter: Int,
        title: String,
        text: String,
    ): List<Passage> {
        val result = mutableListOf<Passage>()
        var start = 0
        while (start < text.length) {
            val limit = minOf(start + PASSAGE_CHARS, text.length)
            val boundary = text.lastIndexOf("\n\n", limit)
            val end = if (limit < text.length && boundary > start + PASSAGE_CHARS / 2) boundary else limit
            val part = text.substring(start, end).trim()
            if (part.isNotBlank()) {
                result +=
                    Passage(chapter, title, start + text.substring(start, end).indexOfFirst { !it.isWhitespace() }, part)
            }
            if (end == text.length) break
            start = maxOf(start + 1, end - OVERLAP_CHARS)
        }
        return result
    }

    fun state(
        story: Story,
        passage: Passage,
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
            addProperty("chapter_position", passage.chapter)
            addProperty("chapter_title", passage.title.take(500))
            addProperty("passage", passage.text)
        }

    fun cacheKey(request: JsonObject): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(request.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Keep contenders for each dimension and book quarter so a long novel stays memory bounded. */
    fun shortlist(
        candidates: List<Candidate>,
        chapterCount: Int,
    ): List<Candidate> =
        candidates
            .groupBy { minOf(3, (it.passage.chapter - 1) * 4 / maxOf(1, chapterCount)) }
            .values
            .flatMap { group ->
                (0..2).flatMap { dimension ->
                    group.sortedByDescending { it.judgments.dimensions()[dimension] }.take(12)
                }
            }.distinctBy { it.passage.chapter to it.passage.offset }

    fun select(candidates: List<Candidate>): List<AiDescriptionPlanning.ChapterText> {
        val remaining = candidates.distinctBy { it.passage.text }.toMutableList()
        val selected = mutableListOf<Candidate>()
        var budget = CONTEXT_CHARS
        while (remaining.isNotEmpty() && selected.size < MAX_PASSAGES) {
            val eligible = remaining.filter { excerpt(it.passage).length <= budget }
            val best = eligible.maxByOrNull { utility(it, selected) } ?: break
            if (utility(best, selected) <= 0.0) break
            selected += best
            remaining.remove(best)
            budget -= excerpt(best.passage).length
        }
        return selected.sortedWith(compareBy({ it.passage.chapter }, { it.passage.offset })).map {
            AiDescriptionPlanning.ChapterText(
                number = it.passage.chapter,
                title = it.passage.title,
                text = excerpt(it.passage),
            )
        }
    }

    private fun excerpt(passage: Passage): String =
        "[Passage at character ${passage.offset + 1}; surrounding text omitted]\n${passage.text}"

    private fun utility(
        candidate: Candidate,
        selected: List<Candidate>,
    ): Double {
        val scores = candidate.judgments.dimensions()
        val coverage = (0..2).map { dim -> selected.sumOf { it.judgments.dimensions()[dim] } }
        val value = scores.indices.sumOf { scores[it] / (1.0 + coverage[it]) }
        val repeated = selected.maxOfOrNull { similarity(candidate.passage.text, it.passage.text) } ?: 0.0
        val sameChapter = selected.count { it.passage.chapter == candidate.passage.chapter }
        return value * (1.0 - repeated) * (1.0 - 0.5 * candidate.judgments.temporary) / (1 + sameChapter)
    }

    private fun similarity(
        first: String,
        second: String,
    ): Double {
        fun words(text: String) =
            text
                .lowercase()
                .split(Regex("\\W+"))
                .filter { it.length > 3 }
                .toSet()
        val a = words(first)
        val b = words(second)
        return if (a.isEmpty() || b.isEmpty()) 0.0 else a.intersect(b).size.toDouble() / minOf(a.size, b.size)
    }
}
