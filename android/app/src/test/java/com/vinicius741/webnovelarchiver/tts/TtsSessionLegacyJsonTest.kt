package com.vinicius741.webnovelarchiver.tts

import com.google.gson.Gson
import com.vinicius741.webnovelarchiver.domain.model.TtsSession
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Legacy persisted sessions (written before `storyTitle` existed) must decode with the field's
 * constructor default instead of crashing or nulling the session — the standard Gson + no-arg-ctor
 * migration for domain models.
 */
class TtsSessionLegacyJsonTest {
    @Test
    fun sessionWithoutStoryTitleDecodesWithDefault() {
        val legacyJson =
            """
            {
              "storyId": "s1",
              "chapterId": "c1",
              "chapterTitle": "Chapter One",
              "currentChunkIndex": 3,
              "isPaused": true,
              "wasPlaying": false,
              "rate": 1.25,
              "pitch": 1.0,
              "updatedAt": 1700000000000,
              "sessionVersion": 1
            }
            """.trimIndent()

        val session = Gson().fromJson(legacyJson, TtsSession::class.java)

        assertEquals("s1", session.storyId)
        assertEquals("", session.storyTitle)
        assertEquals("c1", session.chapterId)
        assertEquals(3, session.currentChunkIndex)
        assertEquals(true, session.isPaused)
        assertEquals(1.25f, session.rate)
    }
}
