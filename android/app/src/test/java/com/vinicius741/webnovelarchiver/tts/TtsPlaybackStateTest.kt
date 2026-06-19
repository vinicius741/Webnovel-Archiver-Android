package com.vinicius741.webnovelarchiver.tts

import com.vinicius741.webnovelarchiver.domain.model.TtsSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsPlaybackStateTest {
    @Test
    fun readerChapterTransitionFollowsSameStoryChapterChange() {
        val snapshot = playbackSnapshot(storyId = "story-1", chapterId = "chapter-2")

        assertEquals(
            "chapter-2",
            TtsPlaybackState.readerChapterTransition("story-1", "chapter-1", snapshot),
        )
    }

    @Test
    fun readerChapterTransitionIgnoresCurrentChapterAndOtherStories() {
        assertNull(
            TtsPlaybackState.readerChapterTransition(
                "story-1",
                "chapter-1",
                playbackSnapshot(storyId = "story-1", chapterId = "chapter-1"),
            ),
        )
        assertNull(
            TtsPlaybackState.readerChapterTransition(
                "story-1",
                "chapter-1",
                playbackSnapshot(storyId = "story-2", chapterId = "chapter-2"),
            ),
        )
        assertNull(TtsPlaybackState.readerChapterTransition("story-1", "chapter-1", null))
    }

    @Test
    fun chunkProgressReportsOneIndexedPosition() {
        assertEquals("Chunk 1 / 10", TtsPlaybackState.chunkProgress(chunkIndex = 0, totalChunks = 10))
        assertEquals("Chunk 5 / 10", TtsPlaybackState.chunkProgress(chunkIndex = 4, totalChunks = 10))
        assertEquals("Chunk 10 / 10", TtsPlaybackState.chunkProgress(chunkIndex = 9, totalChunks = 10))
    }

    @Test
    fun chunkProgressClampsAboveRange() {
        // A chunk index past the end (engine about to advance) should not print "11/10".
        assertEquals("Chunk 10 / 10", TtsPlaybackState.chunkProgress(chunkIndex = 12, totalChunks = 10))
    }

    @Test
    fun chunkProgressReportsBufferingWhenNoChunks() {
        assertEquals("Buffering", TtsPlaybackState.chunkProgress(chunkIndex = 0, totalChunks = 0))
    }

    @Test
    fun snapshotForSessionBuildsFromEligibleSession() {
        val session =
            TtsSession(
                storyId = "rr_1",
                chapterId = "ch_1",
                chapterTitle = "Chapter One",
                currentChunkIndex = 3,
                isPaused = false,
                wasPlaying = true,
                chunkSize = 500,
            )

        val snapshot = TtsPlaybackState.snapshotForSession(session, totalChunks = 10, isPlaying = true)

        assertEquals("Chapter One", snapshot?.title)
        assertEquals("rr_1", snapshot?.storyId)
        assertEquals("ch_1", snapshot?.chapterId)
        assertEquals(3, snapshot?.chunkIndex)
        assertEquals(10, snapshot?.totalChunks)
        assertTrue(snapshot?.isPlaying == true)
        assertFalse(snapshot?.isPaused == true)
    }

    @Test
    fun snapshotForSessionReturnsNullWhenNoSession() {
        assertNull(TtsPlaybackState.snapshotForSession(null, totalChunks = 10, isPlaying = false))
    }

    @Test
    fun snapshotForSessionReturnsNullWhenSessionNotResumeEligible() {
        // A session that was neither playing nor paused is not eligible (nothing to surface).
        val session =
            TtsSession(
                storyId = "rr_1",
                chapterId = "ch_1",
                chapterTitle = "Chapter One",
                wasPlaying = false,
                isPaused = false,
            )
        assertNull(TtsPlaybackState.snapshotForSession(session, totalChunks = 10, isPlaying = false))
    }

    @Test
    fun snapshotForSessionFallsBackTitleWhenBlank() {
        val session =
            TtsSession(
                storyId = "rr_1",
                chapterId = "ch_1",
                chapterTitle = "",
                wasPlaying = true,
            )
        val snapshot = TtsPlaybackState.snapshotForSession(session, totalChunks = 1, isPlaying = true)
        assertEquals("Reading", snapshot?.title)
    }

    @Test
    fun snapshotForSessionMarksPausedWhenNotPlaying() {
        val session =
            TtsSession(
                storyId = "rr_1",
                chapterId = "ch_1",
                chapterTitle = "Chapter One",
                isPaused = true,
                wasPlaying = true,
            )
        val snapshot = TtsPlaybackState.snapshotForSession(session, totalChunks = 5, isPlaying = false)
        assertTrue(snapshot?.isPaused == true)
        assertFalse(snapshot?.isPlaying == true)
    }

    private fun playbackSnapshot(
        storyId: String,
        chapterId: String,
    ) = TtsPlaybackSnapshot(
        title = "Chapter",
        storyId = storyId,
        chapterId = chapterId,
        chunkIndex = 0,
        totalChunks = 1,
        isPlaying = true,
        isPaused = false,
    )
}
