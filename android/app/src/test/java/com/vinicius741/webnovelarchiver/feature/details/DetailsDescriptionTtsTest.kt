package com.vinicius741.webnovelarchiver.feature.details

import com.vinicius741.webnovelarchiver.domain.model.TtsSession
import com.vinicius741.webnovelarchiver.tts.TtsDescriptionPlanning
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackSnapshot
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailsDescriptionTtsTest {
    @Test
    fun seedPrefersLiveDescriptionSnapshotForThisStory() {
        val live = snapshot(storyId = "s1", chapterId = TtsDescriptionPlanning.DESCRIPTION_CHAPTER_ID, isPaused = false)

        val seeded =
            detailsDescriptionSnapshotSeed(
                persisted = null,
                update = TtsPlaybackUpdate(snapshot = live, isAuthoritative = true),
                storyId = "s1",
            )

        assertEquals(live, seeded)
    }

    @Test
    fun seedIgnoresChapterSessionsAndOtherStories() {
        val chapterSession = snapshot(storyId = "s1", chapterId = "c1")
        val otherStory = snapshot(storyId = "s2", chapterId = TtsDescriptionPlanning.DESCRIPTION_CHAPTER_ID)

        assertNull(
            detailsDescriptionSnapshotSeed(null, TtsPlaybackUpdate(chapterSession, isAuthoritative = true), "s1"),
        )
        assertNull(
            detailsDescriptionSnapshotSeed(null, TtsPlaybackUpdate(otherStory, isAuthoritative = true), "s1"),
        )
    }

    @Test
    fun seedFallsBackToPersistedDescriptionSessionBeforeEngineHydration() {
        val persisted =
            TtsSession(
                storyId = "s1",
                chapterId = TtsDescriptionPlanning.DESCRIPTION_CHAPTER_ID,
                isPaused = true,
                wasPlaying = false,
            )

        val seeded =
            detailsDescriptionSnapshotSeed(
                persisted = persisted,
                update = TtsPlaybackUpdate(snapshot = null, isAuthoritative = false),
                storyId = "s1",
            )

        assertTrue(seeded?.isPaused == true)
        assertEquals(TtsDescriptionPlanning.DESCRIPTION_CHAPTER_ID, seeded?.chapterId)
    }

    @Test
    fun seedWithoutPersistedSessionOrLiveStateIsNull() {
        assertNull(
            detailsDescriptionSnapshotSeed(null, TtsPlaybackUpdate(snapshot = null, isAuthoritative = false), "s1"),
        )
        // A persisted session for another story never seeds this screen.
        assertNull(
            detailsDescriptionSnapshotSeed(
                TtsSession(storyId = "s2", chapterId = TtsDescriptionPlanning.DESCRIPTION_CHAPTER_ID, wasPlaying = true),
                TtsPlaybackUpdate(snapshot = null, isAuthoritative = false),
                "s1",
            ),
        )
    }

    private fun snapshot(
        storyId: String,
        chapterId: String,
        isPaused: Boolean = false,
    ) = TtsPlaybackSnapshot(
        title = "Description",
        storyId = storyId,
        chapterId = chapterId,
        chunkIndex = 0,
        totalChunks = 3,
        isPlaying = !isPaused,
        isPaused = isPaused,
    )
}
