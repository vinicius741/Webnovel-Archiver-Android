package com.vinicius741.webnovelarchiver.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun restoredChunkSizeUsesCurrentSettingsWithMinimumBounds() {
        assertEquals(600, TtsSessionPlanning.restoredChunkSize(TtsSettings(chunkSize = 600)))
        assertEquals(100, TtsSessionPlanning.restoredChunkSize(TtsSettings(chunkSize = 40)))
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
}
