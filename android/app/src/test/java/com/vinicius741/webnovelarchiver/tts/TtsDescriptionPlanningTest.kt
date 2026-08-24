package com.vinicius741.webnovelarchiver.tts

import com.vinicius741.webnovelarchiver.cleanup.TtsTextPreparation
import com.vinicius741.webnovelarchiver.domain.model.Story
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsDescriptionPlanningTest {
    @Test
    fun isDescriptionSessionMatchesOnlyTheSentinelChapterId() {
        assertTrue(TtsDescriptionPlanning.isDescriptionSession(TtsDescriptionPlanning.DESCRIPTION_CHAPTER_ID))
        assertFalse(TtsDescriptionPlanning.isDescriptionSession("12345"))
        assertFalse(TtsDescriptionPlanning.isDescriptionSession(null))
        assertFalse(TtsDescriptionPlanning.isDescriptionSession(""))
    }

    @Test
    fun canSpeakRequiresNonBlankDescription() {
        assertFalse(TtsDescriptionPlanning.canSpeak(Story(id = "s1", description = null)))
        assertFalse(TtsDescriptionPlanning.canSpeak(Story(id = "s1", description = "   ")))
        assertTrue(TtsDescriptionPlanning.canSpeak(Story(id = "s1", description = "A story.")))
    }

    @Test
    fun descriptionChapterCarriesTheSentinelIdentity() {
        val chapter = TtsDescriptionPlanning.descriptionChapter()
        assertEquals(TtsDescriptionPlanning.DESCRIPTION_CHAPTER_ID, chapter.id)
        assertEquals(TtsDescriptionPlanning.DESCRIPTION_SESSION_TITLE, chapter.title)
    }

    @Test
    fun descriptionSessionHtmlLeadsWithTitleThenWrapsParagraphs() {
        val html = TtsDescriptionPlanning.descriptionSessionHtml("My Story", "First paragraph\ncontinued.\n\nSecond paragraph.")
        assertEquals("<p>My Story</p><p>First paragraph continued.</p><p>Second paragraph.</p>", html)
    }

    @Test
    fun descriptionSessionHtmlEscapesMarkupCharacters() {
        val html = TtsDescriptionPlanning.descriptionSessionHtml("R&amp;R", "Power < 10 & \"quotes\" aren't parsed.")
        assertEquals("<p>R&amp;amp;R</p><p>Power &lt; 10 &amp; &quot;quotes&quot; aren&#39;t parsed.</p>", html)
    }

    @Test
    fun descriptionSessionHtmlSkipsTheTitleParagraphWhenBlank() {
        val html = TtsDescriptionPlanning.descriptionSessionHtml("  ", "One line\nnext line.")
        assertEquals("<p>One line next line.</p>", html)
    }

    @Test
    fun descriptionChunksAlignWithTitleAndParagraphSentences() {
        // End-to-end with the shared chunker: the title and each sentence of the description
        // become chunks, exactly like chapter playback.
        val html = TtsDescriptionPlanning.descriptionSessionHtml("My Story", "A young hero rises. The journey begins.\n\nWar follows.")
        assertEquals(
            listOf("My Story", "A young hero rises.", "The journey begins.", "War follows."),
            TtsTextPreparation.prepareTtsChunks(html, emptyList()),
        )
    }

    @Test
    fun descriptionProseFollowsTheTitleChunkVerbatim() {
        // Regression guard for "TTS misses the first words of descriptions": the title chunk is
        // the sacrificial session head, so the real synopsis must begin immediately after it with
        // its opening sentence intact.
        val description =
            "When Eric Swallow received his first quest from the System, he certainly didn’t expect " +
                "to end up in the netherworld. And he definitely didn’t plan to become a witch called Sylvia.\n\n" +
                "What to expect:\n- Adventures across multiple planes\n- Politics, economics, and kingdom building"
        val chunks =
            TtsTextPreparation.prepareTtsChunks(
                TtsDescriptionPlanning.descriptionSessionHtml("Netherwitch", description),
                emptyList(),
            )
        assertEquals("Netherwitch", chunks[0])
        assertTrue(chunks[1].startsWith("When Eric Swallow received his first quest from the System"))
        assertEquals(
            "And he definitely didn’t plan to become a witch called Sylvia.",
            chunks[2],
        )
    }
}
