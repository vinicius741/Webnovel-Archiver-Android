package com.vinicius741.webnovelarchiver.feature.narration

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.feature.player.ContinuePlace
import com.vinicius741.webnovelarchiver.feature.player.RecordingControls
import com.vinicius741.webnovelarchiver.narration.NarrationCompressionStatus
import com.vinicius741.webnovelarchiver.narration.NarrationJob
import com.vinicius741.webnovelarchiver.narration.NarrationJobState
import com.vinicius741.webnovelarchiver.narration.NarrationPack
import com.vinicius741.webnovelarchiver.narration.NarrationPackIndex
import com.vinicius741.webnovelarchiver.narration.NarrationProgress
import com.vinicius741.webnovelarchiver.narration.NarrationQueueDocument
import com.vinicius741.webnovelarchiver.narration.NarrationRecipe
import com.vinicius741.webnovelarchiver.narration.NarrationServer
import com.vinicius741.webnovelarchiver.tts.TtsContinueListening
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackSnapshot
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackUpdate
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
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ListenScreenModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val alpha = Story(id = "a", title = "Alpha", sourceUrl = "https://source.test/a", chapters = mutableListOf(Chapter(id = "a1")))
    private val beta = Story(id = "b", title = "Beta", sourceUrl = "https://source.test/b")
    private val data = FakeListenData(listOf(alpha, beta))
    private val controls = RecordingControls()

    private fun TestScope.model(): ListenScreenModel =
        ListenScreenModel(data, controls, io = dispatcher, scope = CoroutineScope(SupervisorJob() + dispatcher))

    /** Collects the model's state, the way an attached renderer does. */
    private fun TestScope.observe(model: ListenScreenModel) {
        backgroundScope.launch(dispatcher) { model.state.collect {} }
        runCurrent()
    }

    private fun job(
        id: String,
        storyId: String,
        state: NarrationJobState = NarrationJobState.QUEUED,
    ) = NarrationJob(id = id, storyId = storyId, chapterId = "ch-$id", state = state)

    private fun snapshot(
        storyId: String = "a",
        chapterId: String = "a1",
        chunk: Int = 0,
    ) = TtsPlaybackSnapshot(
        title = "Chapter",
        storyId = storyId,
        chapterId = chapterId,
        chunkIndex = chunk,
        totalChunks = 10,
        isPlaying = true,
        isPaused = false,
    )

    @Test
    fun theQueueIsGroupedByStoryWithTitlesAndTheBannerIsStarting() =
        runTest(dispatcher) {
            data.queue.value = NarrationQueueDocument(jobs = listOf(job("1", "a"), job("2", "b", NarrationJobState.FAILED), job("3", "a")))

            val state = model().state.value

            assertEquals(listOf("a", "b"), state.content.cards.map { it.group.storyId })
            assertEquals(listOf(true, false), state.content.cards.map { it.isFirst })
            assertEquals(
                "Alpha",
                state.content.cards[0]
                    .story
                    ?.title,
            )
            assertEquals(mapOf("1" to "Title ch-1", "3" to "Title ch-3"), state.content.cards[0].chapterTitles)
            assertEquals(1, state.content.failed)
            assertEquals(GenerationBanner.State.STARTING, state.banner.state)
            assertEquals(ListenNow.Nothing, state.now)
        }

    @Test
    fun liveOnlyQueueFieldsAndProgressTicksKeepTheBuiltContent() =
        runTest(dispatcher) {
            data.queue.value = NarrationQueueDocument(jobs = listOf(job("1", "a")))
            val model = model()
            observe(model)
            val built = model.state.value.content

            data.queue.value = data.queue.value.copy(keepAhead = 7, speed = mapOf("kokoro" to 2.0), msPerChar = mapOf("kokoro" to 60.0))
            runCurrent()
            assertSame("keep-ahead and speed measurements do not rebuild", built, model.state.value.content)

            data.progress.value = NarrationProgress(job = job("1", "a"), done = 3, total = 10)
            runCurrent()
            val started = model.state.value.content
            assertNotSame("a job starting rebuilds, so its row shows as running", built, started)
            assertEquals("1", started.runningJobId)

            data.progress.value = NarrationProgress(job = job("1", "a"), done = 4, total = 10)
            runCurrent()
            assertSame("a progress tick never rebuilds", started, model.state.value.content)
            assertEquals(
                4,
                model.state.value.progress
                    ?.done,
            )
            assertEquals(GenerationBanner.State.GENERATING, model.state.value.banner.state)
            assertEquals(0.4f, model.state.value.banner.fraction)
        }

    @Test
    fun queueChangesAndPublishedAudioRebuild() =
        runTest(dispatcher) {
            val model = model()
            observe(model)
            val empty = model.state.value.content

            data.queue.value = NarrationQueueDocument(jobs = listOf(job("1", "a")))
            runCurrent()
            val queued = model.state.value.content
            assertNotSame(empty, queued)
            assertEquals(1, queued.cards.size)

            data.attached = mapOf("a" to listOf(pack("a", "a1", bytes = 500)))
            data.used = 500
            data.packsRevision.value = 1
            runCurrent()
            val published = model.state.value.content
            assertNotSame(queued, published)
            assertEquals(setOf("a"), published.audio.attached.keys)
            assertEquals("Alpha", published.audioStories["a"]?.title)
            assertEquals(500, published.usedBytes)
        }

    @Test
    fun aRenderStartedAfterAChangeSeesIt() =
        runTest(dispatcher) {
            val model = model()
            // Nobody collects: the job finished and left the queue between two renders.
            data.queue.value = NarrationQueueDocument(jobs = listOf(job("1", "a")))
            assertEquals(
                1,
                model
                    .refresh()
                    .content.cards.size,
            )
            data.queue.value = NarrationQueueDocument()

            assertTrue(
                model
                    .refresh()
                    .content.cards
                    .isEmpty(),
            )
        }

    @Test
    fun theGenerateOnRowNeedsAComputerAndABuiltInVoice() =
        runTest(dispatcher) {
            val servers = listOf(NarrationServer(id = "mac", name = "Mac"))
            data.queue.value = NarrationQueueDocument(servers = servers)
            assertTrue(
                model()
                    .state.value.content.showGenerateOn,
            )

            data.voiceUsesComputers = false
            assertFalse(
                model()
                    .state.value.content.showGenerateOn,
            )

            data.voiceUsesComputers = true
            data.queue.value = NarrationQueueDocument()
            assertFalse(
                model()
                    .state.value.content.showGenerateOn,
            )
        }

    @Test
    fun playbackShowsThePlayingStoryAndVoiceLookingTheStoryUpOnce() =
        runTest(dispatcher) {
            val model = model()
            observe(model)

            data.playback.value = TtsPlaybackUpdate(snapshot(chunk = 1), isAuthoritative = true)
            runCurrent()
            data.playback.value = TtsPlaybackUpdate(snapshot(chunk = 2), isAuthoritative = true)
            runCurrent()

            val now = model.state.value.now as ListenNow.Playing
            assertEquals(2, now.snapshot.chunkIndex)
            assertEquals("Alpha", now.story?.title)
            assertEquals("Heart", now.voice)
            assertEquals(1, data.storyLookups.count { it == "a" })
        }

    @Test
    fun withNothingPlayingTheSavedPlaceIsOfferedAndLookedUpAgainAfterAStop() =
        runTest(dispatcher) {
            val place = ContinuePlace(TtsContinueListening.Target(alpha, alpha.chapters[0]), "sentence 4")
            data.place = place
            val model = model()
            assertEquals(ListenNow.Nothing, model.state.value.now)

            observe(model)
            assertEquals(ListenNow.Continue(place), model.state.value.now)

            data.playback.value = TtsPlaybackUpdate(snapshot(), isAuthoritative = true)
            runCurrent()
            data.place = null
            data.playback.value = TtsPlaybackUpdate(null, isAuthoritative = true)
            runCurrent()

            assertEquals(ListenNow.Nothing, model.state.value.now)
            assertEquals(2, data.placeLookups)
        }

    @Test
    fun aNewModelStartsFromTheLastPlaceFound() =
        runTest(dispatcher) {
            val place = ContinuePlace(TtsContinueListening.Target(alpha, alpha.chapters[0]), null)
            data.lastPlace = place

            assertEquals(ListenNow.Continue(place), model().state.value.now)
        }

    @Test
    fun queueActionsReachTheCoordinator() =
        runTest(dispatcher) {
            data.queue.value = NarrationQueueDocument(jobs = listOf(job("1", "a"), job("2", "b")))
            val model = model()

            model.moveStoryToTop("b")
            model.moveToTop("2")
            model.remove(setOf("1"))
            model.retry(listOf("1", "2"))
            model.retryAll()
            model.clearFailed()
            model.pauseGeneration()
            model.resumeGeneration()
            model.clearQueue()
            runCurrent()

            assertEquals(
                listOf(
                    "moveStoryToTop b",
                    "moveToTop 2",
                    "remove [1]",
                    "retry 1",
                    "retry 2",
                    "retryAll",
                    "clearFailed",
                    "pause",
                    "resume",
                    "clearQueue",
                ),
                data.calls,
            )
        }

    @Test
    fun aFailedChangeIsReported() =
        runTest(dispatcher) {
            data.failing = true
            val model = model()
            val messages = mutableListOf<String>()
            backgroundScope.launch(dispatcher) { model.messages.collect { messages += it } }

            model.clearQueue()
            runCurrent()

            assertEquals(listOf("Could not clear the queue"), messages)
        }

    @Test
    fun aChangeFinishesEvenWhenTheScreenGoesAway() =
        runTest(dispatcher) {
            data.detached = mapOf("gone" to listOf(pack("gone", "g1"), pack("gone", "g2")))
            // A separate I/O dispatcher, so the deletion is still waiting to run when the model is cleared.
            val io = StandardTestDispatcher(testScheduler)
            val model = ListenScreenModel(data, controls, io = io, scope = CoroutineScope(SupervisorJob() + dispatcher))

            model.deleteDetachedAudio()
            model.clear()
            runCurrent()

            assertEquals(listOf("deleteAudio gone [g1, g2]"), data.calls)
        }

    @Test
    fun generateOnTapsToggleAgainstTheChoiceOnScreen() =
        runTest(dispatcher) {
            val servers = listOf(NarrationServer(id = "mac", name = "Mac"), NarrationServer(id = "pc", name = "PC"))
            data.queue.value = NarrationQueueDocument(servers = servers, generateOnIds = listOf("mac"))
            val model = model()

            model.chooseGenerateOn("pc")
            model.chooseGenerateOn(null)
            model.chooseGenerateOn("mac")
            runCurrent()

            assertEquals(listOf("setGenerateOn [mac, pc] false", "setGenerateOn [mac] true", "setGenerateOn [] false"), data.calls)
        }

    @Test
    fun playActionsGoThroughThePlaybackControls() =
        runTest(dispatcher) {
            val model = model()

            model.playPause()
            model.resume(ContinuePlace(TtsContinueListening.Target(alpha, alpha.chapters[0]), null))

            assertEquals(listOf("playPause", "resume a a1"), controls.calls)
        }

    @Test
    fun compressionIsMeasuredAgainstStorageOnlyWhenItChanges() =
        runTest(dispatcher) {
            data.used = 1_000
            data.progress.value = NarrationProgress(job = job("1", "a"), done = 1, total = 3)
            val model = model()
            observe(model)
            assertEquals(1_000, model.state.value.compression.totalBytes)
            val reads = data.usedReads

            data.progress.value = NarrationProgress(job = job("1", "a"), done = 2, total = 3)
            runCurrent()
            assertEquals("a progress tick does not re-measure storage", reads, data.usedReads)

            data.used = 800
            data.compression.value = NarrationCompressionStatus(phase = NarrationCompressionStatus.Phase.COMPRESSING, remainingChapters = 3)
            runCurrent()
            assertEquals(800, model.state.value.compression.totalBytes)
            assertEquals(3, model.state.value.compression.status.remainingChapters)
            assertNull(
                model.state.value.progress
                    ?.etaMs,
            )
        }

    private fun pack(
        storyId: String,
        chapterId: String,
        bytes: Long = 0,
    ) = NarrationPack(
        storyId = storyId,
        chapterId = chapterId,
        version = "v-1",
        dir = File("unused"),
        recipe = NarrationRecipe(storyId = storyId, chapterId = chapterId),
        index = NarrationPackIndex(bytes = bytes),
    )
}

private class FakeListenData(
    private val library: List<Story>,
) : ListenData {
    override val queue = MutableStateFlow(NarrationQueueDocument())
    override val progress = MutableStateFlow<NarrationProgress?>(null)
    override val generationRunning = MutableStateFlow(true)
    override val packsRevision = MutableStateFlow(0L)
    override val compression = MutableStateFlow(NarrationCompressionStatus())
    override val playback = MutableStateFlow(TtsPlaybackUpdate(null, isAuthoritative = true))
    var attached: Map<String, List<NarrationPack>> = emptyMap()
    var detached: Map<String, List<NarrationPack>> = emptyMap()
    var used = 0L
    var usedReads = 0
    var voiceUsesComputers = true
    var place: ContinuePlace? = null
    var lastPlace: ContinuePlace? = null
    var placeLookups = 0
    var failing = false
    val storyLookups = mutableListOf<String>()
    val calls = mutableListOf<String>()

    override fun story(id: String): Story? {
        storyLookups += id
        return library.firstOrNull { it.id == id }?.copy()
    }

    override fun packVoice(
        storyId: String,
        chapterId: String,
    ): String = "Heart"

    override suspend fun chapterSentences(
        storyId: String,
        chapterId: String,
    ): List<String> = emptyList()

    override suspend fun continuePlace(): ContinuePlace? {
        placeLookups++
        return place
    }

    override fun chapterTitle(
        storyId: String,
        chapterId: String,
    ) = "Title $chapterId"

    override fun generatedAudio() = GeneratedAudio(attached, detached)

    override fun usedBytes(): Long {
        usedReads++
        return used
    }

    override suspend fun listenedAt(): Map<String, Long> = emptyMap()

    override fun freeBytes() = 9_000L

    override fun voiceCanUseComputers() = voiceUsesComputers

    override fun lastContinuePlace() = lastPlace

    private fun record(call: String) {
        if (failing) error("disk full")
        calls += call
    }

    override fun pause() = record("pause")

    override fun resume() = record("resume")

    override fun retry(jobId: String) = record("retry $jobId")

    override fun retryAll() = record("retryAll")

    override fun clearFailed() = record("clearFailed")

    override fun clearQueue() = record("clearQueue")

    override fun remove(jobIds: Set<String>) = record("remove $jobIds")

    override fun moveToTop(jobId: String) = record("moveToTop $jobId")

    override fun moveStoryToTop(storyId: String) = record("moveStoryToTop $storyId")

    override suspend fun deleteAudio(
        storyId: String,
        chapterIds: List<String>,
    ) = record("deleteAudio $storyId $chapterIds")

    override fun setGenerateOn(
        serverIds: List<String>,
        phoneFallback: Boolean,
    ) = record("setGenerateOn $serverIds $phoneFallback")
}
