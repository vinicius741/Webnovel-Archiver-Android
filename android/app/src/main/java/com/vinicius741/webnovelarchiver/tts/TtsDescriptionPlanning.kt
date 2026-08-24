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
     * Converts the story title and plain-text description into the HTML the shared chunker
     * ([com.vinicius741.webnovelarchiver.cleanup.TtsTextPreparation.prepareTtsChunks]) consumes.
     * Blank-line-separated paragraphs become `<p>` elements so paragraph boundaries survive
     * chunking; the text is HTML-escaped so descriptions containing markup characters are spoken
     * verbatim instead of being parsed as tags.
     *
     * The title leads as its own paragraph (audiobook convention) and doubles as a sacrificial
     * head for the session's first utterance: audio routes spin up on the first synthesized
     * frames (Bluetooth negotiation, speaker-amp ramp, engine cold start), and that spin-up can
     * swallow the start of the first utterance. Chapters lose nothing there — their stored HTML
     * opens with title boilerplate — but a description's first chunk used to be real prose, so
     * listeners heard the synopsis open mid-sentence. With the title first, a clipped head costs
     * at most part of the title, never the description's first words.
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
