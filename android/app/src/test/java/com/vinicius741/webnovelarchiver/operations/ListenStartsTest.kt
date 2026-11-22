package com.vinicius741.webnovelarchiver.operations

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.TtsStoryPosition
import com.vinicius741.webnovelarchiver.narration.NarrationJob
import com.vinicius741.webnovelarchiver.narration.NarrationJobState
import com.vinicius741.webnovelarchiver.narration.NarrationQueueDocument
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackSnapshot
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackUpdate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListenStartsTest {
    private val dispatcher = StandardTestDispatcher()
    private val story =
        Story(
            id = "s",
            sourceUrl = "https://source.test/s",
            chapters = mutableListOf(Chapter(id = "c1", downloaded = true), Chapter(id = "c2", downloaded = true)),
        )
    private val data = FakeStartData(story)

    private fun TestScope.ui() = RecordingUi(backgroundScope)

    private fun starts() = ListenStarts(data, io = dispatcher)

    @Test
    fun generatedAudioPlaysAtOnce() =
        runTest(dispatcher) {
            data.ready += "c1"
            val ui = ui()
            var started = false

            starts().playIfGenerated(ui, "s", "c1") { started = true }
            runCurrent()

            assertTrue(started)
            assertTrue(data.enqueued.isEmpty())
            assertTrue(ui.events.isEmpty())
        }

    @Test
    fun missingAudioIsQueuedFirstAndPlaysOnceReady() =
        runTest(dispatcher) {
            val ui = ui()
            var started = 0

            starts().playIfGenerated(ui, "s", "c1") { started++ }
            runCurrent()
            assertEquals(listOf("c1"), data.enqueued)
            assertEquals(listOf("toast Generating \"Title c1\". It plays when it's ready.", "permission"), ui.events)
            assertEquals(0, started)

            data.queue.value = NarrationQueueDocument(jobs = listOf(NarrationJob(id = "j", storyId = "s", chapterId = "c1")))
            runCurrent()
            assertEquals(0, started)

            data.ready += "c1"
            data.packsRevision.value = 1
            runCurrent()
            assertEquals(1, started)
            assertEquals("foreground", ui.events.last())
        }

    @Test
    fun aFailedJobGivesUpTheWait() =
        runTest(dispatcher) {
            data.queue.value = NarrationQueueDocument(jobs = listOf(NarrationJob(id = "j", storyId = "s", chapterId = "c1")))
            var started = false

            starts().playIfGenerated(ui(), "s", "c1") { started = true }
            runCurrent()
            data.queue.value =
                NarrationQueueDocument(
                    jobs = listOf(NarrationJob(id = "j", storyId = "s", chapterId = "c1", state = NarrationJobState.FAILED)),
                )
            runCurrent()
            data.ready += "c1"
            data.packsRevision.value = 1
            runCurrent()

            assertTrue(!started)
        }

    @Test
    fun aNewerRequestReplacesTheWaitingOne() =
        runTest(dispatcher) {
            data.queue.value =
                NarrationQueueDocument(
                    jobs =
                        listOf(
                            NarrationJob(id = "1", storyId = "s", chapterId = "c1"),
                            NarrationJob(id = "2", storyId = "s", chapterId = "c2"),
                        ),
                )
            val starts = starts()
            val played = mutableListOf<String>()

            starts.playIfGenerated(ui(), "s", "c1") { played += "c1" }
            runCurrent()
            starts.playIfGenerated(ui(), "s", "c2") { played += "c2" }
            runCurrent()
            data.ready += listOf("c1", "c2")
            data.packsRevision.value = 1
            runCurrent()

            assertEquals(listOf("c2"), played)
        }

    @Test
    fun aQueueFailureIsReported() =
        runTest(dispatcher) {
            data.enqueueFails = true
            val ui = ui()

            starts().playIfGenerated(ui, "s", "c1") {}
            runCurrent()

            assertEquals(listOf("toast Could not generate the audio"), ui.events)
        }

    @Test
    fun playChapterStartsFromTheSavedPlaceOnlyInsideThatChapter() =
        runTest(dispatcher) {
            data.ready += listOf("c1", "c2")
            data.position = TtsStoryPosition(storyId = "s", chapterId = "c2", currentChunkIndex = 5)
            val ui = ui()
            val starts = starts()

            starts.playChapter(ui, "s", "c2")
            runCurrent()
            starts.playChapter(ui, "s", "c1")
            runCurrent()

            assertEquals(listOf("start c2", "beginning c1"), ui.events)
        }

    @Test
    fun continueListeningResumesALoadedStoryInPlace() =
        runTest(dispatcher) {
            data.playback.value = TtsPlaybackUpdate(snapshot(isPlaying = false), isAuthoritative = true)
            val ui = ui()

            starts().continueListening(ui, "s", "c1")
            data.playback.value = TtsPlaybackUpdate(snapshot(isPlaying = true), isAuthoritative = true)
            starts().continueListening(ui, "s", "c1")

            assertEquals(listOf("resume", "toast Already playing"), ui.events)
        }

    @Test
    fun continueListeningPlaysTheSavedPlaceOrSaysWhyNot() =
        runTest(dispatcher) {
            data.ready += listOf("c1", "c2")
            data.position = TtsStoryPosition(storyId = "s", chapterId = "c1", caughtUp = true)
            val ui = ui()
            val starts = starts()

            starts.continueListening(ui, "s", "c1")
            runCurrent()
            data.position = TtsStoryPosition(storyId = "s", chapterId = "c2", caughtUp = true)
            starts.continueListening(ui, "s", "c1")
            runCurrent()

            assertEquals(listOf("start c2", "toast You've listened to the end. Nothing new yet."), ui.events)
        }

    @Test
    fun supersedingAQueueingRequestDoesNotToastAnError() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            data.enqueueGate = gate
            data.ready += "c2"
            val ui = ui()
            val starts = starts()
            val started = mutableListOf<String>()

            starts.playIfGenerated(ui, "s", "c1") { started += "c1" }
            runCurrent()
            starts.playIfGenerated(ui, "s", "c2") { started += "c2" }
            gate.complete(Unit)
            runCurrent()

            assertEquals(listOf("c2"), started)
            assertTrue("cancelling enqueue must not report a failure: ${ui.events}", ui.events.isEmpty())
        }

    @Test
    fun supersedingPlayChapterCancelsTheOlderPositionLookup() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            data.positionGate = gate
            data.ready += listOf("c1", "c2")
            val ui = ui()
            val starts = starts()

            starts.playChapter(ui, "s", "c1")
            runCurrent()
            data.positionGate = null
            starts.playChapter(ui, "s", "c2")
            gate.complete(Unit)
            runCurrent()

            assertEquals(listOf("beginning c2"), ui.events)
        }

    private fun snapshot(isPlaying: Boolean) =
        TtsPlaybackSnapshot(
            title = "Chapter",
            storyId = "s",
            chapterId = "c1",
            chunkIndex = 0,
            totalChunks = 1,
            isPlaying = isPlaying,
            isPaused = !isPlaying,
        )
}

private class FakeStartData(
    private val story: Story,
) : ListenStartData {
    override val packsRevision = MutableStateFlow(0L)
    override val queue = MutableStateFlow(NarrationQueueDocument())
    override val playback = MutableStateFlow(TtsPlaybackUpdate(null, isAuthoritative = true))
    val ready = mutableSetOf<String>()
    val enqueued = mutableListOf<String>()
    var enqueueFails = false
    var enqueueGate: CompletableDeferred<Unit>? = null
    var position: TtsStoryPosition? = null
    var positionGate: CompletableDeferred<Unit>? = null

    override suspend fun isReady(
        storyId: String,
        chapterId: String,
    ) = chapterId in ready

    override suspend fun enqueueFirst(
        storyId: String,
        chapterId: String,
    ) {
        enqueueGate?.await()
        if (enqueueFails) error("queue unreadable")
        enqueued += chapterId
        queue.value = queue.value.copy(jobs = queue.value.jobs + NarrationJob(id = chapterId, storyId = storyId, chapterId = chapterId))
    }

    override fun chapterTitle(
        storyId: String,
        chapterId: String,
    ) = "Title $chapterId"

    override suspend fun position(storyId: String): TtsStoryPosition? {
        positionGate?.await()
        return position
    }

    override fun story(storyId: String) = story.takeIf { it.id == storyId }
}

private class RecordingUi(
    override val scope: CoroutineScope,
) : ListenStartUi {
    val events = mutableListOf<String>()

    override fun toast(text: String) {
        events += "toast $text"
    }

    override fun requestNotificationPermission() {
        events += "permission"
    }

    override suspend fun awaitForeground() {
        events += "foreground"
    }

    override fun start(
        storyId: String,
        chapterId: String,
    ) {
        events += "start $chapterId"
    }

    override fun startFromBeginning(
        storyId: String,
        chapterId: String,
    ) {
        events += "beginning $chapterId"
    }

    override fun resumeSession() {
        events += "resume"
    }
}
