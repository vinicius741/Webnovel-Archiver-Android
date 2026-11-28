package com.vinicius741.webnovelarchiver.tts

import com.vinicius741.webnovelarchiver.cleanup.TtsTextPreparation
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.TtsHistoryEntry
import com.vinicius741.webnovelarchiver.domain.model.TtsSession
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import com.vinicius741.webnovelarchiver.domain.model.TtsStoryPosition
import com.vinicius741.webnovelarchiver.narration.NarrationRecipe
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
            val source =
                FakeTtsPlaybackSource(
                    story,
                    TtsSession(storyId = "story", chapterId = "one", currentChunkIndex = 99, wasPlaying = true),
                )
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
                (
                    TtsPlaybackPreparer(source, dispatcher, dispatcher)
                        .nextChapter(TtsSession(storyId = "story", chapterId = "one")) as? NextChapterOutcome.Prepared
                )?.playback
                    ?: error("Expected next chapter")

            assertEquals(null, story.lastReadChapterId)
            assertEquals("two", prepared.chapter.id)
            assertEquals(listOf("Second."), prepared.chunks)
        }

    @Test
    fun audioOfOlderTextPlaysItsOwnSentences() =
        runTest {
            val story = Story(id = "story", chapters = mutableListOf(Chapter(id = "one", content = "<p>New first. Second.</p>")))
            val older =
                NarrationRecipe(
                    storyId = "story",
                    chapterId = "one",
                    chunkingVersion = TtsTextPreparation.CHUNKING_VERSION,
                    sentences = listOf("Old first.", "Second."),
                )
            val dispatcher = StandardTestDispatcher(testScheduler)

            val prepared =
                TtsPlaybackPreparer(FakeTtsPlaybackSource(story, null, recipe = older), dispatcher, dispatcher)
                    .prepare("story", "one") ?: error("Expected playback")

            assertEquals(listOf("Old first.", "Second."), prepared.chunks)
            assertEquals(true, prepared.olderText)
            // Same sentences, or another chunking version: the current text plays.
            val same = older.copy(sentences = listOf("New first.", "Second."))
            val current =
                TtsPlaybackPreparer(FakeTtsPlaybackSource(story, null, recipe = same), dispatcher, dispatcher)
                    .prepare("story", "one") ?: error("Expected playback")
            assertEquals(false, current.olderText)
            val stale = older.copy(chunkingVersion = TtsTextPreparation.CHUNKING_VERSION - 1)
            val text =
                TtsPlaybackPreparer(FakeTtsPlaybackSource(story, null, recipe = stale), dispatcher, dispatcher)
                    .prepare("story", "one") ?: error("Expected playback")
            assertEquals(listOf("New first.", "Second."), text.chunks)
        }

    @Test
    fun aGeneratedTitleTheTextRepeatsIsSkipped() =
        runTest {
            // Patreon posts open with a navigation line, then the title: packs made before that was
            // recognised hold a title clip that said the chapter number twice.
            val html = "<p>&lt;&lt; Chapter 85 | Table of Contents</p><p>-oOo- Chapter 86 -oOo-</p><p>He landed.</p>"
            val story = Story(id = "story", chapters = mutableListOf(Chapter(id = "one", title = "Chapter 86", content = html)))
            val sentences = listOf("<< Chapter 85 | Table of Contents", "-oOo- Chapter 86 -oOo-", "He landed.")
            val recipe =
                NarrationRecipe(
                    storyId = "story",
                    chapterId = "one",
                    chunkingVersion = TtsTextPreparation.CHUNKING_VERSION,
                    title = "Chapter 86",
                    sentences = sentences,
                )
            val dispatcher = StandardTestDispatcher(testScheduler)

            val prepared =
                TtsPlaybackPreparer(FakeTtsPlaybackSource(story, null, recipe = recipe), dispatcher, dispatcher)
                    .prepare("story", "one") ?: error("Expected playback")

            assertEquals(sentences, prepared.chunks)
            assertEquals(null, prepared.spokenTitle)
            // A title the text does not repeat still plays from the pack.
            val plain =
                Story(id = "story", chapters = mutableListOf(Chapter(id = "one", title = "Chapter 86", content = "<p>He landed.</p>")))
            val titled =
                TtsPlaybackPreparer(
                    FakeTtsPlaybackSource(plain, null, recipe = recipe.copy(sentences = listOf("He landed."))),
                    dispatcher,
                    dispatcher,
                ).prepare("story", "one") ?: error("Expected playback")
            assertEquals("Chapter 86", titled.spokenTitle)
        }

    @Test
    fun anOldDescriptionSessionIsNeitherPreparedNorResumed() =
        runTest {
            // Description read-aloud was removed; a session an older version saved must stay silent.
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
            val preparer = TtsPlaybackPreparer(source, dispatcher, dispatcher)

            assertEquals(null, preparer.prepare("story", TtsDescriptionPlanning.DESCRIPTION_CHAPTER_ID))
            assertEquals(null, preparer.resume())
        }

    @Test
    fun nextChapterNotDownloadedReportsUnpreparedSoPositionIsRetained() =
        runTest {
            // Listening while a download runs: chapter N+1 exists in the story but has no readable
            // content yet — that must never be reported as the end of the book.
            val story =
                Story(
                    id = "story",
                    chapters =
                        mutableListOf(
                            Chapter(id = "one", content = "<p>First.</p>", downloaded = true),
                            Chapter(id = "two", content = null, downloaded = false),
                            Chapter(id = "three", content = "<p>Third.</p>", downloaded = true),
                        ),
                )
            val source = FakeTtsPlaybackSource(story, null)
            val dispatcher = StandardTestDispatcher(testScheduler)

            val outcome =
                TtsPlaybackPreparer(source, dispatcher, dispatcher)
                    .nextChapter(TtsSession(storyId = "story", chapterId = "one"))

            assertEquals(NextChapterOutcome.Unprepared, outcome)
            // Playback never moves the bookmark.
            assertEquals(null, story.lastReadChapterId)
        }

    @Test
    fun nextChapterOnLastChapterReportsNoNextChapter() =
        runTest {
            val story =
                Story(
                    id = "story",
                    chapters =
                        mutableListOf(
                            Chapter(id = "one", content = "<p>First.</p>", downloaded = true),
                            Chapter(id = "two", content = "<p>Second.</p>", downloaded = true),
                        ),
                )
            val source = FakeTtsPlaybackSource(story, null)
            val dispatcher = StandardTestDispatcher(testScheduler)

            val outcome =
                TtsPlaybackPreparer(source, dispatcher, dispatcher)
                    .nextChapter(TtsSession(storyId = "story", chapterId = "two"))

            assertEquals(NextChapterOutcome.NoNextChapter, outcome)
        }

    @Test
    fun nextChapterWithBlankContentReportsUnpreparedSoPositionIsRetained() =
        runTest {
            // The next chapter's file resolves to whitespace: no speakable chunks, but the story
            // does not end here.
            val story =
                Story(
                    id = "story",
                    chapters =
                        mutableListOf(
                            Chapter(id = "one", content = "<p>First.</p>", downloaded = true),
                            Chapter(id = "two", content = "   ", downloaded = true),
                        ),
                )
            val source = FakeTtsPlaybackSource(story, null)
            val dispatcher = StandardTestDispatcher(testScheduler)

            val outcome =
                TtsPlaybackPreparer(source, dispatcher, dispatcher)
                    .nextChapter(TtsSession(storyId = "story", chapterId = "one"))

            assertEquals(NextChapterOutcome.Unprepared, outcome)
        }

    @Test
    fun nextChapterWithUnknownCurrentChapterReportsUnpreparedNotEnd() =
        runTest {
            // The session's chapter fell out of the story's list; that is ambiguity, not a proven
            // end of story, so the saved position must stay.
            val story =
                Story(
                    id = "story",
                    chapters =
                        mutableListOf(
                            Chapter(id = "one", content = "<p>First.</p>", downloaded = true),
                            Chapter(id = "two", content = "<p>Second.</p>", downloaded = true),
                        ),
                )
            val source = FakeTtsPlaybackSource(story, null)
            val dispatcher = StandardTestDispatcher(testScheduler)

            val outcome =
                TtsPlaybackPreparer(source, dispatcher, dispatcher)
                    .nextChapter(TtsSession(storyId = "story", chapterId = "ghost"))

            assertEquals(NextChapterOutcome.Unprepared, outcome)
        }

    @Test
    fun prepareWithoutExplicitIndexResumesStoryPositionFromItsChapter() =
        runTest {
            // Podcast resume: "Read aloud" lands on the story's saved chapter + chunk, even when the
            // user was looking at a different chapter when they tapped.
            val story =
                Story(
                    id = "story",
                    chapters =
                        mutableListOf(
                            Chapter(id = "one", content = "<p>First. Second.</p>", downloaded = true),
                            Chapter(id = "two", content = "<p>Third. Fourth.</p>", downloaded = true),
                        ),
                )
            val source =
                FakeTtsPlaybackSource(
                    story,
                    null,
                    TtsStoryPosition(storyId = "story", chapterId = "two", currentChunkIndex = 1),
                )
            val dispatcher = StandardTestDispatcher(testScheduler)

            val prepared = TtsPlaybackPreparer(source, dispatcher, dispatcher).prepare("story", "one") ?: error("Expected resume")

            assertEquals("two", prepared.chapter.id)
            assertEquals(1, prepared.startIndex)
        }

    @Test
    fun prepareWithoutExplicitIndexFallsBackToRequestedChapterWhenPositionIsStale() =
        runTest {
            val story =
                Story(
                    id = "story",
                    chapters = mutableListOf(Chapter(id = "one", content = "<p>First. Second.</p>", downloaded = true)),
                )
            // "two" no longer exists; the viewed chapter plays from the top.
            val source =
                FakeTtsPlaybackSource(story, null, TtsStoryPosition(storyId = "story", chapterId = "two", currentChunkIndex = 1))
            val dispatcher = StandardTestDispatcher(testScheduler)

            val prepared = TtsPlaybackPreparer(source, dispatcher, dispatcher).prepare("story", "one") ?: error("Expected playback")

            assertEquals("one", prepared.chapter.id)
            assertEquals(0, prepared.startIndex)
        }

    @Test
    fun prepareWithExplicitIndexIgnoresStoryPosition() =
        runTest {
            val story =
                Story(
                    id = "story",
                    chapters = mutableListOf(Chapter(id = "one", content = "<p>First. Second.</p>", downloaded = true)),
                )
            val source =
                FakeTtsPlaybackSource(story, null, TtsStoryPosition(storyId = "story", chapterId = "one", currentChunkIndex = 1))
            val dispatcher = StandardTestDispatcher(testScheduler)

            val prepared = TtsPlaybackPreparer(source, dispatcher, dispatcher).prepare("story", "one", 0) ?: error("Expected playback")

            assertEquals(0, prepared.startIndex)
        }

    @Test
    fun chapterAtSkipsForwardAndMarksCurrentChapterRead() =
        runTest {
            val story =
                Story(
                    id = "story",
                    chapters =
                        mutableListOf(
                            Chapter(id = "one", content = "<p>First.</p>", downloaded = true),
                            Chapter(id = "two", content = "<p>Second.</p>", downloaded = true),
                        ),
                )
            val source = FakeTtsPlaybackSource(story, null)
            val dispatcher = StandardTestDispatcher(testScheduler)

            val prepared =
                TtsPlaybackPreparer(source, dispatcher, dispatcher)
                    .chapterAt(TtsSession(storyId = "story", chapterId = "one"), 1)
                    ?: error("Expected next chapter")

            assertEquals(null, story.lastReadChapterId)
            assertEquals("two", prepared.chapter.id)
            assertEquals(0, prepared.startIndex)
        }

    @Test
    fun chapterAtSkipsBackwardWithoutMovingBookmark() =
        runTest {
            val story =
                Story(
                    id = "story",
                    chapters =
                        mutableListOf(
                            Chapter(id = "one", content = "<p>First.</p>", downloaded = true),
                            Chapter(id = "two", content = "<p>Second.</p>", downloaded = true),
                        ),
                )
            val source = FakeTtsPlaybackSource(story, null)
            val dispatcher = StandardTestDispatcher(testScheduler)

            val prepared =
                TtsPlaybackPreparer(source, dispatcher, dispatcher)
                    .chapterAt(TtsSession(storyId = "story", chapterId = "two"), -1)
                    ?: error("Expected previous chapter")

            assertEquals(null, story.lastReadChapterId)
            assertEquals("one", prepared.chapter.id)
        }

    @Test
    fun chapterAtEdgeReturnsNull() =
        runTest {
            val story = Story(id = "story", chapters = mutableListOf(Chapter(id = "one", content = "<p>First.</p>", downloaded = true)))
            val source = FakeTtsPlaybackSource(story, null)
            val dispatcher = StandardTestDispatcher(testScheduler)

            val forward = TtsPlaybackPreparer(source, dispatcher, dispatcher).chapterAt(TtsSession(storyId = "story", chapterId = "one"), 1)
            val backward =
                TtsPlaybackPreparer(
                    source,
                    dispatcher,
                    dispatcher,
                ).chapterAt(TtsSession(storyId = "story", chapterId = "one"), -1)

            assertEquals(null, forward)
            assertEquals(null, backward)
        }

    @Test
    fun pinnedStartAwayFromTheSavedPlaceKeepsItInTheHistory() =
        runTest {
            val story =
                Story(
                    id = "story",
                    chapters =
                        mutableListOf(
                            Chapter(id = "one", title = "One", content = "<p>First. Second.</p>", downloaded = true),
                            Chapter(id = "two", title = "Two", content = "<p>Far along. Further.</p>", downloaded = true),
                        ),
                )
            val position = TtsStoryPosition(storyId = "story", chapterId = "two", chapterTitle = "Two", currentChunkIndex = 1)
            val dispatcher = StandardTestDispatcher(testScheduler)

            val prepared =
                TtsPlaybackPreparer(FakeTtsPlaybackSource(story, null, position), dispatcher, dispatcher)
                    .prepare("story", "one", requestedIndex = 0) ?: error("Expected playback")

            assertEquals("one", prepared.chapter.id)
            assertEquals(listOf("two" to 1), prepared.history.map { it.chapterId to it.chunkIndex })
        }

    @Test
    fun resumingTheSavedPlaceKeepsTheHistoryAsItWas() =
        runTest {
            val story =
                Story(
                    id = "story",
                    chapters = mutableListOf(Chapter(id = "one", title = "One", content = "<p>First. Second.</p>", downloaded = true)),
                )
            val earlier = TtsHistoryEntry(chapterId = "one", chunkIndex = 0, recordedAt = 1L)
            val position =
                TtsStoryPosition(
                    storyId = "story",
                    chapterId = "one",
                    currentChunkIndex = 1,
                    anchorText = "Second.",
                    history = listOf(earlier),
                )
            val dispatcher = StandardTestDispatcher(testScheduler)

            val prepared =
                TtsPlaybackPreparer(FakeTtsPlaybackSource(story, null, position), dispatcher, dispatcher)
                    .prepare("story", "one") ?: error("Expected playback")

            assertEquals(1, prepared.startIndex)
            assertEquals(listOf(earlier), prepared.history)
        }

    private class FakeTtsPlaybackSource(
        private val story: Story,
        private val persisted: TtsSession?,
        private val storyPosition: TtsStoryPosition? = null,
        private val recipe: NarrationRecipe? = null,
    ) : TtsPlaybackSource {
        override fun generatedRecipe(
            storyId: String,
            chapterId: String,
        ): NarrationRecipe? = recipe

        override fun story(id: String): Story? = story.takeIf { it.id == id }

        override suspend fun chapterHtml(
            storyId: String,
            chapter: Chapter,
        ): String? = chapter.content

        override fun settings() = TtsSettings()

        override fun regexRules(): List<RegexCleanupRule> = emptyList()

        override fun session(): TtsSession? = persisted

        override suspend fun position(storyId: String): TtsStoryPosition? = storyPosition
    }
}
