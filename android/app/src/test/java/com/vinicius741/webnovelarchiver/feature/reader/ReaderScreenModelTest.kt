package com.vinicius741.webnovelarchiver.feature.reader

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.DisplayPreferences
import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.TtsSession
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import com.vinicius741.webnovelarchiver.domain.model.TtsStoryPosition
import com.vinicius741.webnovelarchiver.feature.reader.ReaderContentRenderer.ReaderDocumentColors
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackSnapshot
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackUpdate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderScreenModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val story =
        Story(
            id = "s",
            title = "Story",
            sourceUrl = "https://source.test/s",
            chapters =
                mutableListOf(
                    chapter("c1", downloaded = true),
                    chapter("c2", downloaded = false),
                    chapter("c3", downloaded = true),
                    chapter("c4", downloaded = false),
                ),
        )
    private val data = FakeReaderData(story, dispatcher)
    private val playback = RecordingPlayback()

    private fun TestScope.model(chapterId: String = "c1") =
        ReaderScreenModel("s", chapterId, data, playback, dispatcher, CoroutineScope(SupervisorJob() + dispatcher))

    private fun TestScope.opened(chapterId: String = "c1"): ReaderScreenModel =
        model(chapterId).also {
            it.open(reload = true)
            runCurrent()
        }

    private fun TestScope.observe(model: ReaderScreenModel) {
        backgroundScope.launch(dispatcher) { model.state.collect {} }
        runCurrent()
    }

    private fun play(
        chapterId: String = "c1",
        paused: Boolean = false,
        rate: Float = 1f,
    ) {
        data.playback.value =
            TtsPlaybackUpdate(
                TtsPlaybackSnapshot(
                    title = "Chapter $chapterId",
                    storyId = "s",
                    chapterId = chapterId,
                    chunkIndex = 1,
                    totalChunks = 3,
                    isPlaying = !paused,
                    isPaused = paused,
                    rate = rate,
                ),
                isAuthoritative = true,
            )
    }

    @Test
    fun openingPreparesTheChapterAndReadsItsBookmarkAndDisplay() =
        runTest(dispatcher) {
            story.lastReadChapterId = "c1"
            data.display = DisplayPreferences(readerFontScale = 1.2f, readerDark = true)
            val model = model()
            assertEquals(ReaderPage.Loading, model.state.value.page)

            model.open(reload = true)
            runCurrent()

            val state = model.state.value
            assertEquals("c1", state.document?.chapter?.id)
            assertTrue(state.bookmarked)
            assertEquals(ReaderDisplay(1.2f, pureBlack = true), state.display)
            assertEquals(1, data.prepares)
        }

    @Test
    fun aReRenderKeepsThePreparedChapterAndReopeningPreparesItAgain() =
        runTest(dispatcher) {
            val model = opened()
            val first = model.state.value.page as ReaderPage.Ready

            model.open(reload = false)
            runCurrent()
            assertEquals(1, data.prepares)
            assertEquals(first, model.state.value.page)

            model.open(reload = true)
            runCurrent()
            assertEquals(2, data.prepares)
            assertTrue((model.state.value.page as ReaderPage.Ready).generation > first.generation)
        }

    @Test
    fun aReRenderDuringALoadDoesNotStartAnother() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            data.gate = gate
            val model = model()
            model.open(reload = true)
            runCurrent()
            model.open(reload = false)
            runCurrent()

            gate.complete(Unit)
            runCurrent()
            assertEquals(1, data.prepares)
            assertTrue(model.state.value.page is ReaderPage.Ready)
        }

    @Test
    fun aMissingChapterAndAFailedReadAreTheirOwnPages() =
        runTest(dispatcher) {
            assertEquals(ReaderPage.Missing, opened("gone").state.value.page)

            data.failure = java.io.IOException("disk")
            assertEquals(ReaderPage.Failed, opened().state.value.page)
        }

    @Test
    fun previousAndNextSkipChaptersThatAreNotDownloaded() =
        runTest(dispatcher) {
            val model = opened("c3")

            assertEquals("c1", model.chapterInDirection(-1))
            assertNull("nothing downloaded after c3", model.chapterInDirection(1))
            assertEquals("c3", opened("c1").chapterInDirection(1))
            assertNull(opened("c1").chapterInDirection(-1))
        }

    @Test
    fun neighborsFollowTheStoryAsItIsNow() =
        runTest(dispatcher) {
            val model = opened("c3")
            story.chapters[3].downloaded = true

            assertEquals("a chapter downloaded while reading is reachable", "c4", model.chapterInDirection(1))
        }

    @Test
    fun readableChapterWalksPastGapsAndStopsAtTheEnds() {
        val chapters = story.chapters
        assertEquals("c3", readableChapter(chapters, "c1", 1))
        assertEquals("c1", readableChapter(chapters, "c3", -1))
        assertNull(readableChapter(chapters, "c4", 1))
        assertNull(readableChapter(chapters, "missing", 1))
    }

    @Test
    fun togglingTheBookmarkUpdatesTheStateAndSaysSo() =
        runTest(dispatcher) {
            val model = opened()
            assertFalse(model.state.value.bookmarked)
            val messages = mutableListOf<String>()
            backgroundScope.launch(dispatcher) { model.messages.collect { messages += it } }

            model.toggleBookmark()
            runCurrent()
            assertTrue(model.state.value.bookmarked)

            model.toggleBookmark()
            runCurrent()
            assertFalse(model.state.value.bookmarked)
            assertEquals(listOf("Bookmarked", "Bookmark cleared"), messages)
            assertEquals(listOf("c1", "c1"), data.bookmarkToggles)
        }

    @Test
    fun theDockShowsWhileThisChapterIsReadAloud() =
        runTest(dispatcher) {
            val model = opened()
            observe(model)
            assertFalse(model.state.value.dockVisible)

            play()
            runCurrent()
            assertTrue(model.state.value.dockVisible)
            assertEquals(
                1,
                model.state.value.playing
                    ?.chunkIndex,
            )

            play(chapterId = "c3")
            runCurrent()
            assertFalse("playback moved to another chapter", model.state.value.dockVisible)

            play()
            runCurrent()
            data.playback.value = TtsPlaybackUpdate(null, isAuthoritative = true)
            runCurrent()
            assertFalse("playback stopped", model.state.value.dockVisible)
        }

    @Test
    fun aNonAuthoritativeUpdateKeepsThePersistedSessionsDock() =
        runTest(dispatcher) {
            data.session = TtsSession(storyId = "s", chapterId = "c1", chapterTitle = "Chapter c1", currentChunkIndex = 0, isPaused = true)
            val model = opened()
            observe(model)

            assertTrue("seeded from the persisted session", model.state.value.dockVisible)
            assertTrue(
                model.state.value.playing
                    ?.isPaused == true,
            )
        }

    @Test
    fun theDockPlayPauseResumesASeededSessionAndPausesALiveOne() =
        runTest(dispatcher) {
            data.session =
                TtsSession(
                    storyId = "s",
                    chapterId = "c1",
                    chapterTitle = "Chapter c1",
                    currentChunkIndex = 0,
                    isPaused = false,
                    wasPlaying = true,
                )
            val model = opened()
            observe(model)

            model.dockPlayPause()
            assertEquals("no live session yet: a playing seed resumes", listOf("resume"), playback.calls)

            play()
            runCurrent()
            model.dockPlayPause()
            assertEquals("pause", playback.calls.last())

            play(paused = true, rate = 1.25f)
            runCurrent()
            model.dockPlayPause()
            model.cycleRate()
            model.skipBack()
            model.stopPlayback()
            assertEquals(listOf("resume", "pause", "resume", "rate 1.5", "back", "stop"), playback.calls)
        }

    @Test
    fun readingAloudPlaysHereWhenTheSavedPlaceIsInThisChapter() =
        runTest(dispatcher) {
            data.position = TtsStoryPosition(storyId = "s", chapterId = "c1", currentChunkIndex = 4)
            val model = opened()

            model.startReadAloud()
            runCurrent()
            assertEquals(listOf("place s c1"), playback.calls)
        }

    @Test
    fun readingAloudAsksWhenTheSavedPlaceIsInAnotherChapter() =
        runTest(dispatcher) {
            data.position = TtsStoryPosition(storyId = "s", chapterId = "c3", currentChunkIndex = 4)
            val model = opened()

            model.startReadAloud()
            runCurrent()
            val choice = model.readAloudChoices.first()
            assertEquals(ReadAloudChoice("c3", "Chapter c3", savedSentence = 5), choice)
            assertTrue(playback.calls.isEmpty())

            model.continueListening(choice)
            model.listenFromThisChapter()
            assertEquals(listOf("continue s c3 c1", "beginning s c1"), playback.calls)
        }

    @Test
    fun aNewTextSizeIsSavedAndRefreshesThePageWithoutReloadingIt() =
        runTest(dispatcher) {
            val model = opened()
            val before = model.state.value.page as ReaderPage.Ready

            model.updateDisplay(ReaderDisplay(1.5f, pureBlack = false))
            runCurrent()

            val after = model.state.value.page as ReaderPage.Ready
            assertEquals(before.generation, after.generation)
            assertTrue(after.document.webViewHtml.contains("font-size: 27px"))
            assertEquals(listOf(ReaderDisplay(1.5f, false)), data.saved)
            assertEquals(1, data.prepares)
        }

    @Test
    fun aHeldStepperStoresOnlyTheLatestSize() =
        runTest(dispatcher) {
            val model = opened()

            model.updateDisplay(ReaderDisplay(1.1f, false))
            model.updateDisplay(ReaderDisplay(1.2f, false))
            model.updateDisplay(ReaderDisplay(1.3f, false))
            runCurrent()

            assertEquals(ReaderDisplay(1.3f, false), data.saved.last())
            assertFalse("a value overtaken before its turn is not stored", ReaderDisplay(1.2f, false) in data.saved)
        }

    @Test
    fun pureBlackReloadsTheChapterAfterItIsSaved() =
        runTest(dispatcher) {
            val model = opened()
            val before = model.state.value.page as ReaderPage.Ready

            model.updateDisplay(ReaderDisplay(1f, pureBlack = true))
            runCurrent()

            assertEquals(listOf(ReaderDisplay(1f, true)), data.saved)
            assertEquals(2, data.prepares)
            val after = model.state.value.page as ReaderPage.Ready
            assertTrue(after.generation > before.generation)
            assertTrue(after.document.webViewHtml.contains("#000000"))
        }

    @Test
    fun theVoiceSummaryPrefersTheLiveSpeed() =
        runTest(dispatcher) {
            val model = opened()
            assertEquals("Heart · 1x", model.voiceSummary())

            play(rate = 1.5f)
            assertEquals("Heart · 1.5x", model.voiceSummary())
        }

    private class FakeReaderData(
        private val story: Story,
        private val dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ) : ReaderData {
        override val playback = MutableStateFlow(TtsPlaybackUpdate(null, isAuthoritative = false))
        var display = DisplayPreferences()
        var session: TtsSession? = null
        var position: TtsStoryPosition? = null
        var failure: java.io.IOException? = null
        var gate: CompletableDeferred<Unit>? = null
        var prepares = 0
        val bookmarkToggles = mutableListOf<String>()
        val saved = mutableListOf<ReaderDisplay>()

        override fun story(id: String): Story? = story.takeIf { it.id == id }

        override suspend fun prepare(
            storyId: String,
            chapterId: String,
        ): ReaderPreparation {
            prepares++
            gate?.await()
            failure?.let { return ReaderPreparation.Failed(it) }
            return ReaderDocumentPreparer(Source(), dispatcher, dispatcher).prepare(storyId, chapterId, PALETTE)
        }

        override suspend fun toggleBookmark(
            storyId: String,
            chapterId: String,
        ): Story {
            bookmarkToggles += chapterId
            story.lastReadChapterId = if (story.lastReadChapterId == chapterId) null else chapterId
            return story
        }

        override suspend fun saveDisplay(display: ReaderDisplay) {
            saved += display
            this.display = this.display.copy(readerFontScale = display.fontScale, readerDark = display.pureBlack)
        }

        override suspend fun listenPosition(storyId: String): TtsStoryPosition? = position

        override fun voiceLabel(): String = "Heart"

        override fun savedRate(): Float = 1f

        private inner class Source : ReaderDocumentSource {
            override fun story(id: String): Story? = this@FakeReaderData.story(id)

            override suspend fun chapterHtml(chapter: Chapter): String? = chapter.content

            override fun ttsSettings() = TtsSettings()

            override fun regexRules(): List<RegexCleanupRule> = emptyList()

            override fun displayPreferences() = display.copy()

            override fun ttsSession(): TtsSession? = session
        }
    }

    private class RecordingPlayback : ReaderPlayback {
        val calls = mutableListOf<String>()

        override fun playFromPlace(
            storyId: String,
            chapterId: String,
        ) {
            calls += "place $storyId $chapterId"
        }

        override fun continueFrom(
            storyId: String,
            savedChapterId: String,
            readerChapterId: String,
        ) {
            calls += "continue $storyId $savedChapterId $readerChapterId"
        }

        override fun playFromBeginning(
            storyId: String,
            chapterId: String,
        ) {
            calls += "beginning $storyId $chapterId"
        }

        override fun resumeSession() {
            calls += "resume"
        }

        override fun pause() {
            calls += "pause"
        }

        override fun skipBack() {
            calls += "back"
        }

        override fun setRate(rate: Float) {
            calls += "rate $rate"
        }

        override fun stop() {
            calls += "stop"
        }
    }

    private companion object {
        val PALETTE =
            ReaderDocumentPalette(
                normal = ReaderDocumentColors("#13110D", "#E6DED0"),
                forcedDark = ReaderDocumentColors("#000000", "#E6DED0"),
            )

        fun chapter(
            id: String,
            downloaded: Boolean,
        ) = Chapter(id = id, title = "Chapter $id", content = "<p>Text of $id. Second sentence.</p>").apply { this.downloaded = downloaded }
    }
}
