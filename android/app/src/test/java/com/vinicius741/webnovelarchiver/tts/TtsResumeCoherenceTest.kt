package com.vinicius741.webnovelarchiver.tts

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.TtsSession
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Regression coverage for the play-after-pause bug: the TTS write path ([TtsSessionStore]) and read
 * path ([TtsPlaybackPreparer.resume]) must share one owner so a paused session flushed by the store
 * is observable by the preparer. Before the fix, the store wrote to [AppStorage] only while the
 * preparer read a stale repository cache, so [TtsPlaybackPreparer.resume] returned `null` and
 * play-after-pause silently did nothing.
 *
 * Here both sides are wired to a single in-memory owner, mirroring the production wiring where the
 * repository owns both the cache (read) and the JSON file (write).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TtsResumeCoherenceTest {
    @Test
    fun pausedSessionFlushedThroughStoreIsResumableThroughPreparer() =
        runTest {
            val story =
                Story(
                    id = "story",
                    chapters =
                        mutableListOf(
                            Chapter(id = "one", content = "<p>First sentence. Second sentence.</p>"),
                            Chapter(id = "two", content = "<p>Next chapter.</p>"),
                        ),
                )
            val owner =
                SharedOwner(
                    story = story,
                    persisted = null,
                )
            val dispatcher = StandardTestDispatcher(testScheduler)
            // The same owner backs both the store (write) and the preparer (read).
            val store = TtsSessionStore(owner, dispatcher, debounceMs = 250L)
            val preparer = TtsPlaybackPreparer(owner, dispatcher, dispatcher)

            // Simulate the engine's pause path: flush a paused session mid-chapter.
            store.flush(
                TtsSession(
                    storyId = "story",
                    chapterId = "one",
                    chapterTitle = "Chapter One",
                    currentChunkIndex = 0,
                    isPaused = true,
                    wasPlaying = true,
                ),
            )

            val prepared = preparer.resume()
            assertNotNull("resume must see the session the store just flushed", prepared)
            prepared!!
            assertEquals("story", prepared.story.id)
            assertEquals("one", prepared.chapter.id)
            assertEquals(listOf("First sentence.", "Second sentence."), prepared.chunks)
            assertEquals(0, prepared.startIndex)
        }

    @Test
    fun clearedSessionIsNotResumable() =
        runTest {
            val story = Story(id = "story", chapters = mutableListOf(Chapter(id = "one", content = "<p>Hi.</p>")))
            val owner = SharedOwner(story, persisted = null)
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = TtsSessionStore(owner, dispatcher, debounceMs = 250L)
            val preparer = TtsPlaybackPreparer(owner, dispatcher, dispatcher)

            store.flush(TtsSession(storyId = "story", chapterId = "one", isPaused = true, wasPlaying = true))
            store.clear()

            assertEquals(null, preparer.resume())
        }

    /** One object is both the preparer's [TtsPlaybackSource] and the store's [TtsSessionPersistence]. */
    private class SharedOwner(
        private val story: Story,
        private var persisted: TtsSession?,
    ) : TtsPlaybackSource,
        TtsSessionPersistence {
        override fun story(id: String): Story? = story.takeIf { it.id == id }

        override fun chapterHtml(chapter: Chapter): String? = chapter.content

        override fun settings(): TtsSettings = TtsSettings()

        override fun regexRules(): List<RegexCleanupRule> = emptyList()

        override fun session(): TtsSession? = persisted

        override suspend fun markChapterRead(
            storyId: String,
            chapterId: String,
        ) {
            story.lastReadChapterId = chapterId
        }

        override suspend fun save(session: TtsSession) {
            persisted = session.copy()
        }

        override suspend fun clear() {
            persisted = null
        }
    }
}
