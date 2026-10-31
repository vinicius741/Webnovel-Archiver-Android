package com.vinicius741.webnovelarchiver.ai

import com.vinicius741.webnovelarchiver.domain.model.Story

/** Cover-only scan scope and text budgets, independent of synopsis context and reading progress. */
object AiCoverContextPlanning {
    /**
     * Twice the synopsis budget: the default ten automatic chapters each arrive whole (up to the
     * 12k per-chapter cap) instead of cut to their first 6k characters. ~30k tokens on a cheap model.
     */
    internal const val MAX_TOTAL_CONTEXT_CHARS = 120_000

    /** Verbatim appearance passages sent beside the chapters, strongest first until this is spent. */
    internal const val MAX_APPEARANCE_EVIDENCE_CHARS = 12_000

    fun selectContextChapters(story: Story): List<Int> = story.chapters.indices.filter { story.chapters[it].downloaded }

    fun resolveContextChapters(story: Story): List<Int> {
        val explicit = story.aiCoverContextChapterIndices
        if (explicit.isNullOrEmpty()) return selectContextChapters(story)
        return explicit.distinct().sorted().filter { it in story.chapters.indices && story.chapters[it].downloaded }
    }

    fun contextChaptersLabel(
        story: Story,
        automaticSelection: List<Int>? = null,
    ): String =
        if (story.aiCoverContextChapterIndices.isNullOrEmpty()) {
            automaticSelection?.takeIf { it.isNotEmpty() }?.let { "Automatic: ${it.size} chapters" } ?: "Automatic"
        } else {
            "${resolveContextChapters(story).size} selected"
        }

    internal fun balanceContext(chapters: List<AiDescriptionPlanning.ChapterText>): List<AiDescriptionPlanning.ChapterText> {
        if (chapters.isEmpty()) return emptyList()
        val allowance = minOf(AiDescriptionPlanning.MAX_CHARS_PER_CHAPTER, MAX_TOTAL_CONTEXT_CHARS / chapters.size)
        val marker = "\n[... chapter truncated ...]"
        return chapters.map { chapter ->
            chapter.copy(
                text =
                    when {
                        chapter.text.length <= allowance -> chapter.text
                        allowance <= marker.length -> chapter.text.take(allowance)
                        else -> chapter.text.take(allowance - marker.length) + marker
                    },
            )
        }
    }

    /**
     * Highest-probability passages first until [MAX_APPEARANCE_EVIDENCE_CHARS] is spent, then back in
     * reading order so the prompt writer can follow how the description develops. Exact repeats
     * (recaps, re-posted chapters) keep only their first copy, the earliest in reading order.
     */
    internal fun appearanceEvidence(
        passages: List<ChapterEvidencePlanning.EvidencePassage>,
    ): List<ChapterEvidencePlanning.EvidencePassage> {
        var remaining = MAX_APPEARANCE_EVIDENCE_CHARS
        return passages
            .distinctBy { it.text }
            .sortedWith(
                compareByDescending<ChapterEvidencePlanning.EvidencePassage> { it.probability }.thenBy { it.chapter }.thenBy { it.index },
            ).filter { passage ->
                (passage.text.length <= remaining).also { fits -> if (fits) remaining -= passage.text.length }
            }.sortedWith(compareBy({ it.chapter }, { it.index }))
    }
}
