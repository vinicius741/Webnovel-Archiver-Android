package com.vinicius741.webnovelarchiver.feature.reader

import com.vinicius741.webnovelarchiver.tts.TtsPlaybackSnapshot
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAlongPlanningTest {
    @Test
    fun aLiveUpdateForThisChapterMovesTheHighlight() {
        val state = ReadAlongState(snapshot(chunk = 3))

        val next = ReadAlongPlanning.afterPlayback(state, live(snapshot(chunk = 4)), STORY, CHAPTER)

        assertEquals(4, next.sentence)
        assertTrue(next.following)
    }

    @Test
    fun theInitialPlaceholderKeepsThePersistedSnapshot() {
        val state = ReadAlongState(snapshot(chunk = 7, playing = false))

        val next = ReadAlongPlanning.afterPlayback(state, TtsPlaybackUpdate(null, isAuthoritative = false), STORY, CHAPTER)

        assertEquals(state, next)
    }

    @Test
    fun playbackElsewhereOrAStopClearsTheHighlight() {
        val state = ReadAlongState(snapshot(chunk = 3))

        assertEquals(
            ReadAlongState.NO_SENTENCE,
            ReadAlongPlanning.afterPlayback(state, live(snapshot(chapter = "other")), STORY, CHAPTER).sentence,
        )
        assertEquals(ReadAlongState.NO_SENTENCE, ReadAlongPlanning.afterPlayback(state, live(null), STORY, CHAPTER).sentence)
    }

    @Test
    fun aDragStopsFollowingOnlyWhileASentenceIsHighlighted() {
        assertFalse(ReadAlongPlanning.afterDrag(ReadAlongState(snapshot(chunk = 3))).following)
        assertTrue(ReadAlongPlanning.afterDrag(ReadAlongState()).following)
    }

    @Test
    fun followingStaysOffAcrossSentencesUntilTheReaderReturns() {
        val dragged = ReadAlongPlanning.afterDrag(ReadAlongState(snapshot(chunk = 3)))

        val later = ReadAlongPlanning.afterPlayback(dragged, live(snapshot(chunk = 9)), STORY, CHAPTER)

        assertFalse(later.following)
        assertTrue(ReadAlongPlanning.afterFollow(later).following)
    }

    @Test
    fun aSessionStartingAfreshInThisChapterFollowsAgain() {
        val stoppedWhileScrolledAway = ReadAlongState(snapshot = null, following = false)

        val started = ReadAlongPlanning.afterPlayback(stoppedWhileScrolledAway, live(snapshot(chunk = 0)), STORY, CHAPTER)

        assertTrue(started.following)
    }

    @Test
    fun thePillShowsOnlyWhileAudioPlaysUnfollowed() {
        assertTrue(ReadAlongState(snapshot(chunk = 1), following = false).showsPill)
        assertFalse(ReadAlongState(snapshot(chunk = 1, playing = false), following = false).showsPill)
        assertFalse(ReadAlongState(snapshot(chunk = 1), following = true).showsPill)
        assertFalse(ReadAlongState(null, following = false).showsPill)
    }

    @Test
    fun theDockedPlayerShowsWhileThisChapterHasASession() {
        assertTrue(ReadAlongState(snapshot(chunk = 1, playing = false)).showsPlayer)
        assertFalse(ReadAlongState().showsPlayer)
    }

    @Test
    fun scrollsForANewSentenceOrReturningButNotWhileAway() {
        val at3 = ReadAlongState(snapshot(chunk = 3))
        val at4 = ReadAlongState(snapshot(chunk = 4))

        assertEquals(ReadAlongScroll.SMOOTH, ReadAlongPlanning.scroll(at3, at4))
        assertEquals(ReadAlongScroll.NONE, ReadAlongPlanning.scroll(at3, at3.copy(snapshot = snapshot(chunk = 3, playing = false))))
        assertEquals(ReadAlongScroll.NONE, ReadAlongPlanning.scroll(at3, at4.copy(following = false)))
        assertEquals(ReadAlongScroll.SMOOTH, ReadAlongPlanning.scroll(at3.copy(following = false), at3))
        assertEquals(ReadAlongScroll.NONE, ReadAlongPlanning.scroll(at3, ReadAlongState()))
    }

    @Test
    fun opensThePlayingChapterFromTheCurrentChapterList() {
        val next = snapshot(chapter = "chapter-2")

        assertEquals("chapter-2", ReadAlongPlanning.chapterToOpen(STORY, CHAPTER, next, listOf(CHAPTER, "chapter-2")))
        // Not in the story (yet): stay put rather than open nothing.
        assertNull(ReadAlongPlanning.chapterToOpen(STORY, CHAPTER, next, listOf(CHAPTER)))
        assertNull(ReadAlongPlanning.chapterToOpen(STORY, CHAPTER, snapshot(chunk = 5), listOf(CHAPTER)))
        assertNull(ReadAlongPlanning.chapterToOpen(STORY, CHAPTER, snapshot(story = "other", chapter = "chapter-2"), listOf("chapter-2")))
    }

    @Test
    fun pageStateCarriesTheSentenceAndWhetherToFollow() {
        val state = ReadAlongState(snapshot(chunk = 12))

        assertEquals("12 1", ReadAlongPlanning.pageState(state, fingerDown = false))
        // Never scroll the page from under a finger.
        assertEquals("12 0", ReadAlongPlanning.pageState(state, fingerDown = true))
        assertEquals("12 0", ReadAlongPlanning.pageState(state.copy(following = false), fingerDown = false))
        assertEquals("-1 1", ReadAlongPlanning.pageState(ReadAlongState(), fingerDown = false))
    }

    private fun live(snapshot: TtsPlaybackSnapshot?) = TtsPlaybackUpdate(snapshot, isAuthoritative = true)

    private fun snapshot(
        chunk: Int = 0,
        playing: Boolean = true,
        story: String = STORY,
        chapter: String = CHAPTER,
    ) = TtsPlaybackSnapshot(
        title = "Chapter",
        storyId = story,
        chapterId = chapter,
        chunkIndex = chunk,
        totalChunks = 20,
        isPlaying = playing,
        isPaused = !playing,
    )

    private companion object {
        const val STORY = "story-1"
        const val CHAPTER = "chapter-1"
    }
}
