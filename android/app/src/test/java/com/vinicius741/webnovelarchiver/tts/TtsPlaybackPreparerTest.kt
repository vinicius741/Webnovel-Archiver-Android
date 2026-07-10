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

    private class FakeTtsPlaybackSource(
        private val story: Story,
        private val persisted: TtsSession?,
    ) : TtsPlaybackSource {
        var markedStoryId: String? = null
        var markedChapterId: String? = null

        override fun story(id: String): Story? = story.takeIf { it.id == id }

        override fun chapterHtml(chapter: Chapter): String? = chapter.content

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
