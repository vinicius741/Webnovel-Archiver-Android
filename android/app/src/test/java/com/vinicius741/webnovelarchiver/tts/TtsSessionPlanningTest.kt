package com.vinicius741.webnovelarchiver.tts

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.TtsHistoryEntry
import com.vinicius741.webnovelarchiver.domain.model.TtsSession
import com.vinicius741.webnovelarchiver.domain.model.TtsStoryPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsSessionPlanningTest {
    @Test
    fun resumeEligibleRequiresStoryChapterAndPlayingOrPausedState() {
        assertFalse(TtsSessionPlanning.isResumeEligible(null))
        assertFalse(TtsSessionPlanning.isResumeEligible(TtsSession(storyId = "", chapterId = "c1", wasPlaying = true)))
        assertFalse(TtsSessionPlanning.isResumeEligible(TtsSession(storyId = "s1", chapterId = "", wasPlaying = true)))
        assertFalse(TtsSessionPlanning.isResumeEligible(TtsSession(storyId = "s1", chapterId = "c1", wasPlaying = false, isPaused = false)))
        assertTrue(TtsSessionPlanning.isResumeEligible(TtsSession(storyId = "s1", chapterId = "c1", wasPlaying = true, isPaused = false)))
        assertTrue(TtsSessionPlanning.isResumeEligible(TtsSession(storyId = "s1", chapterId = "c1", wasPlaying = false, isPaused = true)))
    }

    @Test
    fun boundedChunkIndexClampsToAvailableChunks() {
        assertEquals(0, TtsSessionPlanning.boundedChunkIndex(TtsSession(currentChunkIndex = -5), chunkCount = 3))
        assertEquals(1, TtsSessionPlanning.boundedChunkIndex(TtsSession(currentChunkIndex = 1), chunkCount = 3))
        assertEquals(2, TtsSessionPlanning.boundedChunkIndex(TtsSession(currentChunkIndex = 99), chunkCount = 3))
        assertEquals(0, TtsSessionPlanning.boundedChunkIndex(TtsSession(currentChunkIndex = 2), chunkCount = 0))
    }

    @Test
    fun nextChunkIndexAdvancesCurrentCursorAndClampsAtEnd() {
        assertEquals(1, TtsSessionPlanning.nextChunkIndex(currentChunkIndex = 0, chunkCount = 4))
        assertEquals(3, TtsSessionPlanning.nextChunkIndex(currentChunkIndex = 99, chunkCount = 4))
        assertEquals(1, TtsSessionPlanning.nextChunkIndex(currentChunkIndex = -1, chunkCount = 4))
        assertEquals(0, TtsSessionPlanning.nextChunkIndex(currentChunkIndex = 2, chunkCount = 0))
    }

    @Test
    fun previousChunkIndexStepsBackFromCurrentCursor() {
        assertEquals(0, TtsSessionPlanning.previousChunkIndex(currentChunkIndex = 1, chunkCount = 4))
        assertEquals(2, TtsSessionPlanning.previousChunkIndex(currentChunkIndex = 3, chunkCount = 4))
        assertEquals(0, TtsSessionPlanning.previousChunkIndex(currentChunkIndex = -1, chunkCount = 4))
        assertEquals(0, TtsSessionPlanning.previousChunkIndex(currentChunkIndex = 2, chunkCount = 0))
    }

    @Test
    fun readerResumeTargetRequiresEligibleSessionAndExistingStoryChapter() {
        val story = Story(id = "s1", chapters = mutableListOf(Chapter(id = "c1")))

        assertEquals(
            TtsSessionPlanning.ReaderResumeTarget("s1", "c1"),
            TtsSessionPlanning.readerResumeTarget(TtsSession(storyId = "s1", chapterId = "c1", wasPlaying = true)) { story },
        )
        assertEquals(
            null,
            TtsSessionPlanning.readerResumeTarget(TtsSession(storyId = "s1", chapterId = "c1")) { story },
        )
        assertEquals(
            null,
            TtsSessionPlanning.readerResumeTarget(TtsSession(storyId = "s1", chapterId = "missing", wasPlaying = true)) { story },
        )
        assertEquals(
            null,
            TtsSessionPlanning.readerResumeTarget(TtsSession(storyId = "s1", chapterId = "c1", wasPlaying = true)) { null },
        )
    }

    @Test
    fun readerResumeTargetIgnoresDescriptionSessions() {
        // A description session holds the sentinel chapter id, which no story chapter list contains,
        // so startup must never deep-link into the reader for it.
        val story = Story(id = "s1", chapters = mutableListOf(Chapter(id = "c1")))
        assertEquals(
            null,
            TtsSessionPlanning.readerResumeTarget(
                TtsSession(storyId = "s1", chapterId = TtsDescriptionPlanning.DESCRIPTION_CHAPTER_ID, wasPlaying = true),
            ) { story },
        )
    }

    @Test
    fun nextChapterIndexAdvancesOnlyWhenAFollowingChapterExists() {
        val story =
            Story(
                chapters =
                    mutableListOf(
                        Chapter(id = "c1"),
                        Chapter(id = "c2"),
                        Chapter(id = "c3"),
                    ),
            )

        assertEquals(1, TtsSessionPlanning.nextChapterIndex(story, "c1"))
        assertEquals(2, TtsSessionPlanning.nextChapterIndex(story, "c2"))
        assertEquals(null, TtsSessionPlanning.nextChapterIndex(story, "c3"))
        assertEquals(null, TtsSessionPlanning.nextChapterIndex(story, "missing"))
    }

    @Test
    fun chapterIndexAtDeltaClampsAtBothEdges() {
        val story =
            Story(
                chapters =
                    mutableListOf(
                        Chapter(id = "c1"),
                        Chapter(id = "c2"),
                        Chapter(id = "c3"),
                    ),
            )

        assertEquals(0, TtsSessionPlanning.chapterIndexAtDelta(story, "c2", -1))
        assertEquals(2, TtsSessionPlanning.chapterIndexAtDelta(story, "c2", 1))
        assertEquals(null, TtsSessionPlanning.chapterIndexAtDelta(story, "c1", -1))
        assertEquals(null, TtsSessionPlanning.chapterIndexAtDelta(story, "c3", 1))
        assertEquals(null, TtsSessionPlanning.chapterIndexAtDelta(story, "missing", 1))
    }

    @Test
    fun resolveStartPositionPrefersSavedPositionForPlayableChapter() {
        val story =
            Story(
                id = "s1",
                chapters =
                    mutableListOf(
                        Chapter(id = "c1", content = "<p>One.</p>", downloaded = true),
                        Chapter(id = "c2", content = "<p>Two.</p>", downloaded = true),
                    ),
            )

        // Resume may jump to a different chapter than the one on screen.
        assertEquals(
            TtsSessionPlanning.StartPosition("c2", 7),
            TtsSessionPlanning.resolveStartPosition(story, "c1", TtsStoryPosition(storyId = "s1", chapterId = "c2", currentChunkIndex = 7)),
        )
        // Legacy inline content counts as playable even when the downloaded flag is false.
        val legacy =
            Story(id = "s2", chapters = mutableListOf(Chapter(id = "c1", content = "<p>Inline.</p>", downloaded = false)))
        assertEquals(
            TtsSessionPlanning.StartPosition("c1", 2),
            TtsSessionPlanning.resolveStartPosition(
                legacy,
                "c1",
                TtsStoryPosition(storyId = "s2", chapterId = "c1", currentChunkIndex = 2),
            ),
        )
    }

    @Test
    fun resolveStartPositionFallsBackToRequestedChapter() {
        val story = Story(id = "s1", chapters = mutableListOf(Chapter(id = "c1", content = "<p>One.</p>", downloaded = true)))

        // No position, a foreign story's position, a vanished chapter, and an unplayable one all
        // fall back to the requested chapter from the top.
        assertEquals(
            TtsSessionPlanning.StartPosition("c1", 0),
            TtsSessionPlanning.resolveStartPosition(story, "c1", null),
        )
        assertEquals(
            TtsSessionPlanning.StartPosition("c1", 0),
            TtsSessionPlanning.resolveStartPosition(
                story,
                "c1",
                TtsStoryPosition(storyId = "other", chapterId = "c1", currentChunkIndex = 5),
            ),
        )
        assertEquals(
            TtsSessionPlanning.StartPosition("c1", 0),
            TtsSessionPlanning.resolveStartPosition(
                story,
                "c1",
                TtsStoryPosition(storyId = "s1", chapterId = "gone", currentChunkIndex = 5),
            ),
        )
        // A chapter in the list but not playable (undownloaded, no inline content) also falls back.
        val undownloaded =
            Story(id = "s2", chapters = mutableListOf(Chapter(id = "c9"), Chapter(id = "c1", content = "<p>One.</p>", downloaded = true)))
        assertEquals(
            TtsSessionPlanning.StartPosition("c1", 0),
            TtsSessionPlanning.resolveStartPosition(
                undownloaded,
                "c1",
                TtsStoryPosition(storyId = "s2", chapterId = "c9", currentChunkIndex = 5),
            ),
        )
    }

    @Test
    fun resolveStartPositionNeverConsultsPositionsForDescriptionSessions() {
        val story = Story(id = "s1", chapters = mutableListOf(Chapter(id = "c1", downloaded = true)))

        assertEquals(
            TtsSessionPlanning.StartPosition(TtsDescriptionPlanning.DESCRIPTION_CHAPTER_ID, 0),
            TtsSessionPlanning.resolveStartPosition(
                story,
                TtsDescriptionPlanning.DESCRIPTION_CHAPTER_ID,
                TtsStoryPosition(storyId = "s1", chapterId = "c1", currentChunkIndex = 9),
            ),
        )
    }

    @Test
    fun storyPositionMirrorsChapterSessionsOnly() {
        val chapterSession =
            TtsSession(
                storyId = "s1",
                storyTitle = "Title",
                chapterId = "c1",
                chapterTitle = "Chapter One",
                currentChunkIndex = 4,
                updatedAt = 42L,
            )
        val position = TtsSessionPlanning.storyPosition(chapterSession)!!

        assertEquals("s1", position.storyId)
        assertEquals("c1", position.chapterId)
        assertEquals("Chapter One", position.chapterTitle)
        assertEquals(4, position.currentChunkIndex)
        assertEquals(42L, position.updatedAt)

        assertEquals(
            null,
            TtsSessionPlanning.storyPosition(
                TtsSession(storyId = "s1", chapterId = TtsDescriptionPlanning.DESCRIPTION_CHAPTER_ID),
            ),
        )
        assertEquals(null, TtsSessionPlanning.storyPosition(TtsSession(chapterId = "c1")))
    }

    private fun chapter(
        id: String,
        downloaded: Boolean = true,
    ) = Chapter(id = id, title = id, downloaded = downloaded)

    @Test
    fun caughtUpStoryStartsAtTheNewChapterOnceThereIsOne() {
        val story = Story(id = "s", chapters = mutableListOf(chapter("a"), chapter("b")))
        val position = TtsStoryPosition(storyId = "s", chapterId = "a", currentChunkIndex = 40, caughtUp = true)
        assertEquals(TtsSessionPlanning.StartPosition("b", 0), TtsSessionPlanning.resolveStartPosition(story, "a", position))
    }

    @Test
    fun caughtUpStoryWithNothingNewStartsTheViewedChapter() {
        val story = Story(id = "s", chapters = mutableListOf(chapter("a"), chapter("b", downloaded = false)))
        val position = TtsStoryPosition(storyId = "s", chapterId = "a", currentChunkIndex = 40, caughtUp = true)
        assertEquals(TtsSessionPlanning.StartPosition("a", 0), TtsSessionPlanning.resolveStartPosition(story, "a", position))
    }

    @Test
    fun continueListeningPicksTheNewestStoryWithSomethingToPlay() {
        val finished = Story(id = "done", chapters = mutableListOf(chapter("a")))
        val older = Story(id = "older", chapters = mutableListOf(chapter("x"), chapter("y")))
        val positions =
            listOf(
                TtsStoryPosition(storyId = "older", chapterId = "y", updatedAt = 1L),
                TtsStoryPosition(storyId = "done", chapterId = "a", updatedAt = 3L, caughtUp = true),
                TtsStoryPosition(storyId = "deleted", chapterId = "a", updatedAt = 4L),
            )
        val stories = listOf(finished, older).associateBy { it.id }

        val target = TtsContinueListening.target(positions) { stories[it] }

        assertEquals("older", target?.story?.id)
        assertEquals("y", target?.chapter?.id)
    }

    @Test
    fun continueListeningOffersANewChapterAfterCatchingUp() {
        val story = Story(id = "s", chapters = mutableListOf(chapter("a"), chapter("b")))
        val positions = listOf(TtsStoryPosition(storyId = "s", chapterId = "a", caughtUp = true))
        assertEquals("b", TtsContinueListening.target(positions) { story }?.chapter?.id)
    }

    @Test
    fun continueListeningStaysDismissedWithoutFallingBackToAnOlderStory() {
        val story = Story(id = "s", chapters = mutableListOf(chapter("a")))
        val positions =
            listOf(
                TtsStoryPosition(storyId = "s", chapterId = "a", updatedAt = 1L),
                TtsStoryPosition(storyId = "s", chapterId = "a", updatedAt = 5L),
            )
        assertNull(TtsContinueListening.target(positions, dismissedThrough = 5L) { story })
    }

    @Test
    fun continueListeningReturnsOnceSomethingIsHeardAfterDismissal() {
        val story = Story(id = "s", chapters = mutableListOf(chapter("a")))
        val positions = listOf(TtsStoryPosition(storyId = "s", chapterId = "a", updatedAt = 6L))
        assertEquals("s", TtsContinueListening.target(positions, dismissedThrough = 5L) { story }?.story?.id)
    }

    @Test
    fun storyPositionCarriesTheAnchorAndHistory() {
        val history = listOf(TtsHistoryEntry(chapterId = "c", chunkIndex = 2))
        val position =
            TtsSessionPlanning.storyPosition(
                TtsSession(
                    storyId = "s",
                    chapterId = "c",
                    chunkOffsetMs = 50L,
                    anchorText = "Hello",
                    chunkingVersion = 2,
                    history = history,
                ),
            )
        assertEquals(50L, position?.chunkOffsetMs)
        assertEquals("Hello", position?.anchorText)
        assertEquals(2, position?.chunkingVersion)
        assertEquals(history, position?.history)
    }
}
