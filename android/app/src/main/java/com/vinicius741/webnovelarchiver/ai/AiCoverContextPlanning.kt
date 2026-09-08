package com.vinicius741.webnovelarchiver.ai

import com.vinicius741.webnovelarchiver.domain.model.Story

/** Cover-only sampling and text budgets, independent of synopsis context and reading progress. */
object AiCoverContextPlanning {
    private const val CONTEXT_CHAPTER_COUNT = 9

    fun selectContextChapters(story: Story): List<Int> {
        val downloaded = story.chapters.indices.filter { story.chapters[it].downloaded }
        if (downloaded.size <= CONTEXT_CHAPTER_COUNT) return downloaded
        return (0 until CONTEXT_CHAPTER_COUNT).map { sample ->
            downloaded[(sample.toLong() * (downloaded.size - 1) / (CONTEXT_CHAPTER_COUNT - 1)).toInt()]
        }
    }

    fun resolveContextChapters(story: Story): List<Int> {
        val explicit = story.aiCoverContextChapterIndices
        if (explicit.isNullOrEmpty()) return selectContextChapters(story)
        return explicit.distinct().sorted().filter { it in story.chapters.indices && story.chapters[it].downloaded }
    }

    fun contextChaptersLabel(story: Story): String =
        if (story.aiCoverContextChapterIndices.isNullOrEmpty()) {
            "Across downloaded chapters (automatic)"
        } else {
            "${resolveContextChapters(story).size} selected"
        }

    internal fun balanceContext(chapters: List<AiDescriptionPlanning.ChapterText>): List<AiDescriptionPlanning.ChapterText> {
        if (chapters.isEmpty()) return emptyList()
        val allowance = minOf(AiDescriptionPlanning.MAX_CHARS_PER_CHAPTER, AiDescriptionPlanning.MAX_TOTAL_CONTEXT_CHARS / chapters.size)
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
}
