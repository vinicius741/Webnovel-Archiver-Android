package com.vinicius741.webnovelarchiver.ai

import com.vinicius741.webnovelarchiver.cleanup.HtmlCleanup
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.domain.model.Story

/**
 * Reads a story's context chapters — the first downloaded ones [AiDescriptionPlanning] selects,
 * or the story's explicit per-story selection when one exists — into capped plain text for AI
 * prompts. Shared by the description and cover engines so every AI
 * feature bases its output on identical context. Chapters whose files are missing and that carry
 * no in-memory content are dropped; an empty result means nothing readable was available.
 */
internal object AiContextChapters {
    suspend fun read(
        repository: AppRepository,
        story: Story,
    ): List<AiDescriptionPlanning.ChapterText> = read(repository, story, story.aiContextChapterIndices)

    suspend fun read(
        repository: AppRepository,
        story: Story,
        explicitIndices: List<Int>?,
    ): List<AiDescriptionPlanning.ChapterText> =
        AiDescriptionPlanning.resolveContextChapters(story, explicitIndices).mapNotNull { index ->
            val chapter = story.chapters[index]
            val html = repository.readChapter(chapter) ?: chapter.content ?: return@mapNotNull null
            AiDescriptionPlanning.ChapterText(
                number = index + 1,
                title = chapter.title,
                text = AiDescriptionPlanning.capChapterText(HtmlCleanup.htmlToFormattedText(html)),
            )
        }
}
