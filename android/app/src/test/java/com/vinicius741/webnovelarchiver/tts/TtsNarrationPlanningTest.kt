package com.vinicius741.webnovelarchiver.tts

import com.vinicius741.webnovelarchiver.cleanup.TtsChunkBoundary
import com.vinicius741.webnovelarchiver.cleanup.TtsNarrationChunk
import com.vinicius741.webnovelarchiver.cleanup.TtsTextPreparation
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsNarrationPlanningTest {
    private val chunks =
        listOf(
            TtsNarrationChunk("One.", TtsChunkBoundary.SENTENCE),
            TtsNarrationChunk("Two.", TtsChunkBoundary.PARAGRAPH),
            TtsNarrationChunk("Three.", TtsChunkBoundary.SCENE),
            TtsNarrationChunk("Four.", TtsChunkBoundary.PARAGRAPH),
        )

    private fun pauses(items: List<TtsTimelineItem>) = items.filterIsInstance<TtsTimelineItem.Silence>().map { it.durationMs }

    @Test
    fun longerBreaksGetLongerPauses() {
        assertEquals(
            listOf(
                TtsSettings().sentencePauseMs,
                TtsSettings().paragraphPauseMs,
                TtsSettings().scenePauseMs,
                TtsNarrationPlanning.CHAPTER_END_PAUSE_MS,
            ),
            pauses(TtsNarrationPlanning.items(chunks)),
        )
    }

    @Test
    fun eachBreakUsesItsOwnSetting() {
        val settings = TtsSettings(sentencePauseMs = 111L, paragraphPauseMs = 222L, scenePauseMs = 333L)
        assertEquals(
            listOf(111L, 222L, 333L, TtsNarrationPlanning.CHAPTER_END_PAUSE_MS),
            pauses(TtsNarrationPlanning.items(chunks, settings = settings)),
        )
    }

    @Test
    fun newPacingChangesPausesAndKeepsWhatIsRendered() {
        val timeline = TtsTimeline(TtsNarrationPlanning.items(chunks))
        timeline.setKnownDuration(0, 900L)
        val before = timeline.totalMs()
        val longer = TtsSettings().let { it.copy(sentencePauseMs = it.sentencePauseMs + 400L) }

        timeline.updatePacing(TtsNarrationPlanning.items(chunks, settings = longer), phrasePauseMs = 150L)

        assertEquals(before + 400L, timeline.totalMs())
        assertEquals(900L, timeline.durationMs(0))
        assertEquals(150L, timeline.phrasePauseMs)
    }

    @Test
    fun sampleTextKeepsItsParagraphAndSceneBreaks() {
        val narration =
            TtsTextPreparation.prepareTtsNarration(
                TtsNarrationPlanning.sampleHtml("One here. Two <here>.\n\nThree here.\n  \n* * *\n\nFour here."),
                emptyList(),
            )
        assertEquals(
            listOf("One here.", "Two <here>.", "Three here.", "Four here."),
            narration.map { it.text },
        )
        assertEquals(
            listOf(TtsChunkBoundary.SENTENCE, TtsChunkBoundary.PARAGRAPH, TtsChunkBoundary.SCENE),
            narration.dropLast(1).map { it.boundaryAfter },
        )
    }

    @Test
    fun titleComesFirstAndBelongsToTheFirstSentence() {
        val items = TtsNarrationPlanning.items(chunks, spokenTitle = "Chapter One")
        assertEquals(TtsTimelineItem.Speech("Chapter One", 0, isTitle = true), items[0])
        assertEquals(0, TtsNarrationPlanning.itemIndexForChunk(items, 0, includeTitle = true))
        assertEquals(2, TtsNarrationPlanning.itemIndexForChunk(items, 0))
    }

    @Test
    fun savePointMapsTitleAndPausesToRealSentences() {
        val items = TtsNarrationPlanning.items(chunks, spokenTitle = "Chapter One")
        assertEquals(TtsChunkPoint(0), TtsNarrationPlanning.savePoint(items, TtsTimelinePoint(0, 500L)))
        assertEquals(TtsChunkPoint(0), TtsNarrationPlanning.savePoint(items, TtsTimelinePoint(1, 100L)))
        assertEquals(TtsChunkPoint(0, 700L), TtsNarrationPlanning.savePoint(items, TtsTimelinePoint(2, 700L)))
        assertEquals(TtsChunkPoint(1), TtsNarrationPlanning.savePoint(items, TtsTimelinePoint(3, 100L)))
        assertEquals(TtsChunkPoint(3), TtsNarrationPlanning.savePoint(items, TtsTimelinePoint(items.lastIndex, 0L)))
    }

    @Test
    fun spokenTitleIsSkippedWhenTheTextAlreadyOpensWithIt() {
        assertNull(TtsNarrationPlanning.spokenChapterTitle("1.2 - Temple", "1.2 - Temple of Promise begins."))
        assertEquals("1.2, Temple", TtsNarrationPlanning.spokenChapterTitle("1.2 - Temple", "He stood there."))
        assertNull(TtsNarrationPlanning.spokenChapterTitle("  ", "He stood there."))
    }

    @Test
    fun timelineEstimatesThenBecomesExact() {
        val items = listOf(TtsTimelineItem.Speech("abcd", 0), TtsTimelineItem.Silence(300L, 0), TtsTimelineItem.Speech("efgh", 1))
        val timeline = TtsTimeline(items)
        assertFalse(timeline.isExact)
        timeline.setKnownDuration(0, 1_000L)
        timeline.setKnownDuration(2, 2_000L)
        assertTrue(timeline.isExact)
        assertEquals(3_300L, timeline.totalMs())
    }

    @Test
    fun timelineLocatesTimesAndClampsAtTheEnds() {
        val items = listOf(TtsTimelineItem.Speech("abcd", 0), TtsTimelineItem.Silence(300L, 0), TtsTimelineItem.Speech("efgh", 1))
        val timeline = TtsTimeline(items)
        timeline.setKnownDuration(0, 1_000L)
        timeline.setKnownDuration(2, 2_000L)
        assertEquals(TtsTimelinePoint(0, 0L), timeline.locate(-5L))
        assertEquals(TtsTimelinePoint(0, 999L), timeline.locate(999L))
        assertEquals(TtsTimelinePoint(1, 100L), timeline.locate(1_100L))
        assertEquals(TtsTimelinePoint(2, 500L), timeline.locate(1_800L))
        assertEquals(TtsTimelinePoint(2, 2_000L), timeline.locate(99_000L))
        assertEquals(1_800L, timeline.positionMs(2, 500L))
    }

    @Test
    fun timelineCalibratesItsEstimateFromRenderedSpeech() {
        val long = "x".repeat(300)
        val timeline = TtsTimeline(listOf(TtsTimelineItem.Speech(long, 0), TtsTimelineItem.Speech(long, 1)))
        timeline.setKnownDuration(0, 3_000L)
        assertEquals(3_000L, timeline.durationMs(1))
    }
}
