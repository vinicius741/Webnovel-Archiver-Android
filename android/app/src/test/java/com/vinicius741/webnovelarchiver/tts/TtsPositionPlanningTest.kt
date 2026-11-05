package com.vinicius741.webnovelarchiver.tts

import com.vinicius741.webnovelarchiver.cleanup.TtsChunkBoundary.PARAGRAPH
import com.vinicius741.webnovelarchiver.cleanup.TtsChunkBoundary.SENTENCE
import com.vinicius741.webnovelarchiver.cleanup.TtsNarrationChunk
import com.vinicius741.webnovelarchiver.domain.model.TtsHistoryEntry
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
}
