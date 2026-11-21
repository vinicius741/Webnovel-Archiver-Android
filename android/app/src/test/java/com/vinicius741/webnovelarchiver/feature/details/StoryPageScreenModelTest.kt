package com.vinicius741.webnovelarchiver.feature.details

import com.vinicius741.webnovelarchiver.data.repository.DownloadUiSnapshot
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.download.DownloadPacingSnapshot
import com.vinicius741.webnovelarchiver.download.DownloadPacingUiKind
import com.vinicius741.webnovelarchiver.operations.StoryOperationKind
import com.vinicius741.webnovelarchiver.operations.StoryOperationState
import com.vinicius741.webnovelarchiver.operations.StoryOperations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

@OptIn(ExperimentalCoroutinesApi::class)
class StoryPageScreenModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val story =
        Story(id = "s1", sourceUrl = "https://source.test/s1", chapters = mutableListOf(Chapter(id = "c1"), Chapter(id = "c2")))
    private val other = Story(id = "s2", sourceUrl = "https://source.test/s2")
    private val data = FakeData(DownloadUiSnapshot(library = listOf(story, other)))
    private val pacing = MutableStateFlow<Map<String, DownloadPacingSnapshot>>(emptyMap())
    private val ticker = MutableSharedFlow<Long>()
    private val operations = StoryOperations()
    private var now = 1_000_000L

    private fun TestScope.model(): StoryPageScreenModel =
        StoryPageScreenModel(
            storyId = "s1",
            data = data,
            pacingSnapshots = pacing,
            ticker = ticker,
            nowMillis = { now },
            operations = operations,
            providerName = { _, _ -> "Source" },
            scope = CoroutineScope(SupervisorJob() + dispatcher),
        )

    /** Collects the model's state, the way an attached renderer does. */
    private fun TestScope.observe(model: StoryPageScreenModel) {
        backgroundScope.launch(dispatcher) { model.state.collect {} }
        runCurrent()
    }

    private fun job(
        id: String,
        chapterId: String,
        status: DownloadJobStatus,
        storyId: String = "s1",
    ) = DownloadJob(id = id, storyId = storyId, chapter = Chapter(id = chapterId, title = "Chapter $chapterId"), status = status.wire)

    @Test
    fun initialStateReadsTheStoryAndIsIdle() =
        runTest(dispatcher) {
            val state = model().state.value

            assertEquals("s1", state.story?.id)
            assertNotSame("the model holds its own copy, never the shared snapshot story", story, state.story)
            assertTrue(state.jobs.isEmpty())
            assertEquals(0, state.download.total)
            assertFalse(state.isBusy)
            assertNull(state.pacing)
        }

    @Test
    fun downloadEventsUpdateJobsSummaryAndChapterStatusesOfThisStoryOnly() =
        runTest(dispatcher) {
            val model = model()
            observe(model)

            data.publish(
                queue =
                    listOf(
                        job("j1", "c1", DownloadJobStatus.Downloading),
                        job("j2", "c2", DownloadJobStatus.Pending),
                        job("j3", "x", DownloadJobStatus.Pending, storyId = "s2"),
                    ),
                queueChanged = true,
            )
            runCurrent()

            val state = model.state.value
            assertEquals(listOf("j1", "j2"), state.jobs.map { it.id })
            assertEquals(2, state.download.total)
            assertTrue(state.download.isActive)
            assertEquals(DownloadJobStatus.Downloading, state.chapterStatuses["c1"])
            assertEquals(1L, state.queueVersion)
        }

    @Test
    fun theStoryCopyIsKeptUntilTheStoryItselfChanges() =
        runTest(dispatcher) {
            val model = model()
            observe(model)
            val first = model.state.value

            data.publish(queue = listOf(job("j1", "c1", DownloadJobStatus.Pending)), queueChanged = true)
            runCurrent()
            val afterQueue = model.state.value
            assertSame(first.story, afterQueue.story)
            assertTrue(afterQueue.downloadChangedSince(first))

            val downloaded = story.copy(chapters = mutableListOf(Chapter(id = "c1", downloaded = true), Chapter(id = "c2")))
            data.publish(library = listOf(downloaded, other), libraryChanged = true)
            runCurrent()
            val afterLibrary = model.state.value
            assertNotSame(afterQueue.story, afterLibrary.story)
            assertEquals(1, afterLibrary.story?.chapters?.count { it.downloaded })
            assertTrue(afterLibrary.downloadChangedSince(afterQueue))
        }

    @Test
    fun contentOperationProgressAndClearReachTheStateAndGateTheStory() =
        runTest(dispatcher) {
            val model = model()
            observe(model)

            operations.setContent("s1", StoryOperationKind.CLEANUP, "Processing...")
            runCurrent()
            assertTrue(model.state.value.isBusy)
            assertEquals(
                StoryOperationKind.CLEANUP,
                model.state.value.content
                    ?.kind,
            )

            operations.updateContent("s1", StoryOperationKind.CLEANUP, "Processing 1/2: One", 0.5f)
            runCurrent()
            assertEquals(
                0.5f,
                model.state.value.content
                    ?.progress,
            )
            assertEquals(
                "Processing 1/2: One",
                model.state.value.content
                    ?.message,
            )

            operations.clearContent("s1", StoryOperationKind.CLEANUP)
            runCurrent()
            assertNull(model.state.value.content)
            assertFalse(model.state.value.isBusy)
        }

    @Test
    fun anotherStorysOperationDoesNotGateThisStory() =
        runTest(dispatcher) {
            val model = model()
            observe(model)

            operations.setContent("s2", StoryOperationKind.SYNC, "Starting...")
            runCurrent()

            assertNull(model.state.value.content)
            assertFalse(model.state.value.isBusy)
        }

    @Test
    fun anAiJobShowsWithoutBlockingContentActions() =
        runTest(dispatcher) {
            val model = model()
            observe(model)

            operations.setAi(StoryOperationState("s1", StoryOperationKind.AI_COVER, "Painting cover..."))
            runCurrent()
            assertEquals(
                "Painting cover...",
                model.state.value.ai
                    ?.message,
            )
            assertFalse(model.state.value.isBusy)

            operations.updateAi("s1", StoryOperationKind.AI_COVER, "Saving cover...")
            runCurrent()
            assertEquals(
                "Saving cover...",
                model.state.value.ai
                    ?.message,
            )

            operations.clearAi("s1", StoryOperationKind.AI_COVER)
            runCurrent()
            assertNull(model.state.value.ai)
        }

    @Test
    fun pacingCountsDownOnTicksAndMarksWaitingChapters() =
        runTest(dispatcher) {
            val model = model()
            observe(model)
            data.publish(queue = listOf(job("j1", "c1", DownloadJobStatus.Downloading)), queueChanged = true)
            pacing.value = mapOf("Source" to DownloadPacingSnapshot("Source", "s1", "j1", "Chapter c1", nextRequestAtMillis = now + 5_000L))
            runCurrent()
            assertEquals(
                DownloadPacingUiKind.CONFIGURED_WAIT,
                model.state.value.pacing
                    ?.kind,
            )
            assertEquals(
                5L,
                model.state.value.pacing
                    ?.remainingSeconds,
            )
            assertEquals(setOf("c1"), model.state.value.waitingChapterIds)

            now += 2_000L
            ticker.emit(now)
            runCurrent()
            assertEquals(
                3L,
                model.state.value.pacing
                    ?.remainingSeconds,
            )

            now += 4_000L
            ticker.emit(now)
            runCurrent()
            assertNull(model.state.value.pacing)
            assertTrue(
                model.state.value.waitingChapterIds
                    .isEmpty(),
            )
        }

    @Test
    fun sourcesAreReadOnlyWhileObservedAndRefreshCatchesUp() =
        runTest(dispatcher) {
            val model = model()
            val collector = backgroundScope.launch(dispatcher) { model.state.collect {} }
            runCurrent()
            collector.cancel()
            runCurrent()

            operations.setContent("s1", StoryOperationKind.EPUB, "Preparing...")
            runCurrent()
            assertNull("an unobserved model does not follow its sources", model.state.value.content)

            assertEquals(StoryOperationKind.EPUB, model.refresh().content?.kind)
            assertEquals(
                StoryOperationKind.EPUB,
                model.state.value.content
                    ?.kind,
            )
        }

    @Test
    fun refreshPageBumpsTheRevision() =
        runTest(dispatcher) {
            val model = model()
            val before = model.state.value.revision

            model.refreshPage()

            assertEquals(before + 1, model.state.value.revision)
        }

    @Test
    fun actionsGoToTheRepository() =
        runTest(dispatcher) {
            val model = model()

            model.toggleBookmark("c2")
            model.saveChapterFilter("downloaded")
            runCurrent()

            assertEquals(listOf("s1" to "c2"), data.toggled)
            assertEquals(listOf("downloaded"), data.savedFilters)
        }

    @Test
    fun clearingTheModelStopsItsWork() =
        runTest(dispatcher) {
            val model = model()
            observe(model)
            model.clear()

            operations.setContent("s1", StoryOperationKind.SYNC, "Starting...")
            runCurrent()

            assertNull(model.state.value.content)
        }

    private class FakeData(
        initial: DownloadUiSnapshot,
    ) : StoryPageData {
        private val state = MutableStateFlow(initial)
        val toggled = mutableListOf<Pair<String, String>>()
        val savedFilters = mutableListOf<String>()

        override val downloads: StateFlow<DownloadUiSnapshot> = state

        override fun story(id: String): Story? =
            state.value.library
                .firstOrNull { it.id == id }
                ?.copy()

        override suspend fun toggleBookmark(
            storyId: String,
            chapterId: String,
        ) {
            toggled += storyId to chapterId
        }

        override suspend fun saveChapterFilter(mode: String) {
            savedFilters += mode
        }

        fun publish(
            library: List<Story> = state.value.library,
            queue: List<DownloadJob> = state.value.queue,
            libraryChanged: Boolean = false,
            queueChanged: Boolean = false,
        ) {
            val previous = state.value
            state.value =
                DownloadUiSnapshot(
                    version = previous.version + 1,
                    libraryVersion = previous.libraryVersion + if (libraryChanged) 1 else 0,
                    queueVersion = previous.queueVersion + if (queueChanged) 1 else 0,
                    library = library,
                    queue = queue,
                )
        }
    }
}
