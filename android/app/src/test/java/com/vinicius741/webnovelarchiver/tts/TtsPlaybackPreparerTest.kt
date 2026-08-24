package com.vinicius741.webnovelarchiver.tts

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.TtsSession
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TtsPlaybackPreparerTest {
    @Test
    fun resumeLoadsAndChunksSessionThroughPreparationSeam() =
        runTest {
            val chapters =
                mutableListOf(
                    Chapter(id = "one", content = "<p>First sentence. Second sentence.</p>"),
                    Chapter(id = "two", content = "<p>Next chapter.</p>"),
                )
            val story = Story(id = "story", chapters = chapters)
            val source = FakeTtsPlaybackSource(story, TtsSession("story", "one", currentChunkIndex = 99, wasPlaying = true))
            val dispatcher = StandardTestDispatcher(testScheduler)
            val preparer = TtsPlaybackPreparer(source, dispatcher, dispatcher)

            val prepared = preparer.resume() ?: error("Expected resumable playback")

            assertEquals(listOf("First sentence.", "Second sentence."), prepared.chunks)
            assertEquals(prepared.chunks.lastIndex, prepared.startIndex)
        }

    @Test
    fun nextChapterPersistsProgressAndPreparesFollowingChapter() =
        runTest {
            val story =
                Story(
                    id = "story",
                    chapters =
                        mutableListOf(
                            Chapter(id = "one", content = "<p>First.</p>"),
                            Chapter(id = "two", content = "<p>Second.</p>"),
                        ),
                )
            val source = FakeTtsPlaybackSource(story, null)
            val dispatcher = StandardTestDispatcher(testScheduler)

            val prepared =
                TtsPlaybackPreparer(source, dispatcher, dispatcher)
                    .nextChapter(TtsSession(storyId = "story", chapterId = "one"))
                    ?: error("Expected next chapter")

            assertEquals("one", source.markedChapterId)
            assertEquals("story", source.markedStoryId)
            assertEquals("two", prepared.chapter.id)
            assertEquals(listOf("Second."), prepared.chunks)
        }

    @Test
    fun prepareSpeaksStoryDescriptionForSentinelChapterId() =
        runTest {
            val story =
                Story(
                    id = "story",
                    title = "Titled Story",
                    description = "A humble beginning. Then adventure.\n\nA second paragraph.",
                    chapters = mutableListOf(Chapter(id = "one", content = "<p>Never spoken.</p>")),
                )
            val source = FakeTtsPlaybackSource(story, null)
            val dispatcher = StandardTestDispatcher(testScheduler)

            val prepared =
                TtsPlaybackPreparer(source, dispatcher, dispatcher)
                    .prepare("story", TtsDescriptionPlanning.DESCRIPTION_CHAPTER_ID)
                    ?: error("Expected description playback")

            assertEquals(TtsDescriptionPlanning.DESCRIPTION_CHAPTER_ID, prepared.chapter.id)
            assertEquals(
                listOf("Titled Story", "A humble beginning.", "Then adventure.", "A second paragraph."),
                prepared.chunks,
            )
            assertEquals(0, prepared.startIndex)
        }

    @Test
    fun prepareReturnsNullForBlankDescription() =
        runTest {
            val story = Story(id = "story", description = "   ")
            val source = FakeTtsPlaybackSource(story, null)
            val dispatcher = StandardTestDispatcher(testScheduler)

            val prepared =
                TtsPlaybackPreparer(
                    source,
                    dispatcher,
                    dispatcher,
                ).prepare("story", TtsDescriptionPlanning.DESCRIPTION_CHAPTER_ID)

            assertEquals(null, prepared)
        }

    @Test
    fun resumeRestoresPausedDescriptionSession() =
        runTest {
            val story = Story(id = "story", description = "First sentence. Second sentence.")
            val session =
                TtsSession(
                    storyId = "story",
                    chapterId = TtsDescriptionPlanning.DESCRIPTION_CHAPTER_ID,
                    currentChunkIndex = 1,
                    isPaused = true,
                    wasPlaying = false,
                )
            val source = FakeTtsPlaybackSource(story, session)
            val dispatcher = StandardTestDispatcher(testScheduler)

            val prepared =
                TtsPlaybackPreparer(source, dispatcher, dispatcher).resume()
                    ?: error("Expected resumable description playback")

            assertEquals(listOf("First sentence.", "Second sentence."), prepared.chunks)
            assertEquals(1, prepared.startIndex)
        }

    @Test
    fun nextChapterFinishesDescriptionSessionWithoutMarkingRead() =
        runTest {
            val story =
                Story(
                    id = "story",
                    description = "Only description.",
                    chapters = mutableListOf(Chapter(id = "one", content = "<p>Chapter.</p>")),
                )
            val source = FakeTtsPlaybackSource(story, null)
            val dispatcher = StandardTestDispatcher(testScheduler)

            val prepared =
                TtsPlaybackPreparer(source, dispatcher, dispatcher)
                    .nextChapter(TtsSession(storyId = "story", chapterId = TtsDescriptionPlanning.DESCRIPTION_CHAPTER_ID))

            assertEquals(null, prepared)
            // The sentinel must never be persisted as the story's last-read chapter.
            assertEquals(null, source.markedChapterId)
            assertEquals(null, story.lastReadChapterId)
        }

    private class FakeTtsPlaybackSource(
        private val story: Story,
        private val persisted: TtsSession?,
    ) : TtsPlaybackSource {
        var markedStoryId: String? = null
        var markedChapterId: String? = null

        override fun story(id: String): Story? = story.takeIf { it.id == id }

        override suspend fun chapterHtml(chapter: Chapter): String? = chapter.content

        override fun settings() = TtsSettings()

        override fun regexRules(): List<RegexCleanupRule> = emptyList()

        override fun session(): TtsSession? = persisted

        override suspend fun markChapterRead(
            storyId: String,
            chapterId: String,
        ) {
            markedStoryId = storyId
            markedChapterId = chapterId
            story.lastReadChapterId = chapterId
        }
    }
}
