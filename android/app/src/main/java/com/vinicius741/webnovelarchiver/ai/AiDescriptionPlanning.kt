package com.vinicius741.webnovelarchiver.ai

import com.vinicius741.webnovelarchiver.domain.model.Story

/**
 * Pure planning for AI-generated novel descriptions. Decides which chapters feed the model, builds
 * the prompt, and post-processes the reply — all unit-testable without Android or network. The
 * chapter/char budgets bound the per-generation cost regardless of how long the source chapters are.
 *
 * Future AI features (tags, cover art) get their own planning objects beside this one.
 */
object AiDescriptionPlanning {
    /** How many of the story's first downloaded chapters are sent as context. */
    const val CONTEXT_CHAPTER_COUNT = 5

    /** Per-chapter plain-text cap (~3k tokens); enough for a full typical web-novel chapter. */
    internal const val MAX_CHARS_PER_CHAPTER = 12_000

    /** Total context cap across all chapters; with the per-chapter cap this is a final guard. */
    internal const val MAX_TOTAL_CONTEXT_CHARS = 60_000

    /** Output budget: a 120–200 word synopsis fits well inside this even with brief model preambles. */
    const val MAX_OUTPUT_TOKENS = 700

    /** Plain text of one context chapter, already capped and trimmed. */
    data class ChapterText(
        val number: Int,
        val title: String,
        val text: String,
    )

    /**
     * The synopsis the Details screen should display: the AI one when the story has one and the
     * user's toggle selects it, otherwise the source description. Also drives description TTS so
     * narration reads exactly what is on screen.
     */
    fun activeDescription(story: Story): String? {
        val aiDescription = story.aiDescription?.takeIf { it.isNotBlank() }
        val sourceDescription = story.description?.takeIf { it.isNotBlank() }
        return if (story.showAiDescription) {
            aiDescription ?: sourceDescription
        } else {
            sourceDescription ?: aiDescription
        }
    }

    /** Whether [activeDescription] currently resolves to the locally generated synopsis. */
    fun isAiDescriptionActive(story: Story): Boolean {
        val hasAiDescription = !story.aiDescription.isNullOrBlank()
        val hasSourceDescription = !story.description.isNullOrBlank()
        return hasAiDescription && (story.showAiDescription || !hasSourceDescription)
    }

    /** Indices (into [Story.chapters]) of the first [CONTEXT_CHAPTER_COUNT] downloaded chapters. */
    fun selectContextChapters(story: Story): List<Int> =
        story.chapters
            .withIndex()
            .filter { it.value.downloaded }
            .take(CONTEXT_CHAPTER_COUNT)
            .map { it.index }

    /** Caps one chapter's plain text, marking the cut so the model knows the text continues. */
    fun capChapterText(text: String): String =
        if (text.length <= MAX_CHARS_PER_CHAPTER) {
            text.trim()
        } else {
            text.take(MAX_CHARS_PER_CHAPTER).trim() + "\n[... chapter truncated ...]"
        }

    /** Builds the chat messages for description generation from the story metadata + context text. */
    fun buildMessages(
        story: Story,
        chapters: List<ChapterText>,
    ): List<OpenRouterMessage> {
        val metadata =
            buildList {
                add("Title: ${story.title}")
                if (story.author.isNotBlank()) add("Author: ${story.author}")
                story.tags?.takeIf { it.isNotEmpty() }?.let { add("Tags: ${it.joinToString(", ")}") }
            }.joinToString("\n")
        val chapterBlock =
            enforceTotalContextCap(chapters).joinToString("\n\n") { chapter ->
                buildString {
                    append("Chapter ${chapter.number}")
                    if (chapter.title.isNotBlank()) append(": ${chapter.title}")
                    append("\n")
                    append(chapter.text)
                }
            }
        val userContent =
            "Here are the opening chapters of a web novel:\n\n$metadata\n\n$chapterBlock"
        return listOf(
            OpenRouterMessage(role = "system", content = SYSTEM_PROMPT),
            OpenRouterMessage(role = "user", content = userContent),
        )
    }

    /** Drops/truncates trailing chapters so combined context stays under [MAX_TOTAL_CONTEXT_CHARS]. */
    internal fun enforceTotalContextCap(chapters: List<ChapterText>): List<ChapterText> {
        var remaining = MAX_TOTAL_CONTEXT_CHARS
        return chapters.mapNotNull { chapter ->
            if (remaining <= 0) return@mapNotNull null
            val text =
                if (chapter.text.length <= remaining) {
                    chapter.text
                } else {
                    chapter.text.take(remaining).trim() + "\n[... chapter truncated ...]"
                }
            remaining -= text.length
            chapter.copy(text = text)
        }
    }

    /**
     * Post-processes the model's reply: trims surrounding whitespace, drops wrapping quotes some
     * models add (either around the whole reply or just a stray leading quote), and collapses 3+
     * blank lines. Returns null when nothing presentable remains.
     */
    fun cleanGeneratedDescription(raw: String): String? {
        var text = raw.trim()
        if (text.startsWith("\"")) text = text.removePrefix("\"")
        if (text.endsWith("\"")) text = text.removeSuffix("\"")
        text = text.trim()
        text = text.replace(Regex("\n{3,}"), "\n\n")
        return text.takeIf { it.isNotBlank() }
    }

    private const val SYSTEM_PROMPT =
        "You write back-cover synopses for web novels. Using only the opening chapters provided, " +
            "write a compelling, accurate description of the story: the premise, the protagonist, " +
            "the central conflict, and what makes it engaging. 120 to 200 words. Plain prose only: " +
            "no headings, no lists, no quotation marks around the text, no meta commentary, " +
            "and no events beyond the provided chapters."
}
