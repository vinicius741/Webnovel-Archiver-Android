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
    fun descriptionToHtmlWrapsParagraphsAndNormalizesWhitespace() {
        val html = TtsDescriptionPlanning.descriptionToHtml("First paragraph\ncontinued.\n\nSecond paragraph.")
        assertEquals("<p>First paragraph continued.</p><p>Second paragraph.</p>", html)
    }

    @Test
    fun descriptionToHtmlEscapesMarkupCharacters() {
        val html = TtsDescriptionPlanning.descriptionToHtml("Power < 10 & \"quotes\" aren't parsed.")
        assertEquals("<p>Power &lt; 10 &amp; &quot;quotes&quot; aren&#39;t parsed.</p>", html)
    }

    @Test
    fun descriptionToHtmlFallsBackToSingleParagraphWithoutBlankLineSeparators() {
        val html = TtsDescriptionPlanning.descriptionToHtml("One line\nnext line.")
        assertEquals("<p>One line next line.</p>", html)
    }

    @Test
    fun descriptionChunksAlignWithParagraphSentences() {
        // End-to-end with the shared chunker: each sentence of the description becomes a chunk,
        // exactly like chapter playback.
        val html = TtsDescriptionPlanning.descriptionToHtml("A young hero rises. The journey begins.\n\nWar follows.")
        assertEquals(
            listOf("A young hero rises.", "The journey begins.", "War follows."),
            TtsTextPreparation.prepareTtsChunks(html, emptyList()),
        )
    }
}
