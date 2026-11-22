package com.vinicius741.webnovelarchiver.feature.player

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.TtsHistoryEntry
import com.vinicius741.webnovelarchiver.tts.TtsContinueListening
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackSnapshot
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackUpdate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
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
class PlayerScreenModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val story =
        Story(
            id = "s",
            title = "Story",
            sourceUrl = "https://source.test/s",
            chapters = mutableListOf(Chapter(id = "c1"), Chapter(id = "c2")),
        )
    private val data = FakePlayerData(story)
    private val controls = RecordingControls()

    private fun TestScope.model() = PlayerScreenModel(data, controls, CoroutineScope(SupervisorJob() + dispatcher))

    private fun TestScope.observe(model: PlayerScreenModel) {
        backgroundScope.launch(dispatcher) { model.state.collect {} }
        runCurrent()
    }

    private fun play(
        chapterId: String = "c1",
        chunk: Int = 0,
        rate: Float = 1f,
    ) {
        data.playback.value =
            TtsPlaybackUpdate(
                TtsPlaybackSnapshot(
                    title = "Chapter $chapterId",
                    storyId = "s",
                    chapterId = chapterId,
                    chunkIndex = chunk,
                    totalChunks = 3,
                    isPlaying = true,
                    isPaused = false,
                    rate = rate,
                ),
                isAuthoritative = true,
            )
    }

    @Test
    fun onlyAnAuthoritativeStopClosesThePlayer() =
        runTest(dispatcher) {
            data.playback.value = TtsPlaybackUpdate(null, isAuthoritative = false)
            assertFalse("before the repository hydrates, nothing loaded is not a stop", model().state.value.stopped)

            data.playback.value = TtsPlaybackUpdate(null, isAuthoritative = true)
            assertTrue(model().state.value.stopped)
        }

    @Test
    fun aSnapshotMapsToTheStoryVoiceAndChapterKey() =
        runTest(dispatcher) {
            play(chunk = 1)
            val state = model().state.value

            assertEquals("s" to "c1", state.chapterKey)
            assertEquals("Story", state.story?.title)
            assertEquals("Voice c1", state.voice)
            assertFalse(state.stopped)
            assertTrue("sentences load only while a renderer collects", state.sentences.isEmpty())
        }

    @Test
    fun sentencesLoadForThePlayingChapterAndReloadWhenItChanges() =
        runTest(dispatcher) {
            play()
            val model = model()
            observe(model)
            assertEquals(listOf("c1 one", "c1 two"), model.state.value.sentences)

            play(chunk = 1)
            runCurrent()
            assertEquals(1, data.sentenceLoads.size)

            play(chapterId = "c2")
            runCurrent()
            assertEquals("s" to "c2", model.state.value.chapterKey)
            assertEquals(listOf("c2 one", "c2 two"), model.state.value.sentences)
            assertEquals(listOf("c1", "c2"), data.sentenceLoads)
            assertEquals(1, data.storyLookups)
        }

    @Test
    fun aSentenceLoadCutShortByAStopRunsAgainWhenTheChapterResumes() =
        runTest(dispatcher) {
            data.sentenceGate = CompletableDeferred()
            play()
            val model = model()
            observe(model)
            assertEquals("still loading", emptyList<String>(), model.state.value.sentences)

            data.playback.value = TtsPlaybackUpdate(null, isAuthoritative = true)
            runCurrent()
            data.sentenceGate = null
            play()
            runCurrent()
            assertEquals(listOf("c1 one", "c1 two"), model.state.value.sentences)
            assertEquals(listOf("c1", "c1"), data.sentenceLoads)
        }

    @Test
    fun withNothingPlayingTheSavedPlaceIsOfferedAndResumed() =
        runTest(dispatcher) {
            data.place = ContinuePlace(TtsContinueListening.Target(story, story.chapters[1]), null)
            val model = model()
            assertNull(model.state.value.continuePlace)

            observe(model)
            assertEquals(
                "c2",
                model.state.value.continuePlace
                    ?.target
                    ?.chapter
                    ?.id,
            )

            model.continueListening()
            assertEquals(listOf("resume s c2"), controls.calls)
        }

    @Test
    fun transportSpeedTimerAndHistoryGoThroughTheControls() =
        runTest(dispatcher) {
            play(rate = 1.25f)
            val model = model()
            val entry = TtsHistoryEntry(chapterId = "c1", chapterTitle = "Chapter c1")

            model.playPause()
            model.skipBack()
            model.skipForward()
            model.previousChapter()
            model.nextChapter()
            model.seekTo(42_000L)
            model.cycleRate()
            model.setSleepTimer(15)
            model.jumpToHistory(entry)
            model.stop()

            assertEquals(
                listOf(
                    "playPause",
                    "skipBack",
                    "skipForward",
                    "previousChapter",
                    "nextChapter",
                    "seekTo 42000",
                    "setRate 1.5",
                    "setSleepTimer 15",
                    "jumpToHistory c1",
                    "stop",
                ),
                controls.calls,
            )
        }

    @Test
    fun theSpeedCycleWrapsAndNeedsSomethingPlaying() =
        runTest(dispatcher) {
            val model = model()
            model.cycleRate()
            assertTrue(controls.calls.isEmpty())

            play(rate = 2f)
            model.cycleRate()
            assertEquals(listOf("setRate 0.75"), controls.calls)
        }
}

private class FakePlayerData(
    private val story: Story,
) : PlayerData {
    override val playback = MutableStateFlow(TtsPlaybackUpdate(null, isAuthoritative = false))
    var place: ContinuePlace? = null
    var storyLookups = 0
    val sentenceLoads = mutableListOf<String>()

    /** While set, a sentence load waits on it. */
    var sentenceGate: CompletableDeferred<Unit>? = null

    override fun story(id: String): Story? {
        storyLookups++
        return story.takeIf { it.id == id }?.copy()
    }

    override fun packVoice(
        storyId: String,
        chapterId: String,
    ) = "Voice $chapterId"

    override suspend fun chapterSentences(
        storyId: String,
        chapterId: String,
    ): List<String> {
        sentenceLoads += chapterId
        sentenceGate?.await()
        return listOf("$chapterId one", "$chapterId two")
    }

    override suspend fun continuePlace() = place
}

/** Records each playback command as text. */
internal class RecordingControls : PlaybackControls {
    val calls = mutableListOf<String>()

    override fun playPause() {
        calls += "playPause"
    }

    override fun stop() {
        calls += "stop"
    }

    override fun skipBack() {
        calls += "skipBack"
    }

    override fun skipForward() {
        calls += "skipForward"
    }

    override fun previousChapter() {
        calls += "previousChapter"
    }

    override fun nextChapter() {
        calls += "nextChapter"
    }

    override fun seekTo(positionMs: Long) {
        calls += "seekTo $positionMs"
    }

    override fun setRate(rate: Float) {
        calls += "setRate $rate"
    }

    override fun setSleepTimer(minutes: Int) {
        calls += "setSleepTimer $minutes"
    }

    override fun jumpToHistory(entry: TtsHistoryEntry) {
        calls += "jumpToHistory ${entry.chapterId}"
    }

    override fun resume(
        storyId: String,
        chapterId: String,
    ) {
        calls += "resume $storyId $chapterId"
    }
}
