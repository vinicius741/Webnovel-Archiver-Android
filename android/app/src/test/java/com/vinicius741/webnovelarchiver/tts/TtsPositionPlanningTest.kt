package com.vinicius741.webnovelarchiver.tts

import com.vinicius741.webnovelarchiver.cleanup.TtsChunkBoundary.PARAGRAPH
import com.vinicius741.webnovelarchiver.cleanup.TtsChunkBoundary.SENTENCE
import com.vinicius741.webnovelarchiver.cleanup.TtsNarrationChunk
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.TtsHistoryEntry
import com.vinicius741.webnovelarchiver.domain.model.TtsStoryPosition
import org.junit.Assert.assertEquals
import org.junit.Test

class TtsPositionPlanningTest {
    private val chunks = listOf("Alpha one.", "Beta two.", "Gamma three.", "Delta four.")
    private val noLegacy: () -> List<String> = { error("legacy numbering not needed") }

    @Test
    fun matchingAnchorKeepsTheOffset() {
        val point = TtsPositionPlanning.resolve(chunks, TtsSavedPoint(2, 900L, "Gamma"), noLegacy)
        assertEquals(TtsChunkPoint(2, 900L), point)
    }

    @Test
    fun shiftedNumberingFollowsTheTextAndDropsTheOffset() {
        val point = TtsPositionPlanning.resolve(chunks, TtsSavedPoint(0, 900L, "Gamma"), noLegacy)
        assertEquals(TtsChunkPoint(2), point)
    }

    @Test
    fun removedTextFallsBackToTheClampedNumber() {
        assertEquals(TtsChunkPoint(3), TtsPositionPlanning.resolve(chunks, TtsSavedPoint(99, 900L, "Gone"), noLegacy))
    }

    @Test
    fun nearestMatchWinsWhenALineRepeats() {
        val repeats = listOf("Yes.", "A.", "Yes.", "B.", "C.", "D.", "Yes.")
        assertEquals(TtsChunkPoint(6), TtsPositionPlanning.resolve(repeats, TtsSavedPoint(5, 0L, "Yes."), noLegacy))
    }

    @Test
    fun positionSavedBeforeAnchorsIsTranslatedThroughTheOldNumbering() {
        val current = listOf("\"Stop!\" he said.", "Then he left.")
        val legacy = listOf("\"Stop!\"", "he said.", "Then he left.")
        val saved = TtsSavedPoint(2, chunkingVersion = 0)
        assertEquals(TtsChunkPoint(1), TtsPositionPlanning.resolve(current, saved) { legacy })
    }

    @Test
    fun oldPositionPastTheEndClamps() {
        assertEquals(TtsChunkPoint(3), TtsPositionPlanning.resolve(chunks, TtsSavedPoint(99, chunkingVersion = 0)) { chunks })
    }

    private val narration =
        listOf(
            TtsNarrationChunk("P1 one.", SENTENCE),
            TtsNarrationChunk("P1 two.", PARAGRAPH),
            TtsNarrationChunk("P2 one.", SENTENCE),
            TtsNarrationChunk("P2 two.", SENTENCE),
            TtsNarrationChunk("P2 three.", PARAGRAPH),
        )

    private fun resume(
        pausedMs: Long,
        replay: Boolean = true,
    ) = TtsPositionPlanning.resumePoint(narration, TtsChunkPoint(4, 800L), pausedMs, replay)

    @Test
    fun briefPauseContinuesExactly() {
        assertEquals(TtsChunkPoint(4, 800L), resume(5_000L))
    }

    @Test
    fun longerBreaksReplayMore() {
        assertEquals(TtsChunkPoint(4), resume(2 * 60_000L))
        assertEquals(TtsChunkPoint(3), resume(2 * 3_600_000L))
        assertEquals(TtsChunkPoint(2), resume(24 * 3_600_000L))
        assertEquals(TtsChunkPoint(0), resume(7 * 24 * 3_600_000L))
    }

    @Test
    fun replayCanBeTurnedOff() {
        assertEquals(TtsChunkPoint(4, 800L), resume(7 * 24 * 3_600_000L, replay = false))
    }

    @Test
    fun historyIsNewestFirstDeduplicatedAndCapped() {
        var history = emptyList<TtsHistoryEntry>()
        repeat(TtsPositionPlanning.MAX_HISTORY + 3) { index ->
            history = TtsPositionPlanning.withHistoryEntry(history, TtsHistoryEntry(chapterId = "c", chunkIndex = index))
        }
        assertEquals(TtsPositionPlanning.MAX_HISTORY, history.size)
        assertEquals(TtsPositionPlanning.MAX_HISTORY + 2, history.first().chunkIndex)

        history = TtsPositionPlanning.withHistoryEntry(history, TtsHistoryEntry(chapterId = "c", chunkIndex = history.last().chunkIndex))
        assertEquals(TtsPositionPlanning.MAX_HISTORY, history.size)
        assertEquals(1, history.count { it.chunkIndex == history.first().chunkIndex })
    }

    private val story = Story(id = "s", chapters = MutableList(3) { Chapter(id = "c$it", title = "Chapter ${it + 1}") })

    private fun start(
        chapterId: String,
        chunkIndex: Int,
        anchor: String? = null,
    ) = TtsHistoryEntry(chapterId = chapterId, chunkIndex = chunkIndex, anchorText = anchor, recordedAt = 500L)

    @Test
    fun startingElsewhereKeepsTheSavedPlaceInTheHistory() {
        val older = TtsHistoryEntry(chapterId = "c0", chunkIndex = 3, recordedAt = 100L)
        val position =
            TtsStoryPosition(
                storyId = "s",
                chapterId = "c2",
                chapterTitle = "Chapter 3",
                currentChunkIndex = 40,
                anchorText = "Far",
                history = listOf(older),
            )

        val history = TtsPositionPlanning.historyForStart(story, position, start("c0", 0))

        assertEquals(listOf("c2", "c0"), history.map { it.chapterId })
        assertEquals(40, history.first().chunkIndex)
        assertEquals("Far", history.first().anchorText)
        assertEquals("Chapter 3", history.first().chapterTitle)
        assertEquals(500L, history.first().recordedAt)
    }

    @Test
    fun continuingFromTheSavedPlaceAddsNothing() {
        val position = TtsStoryPosition(storyId = "s", chapterId = "c1", currentChunkIndex = 4, anchorText = "Same")

        assertEquals(emptyList<TtsHistoryEntry>(), TtsPositionPlanning.historyForStart(story, position, start("c1", 4)))
        // Resolved from the saved place itself: renumbering may have moved its sentence number.
        assertEquals(
            emptyList<TtsHistoryEntry>(),
            TtsPositionPlanning.historyForStart(story, position, start("c1", 6, "Same"), resumedSavedPlace = true),
        )
    }

    @Test
    fun aRepeatedLineElsewhereIsNotTheSavedPlace() {
        val position = TtsStoryPosition(storyId = "s", chapterId = "c1", currentChunkIndex = 120, anchorText = "He nodded.")

        assertEquals(1, TtsPositionPlanning.historyForStart(story, position, start("c1", 5, "He nodded.")).size)
    }

    @Test
    fun anotherSentenceInTheSameChapterIsAJump() {
        val position = TtsStoryPosition(storyId = "s", chapterId = "c1", currentChunkIndex = 4, anchorText = "Same")

        assertEquals(1, TtsPositionPlanning.historyForStart(story, position, start("c1", 0, "Top")).size)
    }

    @Test
    fun caughtUpContinuesIntoTheNextChapterWithoutAnEntry() {
        val position = TtsStoryPosition(storyId = "s", chapterId = "c1", currentChunkIndex = 9, caughtUp = true)

        assertEquals(emptyList<TtsHistoryEntry>(), TtsPositionPlanning.historyForStart(story, position, start("c2", 0)))
        assertEquals(1, TtsPositionPlanning.historyForStart(story, position, start("c0", 0)).size)
    }

    @Test
    fun noSavedPlaceOrAnotherStoryStartsWithNoHistory() {
        assertEquals(emptyList<TtsHistoryEntry>(), TtsPositionPlanning.historyForStart(story, null, start("c0", 0)))
        val other = TtsStoryPosition(storyId = "x", chapterId = "c1", history = listOf(TtsHistoryEntry(chapterId = "c1")))
        assertEquals(emptyList<TtsHistoryEntry>(), TtsPositionPlanning.historyForStart(story, other, start("c0", 0)))
    }
}
