package com.vinicius741.webnovelarchiver.tts

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story

/**
 * Description narration reuses the chapter TTS pipeline by keying the session on a reserved
 * sentinel chapter id. Pure functions, unit-testable without Android.
 */
object TtsDescriptionPlanning {
    /** Reserved sentinel chapter id; source ids are numeric path fragments, so no collision. */
    const val DESCRIPTION_CHAPTER_ID = "wna:description"

    const val DESCRIPTION_SESSION_TITLE = "Description"

    fun isDescriptionSession(chapterId: String?): Boolean = chapterId == DESCRIPTION_CHAPTER_ID

    fun canSpeak(story: Story): Boolean = !story.description.isNullOrBlank()

    /** Session-identity-only chapter; never added to a story's chapter list. */
    fun descriptionChapter(): Chapter =
        Chapter(
            id = DESCRIPTION_CHAPTER_ID,
            title = DESCRIPTION_SESSION_TITLE,
        )

    /**
     * Builds the HTML the shared chunker consumes: blank-line-separated paragraphs become `<p>`s
     * so boundaries survive chunking; text is HTML-escaped so markup characters are spoken
     * verbatim. The leading title paragraph doubles as a sacrificial head — audio spin-up
     * (Bluetooth, engine cold start) can clip the first utterance, which should cost title
     * text, never the description's first words.
     */
    fun descriptionSessionHtml(
        title: String,
        description: String,
    ): String {
        val paragraphs =
            description
                .split(Regex("\n\\s*\n"))
                .map(::normalizeWhitespace)
                .filter { it.isNotEmpty() }
        val effective = paragraphs.ifEmpty { listOf(normalizeWhitespace(description)) }.filter { it.isNotEmpty() }
        val titleParagraph = normalizeWhitespace(title).takeIf { it.isNotEmpty() }
        return (listOfNotNull(titleParagraph) + effective)
            .joinToString("") { "<p>${escapeHtml(it)}</p>" }
    }

    private fun normalizeWhitespace(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    private fun escapeHtml(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
}
