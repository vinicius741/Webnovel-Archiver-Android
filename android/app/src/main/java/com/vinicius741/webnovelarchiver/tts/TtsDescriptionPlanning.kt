package com.vinicius741.webnovelarchiver.tts

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story

/**
 * Description narration planning. The details screen speaks a story's description through the exact
 * same pipeline as chapter playback (shared [TtsEngine], voice/rate/pitch settings, foreground
 * service, MediaSession, and persisted [com.vinicius741.webnovelarchiver.domain.model.TtsSession])
 * by keying the session on a reserved sentinel chapter id instead of a real chapter. All decisions
 * here are pure so they can be unit-tested without Android or the repository.
 */
object TtsDescriptionPlanning {
    /**
     * Reserved chapter id marking a TTS session as description narration. Source-derived chapter ids
     * are numeric path fragments, so a namespaced non-numeric id cannot collide with a real chapter.
     */
    const val DESCRIPTION_CHAPTER_ID = "wna:description"

    /** Session/notification title for description narration. */
    const val DESCRIPTION_SESSION_TITLE = "Description"

    fun isDescriptionSession(chapterId: String?): Boolean = chapterId == DESCRIPTION_CHAPTER_ID

    fun canSpeak(story: Story): Boolean = !story.description.isNullOrBlank()

    /**
     * Synthetic chapter carrying the session identity for description playback. Never added to a
     * story's chapter list — it only names the session so the shared engine/session machinery works
     * unchanged.
     */
    fun descriptionChapter(): Chapter =
        Chapter(
            id = DESCRIPTION_CHAPTER_ID,
            title = DESCRIPTION_SESSION_TITLE,
        )

    /**
     * Converts the plain-text description into the HTML the shared chunker
     * ([com.vinicius741.webnovelarchiver.cleanup.TtsTextPreparation.prepareTtsChunks]) consumes.
     * Blank-line-separated paragraphs become `<p>` elements so paragraph boundaries survive
     * chunking; the text is HTML-escaped so descriptions containing markup characters are spoken
     * verbatim instead of being parsed as tags.
     */
    fun descriptionToHtml(description: String): String {
        val paragraphs =
            description
                .split(Regex("\n\\s*\n"))
                .map(::normalizeWhitespace)
                .filter { it.isNotEmpty() }
        val effective = paragraphs.ifEmpty { listOf(normalizeWhitespace(description)) }.filter { it.isNotEmpty() }
        return effective.joinToString("") { "<p>${escapeHtml(it)}</p>" }
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
