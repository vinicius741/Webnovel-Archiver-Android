package com.vinicius741.webnovelarchiver.feature.downloads

import com.vinicius741.webnovelarchiver.data.repository.DownloadUiSnapshot
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.download.DownloadPacingSnapshot
import com.vinicius741.webnovelarchiver.download.GlobalQueueAction
import com.vinicius741.webnovelarchiver.download.QueueSection
import com.vinicius741.webnovelarchiver.download.SourceCheckOutcome
import com.vinicius741.webnovelarchiver.download.SourceCheckPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueScreenModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val data = FakeQueueData()

    private fun TestScope.model(): QueueScreenModel =
        QueueScreenModel(data, scope = CoroutineScope(SupervisorJob() + dispatcher), planning = dispatcher)

    /** Collects the model's state, the way an attached renderer does. */
    private fun TestScope.observe(model: QueueScreenModel) {
        backgroundScope.launch(dispatcher) { model.state.collect {} }
        runCurrent()
    }

    private val QueueScreenModel.keys: List<String> get() = state.value.items.map { it.key }

    @Test
    fun theSectionPlanBecomesRowsSubtitleAndAppBar() =
        runTest(dispatcher) {
            data.publish(
                job("a", 0, status = "completed"),
                job("a", 1, status = "downloading"),
                job("b", 0),
                job("c", 0, source = "sh"),
                job("d", 0, status = "paused"),
            )

            val model = model()

            assertEquals(
                listOf(
                    "summary",
                    "header:DOWNLOADING",
                    "story:a",
                    "header:UP_NEXT",
                    "story:b",
                    "story:c",
                    "header:PAUSED",
                    "story:d",
                ),
                model.keys,
            )
            val upNext =
                model.state.value.items
                    .filterIsInstance<QueueStoryItem>()
                    .filter { it.section == QueueSection.UP_NEXT }
            assertTrue("up-next cards can be dragged", upNext.all { it.draggable })
            assertEquals(
                "4 novels · 1 of 5 chapters",
                model.state.value.subtitle
                    .substringBefore(" ·  "),
            )
            assertEquals(GlobalQueueAction.PAUSE_ALL, model.state.value.appBar.toggle)
            assertFalse(model.state.value.empty)
        }

    @Test
    fun anEmptyQueueShowsNoRows() =
        runTest(dispatcher) {
            val state = model().state.value

            assertTrue(state.empty)
            assertTrue(state.items.isEmpty())
            assertEquals("Nothing in the queue", state.subtitle)
        }

    @Test
    fun queuePublishesAreFollowedOnlyWhileARendererCollects() =
        runTest(dispatcher) {
            data.publish(job("a", 0))
            val model = model()

            data.publish(job("a", 0), job("b", 0))
            runCurrent()
            assertEquals("unobserved, the state waits for the next render", listOf("summary", "header:UP_NEXT", "story:a"), model.keys)

            observe(model)
            assertTrue("story:b" in model.keys)

            data.publish(job("a", 0, status = "downloading"), job("b", 0))
            runCurrent()
            assertEquals(listOf("summary", "header:DOWNLOADING", "story:a", "header:UP_NEXT", "story:b"), model.keys)
        }

    @Test
    fun onRenderCatchesUpWithAQueueChangedWhileAway() =
        runTest(dispatcher) {
            data.publish(job("a", 0))
            val model = model()
            data.publish(job("a", 0), job("b", 0))

            val state = model.onRender()

            assertTrue(state.items.any { it.key == "story:b" })
        }

    @Test
    fun expandChoicesOpenChapterRowsAndSurviveQueueChanges() =
        runTest(dispatcher) {
            data.publish(job("a", 0, status = "downloading"), job("a", 1))
            val model = model()
            observe(model)

            model.toggleStory("a")
            runCurrent()
            assertEquals(listOf("summary", "header:DOWNLOADING", "story:a", "chapter:a-0", "chapter:a-1"), model.keys)
            assertTrue((model.state.value.items[2] as QueueStoryItem).expanded)

            data.publish(job("a", 0, status = "completed"), job("a", 1, status = "downloading"))
            runCurrent()
            assertEquals("the choice outlives the publish", listOf("summary", "header:DOWNLOADING", "story:a", "chapter:a-1"), model.keys)

            model.setStoryExpanded("a", false)
            runCurrent()
            assertEquals(listOf("summary", "header:DOWNLOADING", "story:a"), model.keys)
        }

    @Test
    fun theFinishedSectionOpensAndCloses() =
        runTest(dispatcher) {
            data.publish(job("a", 0, status = "completed"))
            val model = model()
            observe(model)
            assertEquals(listOf("summary", "header:FINISHED"), model.keys)

            model.toggleFinished()
            runCurrent()
            assertEquals(listOf("summary", "header:FINISHED", "story:a"), model.keys)

            model.toggleFinished()
            runCurrent()
            assertEquals(listOf("summary", "header:FINISHED"), model.keys)
        }

    @Test
    fun resumeAndRetryStartTheDownloadServiceButPauseDoesNot() =
        runTest(dispatcher) {
            val model = model()

            model.run(QueueControl.PauseStory("a"))
            runCurrent()
            assertEquals(0, data.serviceStarts)

            model.run(QueueControl.ResumeStory("a"))
            model.run(QueueControl.RetryJob("j"))
            runCurrent()

            assertEquals(listOf(QueueControl.PauseStory("a"), QueueControl.ResumeStory("a"), QueueControl.RetryJob("j")), data.applied)
            assertEquals(2, data.serviceStarts)
        }

    @Test
    fun aFailedSaveIsReportedAsAMessage() =
        runTest(dispatcher) {
            val model = model()
            val messages = mutableListOf<String>()
            backgroundScope.launch(dispatcher) { model.messages.toList(messages) }
            data.failing = true

            model.run(QueueControl.ClearFinished)
            runCurrent()

            assertEquals(listOf("Couldn't save that change"), messages)
        }

    @Test
    fun aDropOntoDownloadingNowRunsDownloadNowAndADragWithinUpNextReorders() =
        runTest(dispatcher) {
            val model = model()

            model.reorder(listOf("c", "b"), downloadNow = null)
            model.reorder(listOf("b"), downloadNow = "c")
            runCurrent()

            assertEquals(listOf(QueueControl.Reorder(listOf("c", "b")), QueueControl.DownloadNow("c")), data.applied)
        }

    @Test
    fun afterADropTheListWaitsForTheSavedOrder() =
        runTest(dispatcher) {
            data.publish(job("a", 0, status = "downloading"), job("b", 0), job("c", 0))
            val model = model()
            observe(model)
            val before = model.state.value

            model.reorder(listOf("c", "b"), downloadNow = null)
            // A pacing countdown starts before the saved order is published: the dragged list stays.
            data.pacing.value = mapOf("rr" to pacing("a", "a-0", data.clock.value + 5_000))
            runCurrent()
            assertEquals(before, model.state.value)

            data.publish(job("a", 0, status = "downloading"), job("c", 0), job("b", 0))
            runCurrent()
            assertEquals(listOf("summary", "header:DOWNLOADING", "story:a", "header:UP_NEXT", "story:c", "story:b"), model.keys)
        }

    @Test
    fun countdownsReplanEveryTick() =
        runTest(dispatcher) {
            data.publish(job("a", 0, status = "downloading"), job("a", 1))
            val model = model()
            observe(model)
            model.toggleStory("a")
            data.pacing.value = mapOf("rr" to pacing("a", "a-0", data.clock.value + 3_000))
            runCurrent()
            val waiting =
                model.state.value.items
                    .filterIsInstance<QueueChapterItem>()
                    .single { it.jobId == "a-0" }

            data.clock.value += 1_000
            runCurrent()
            val ticked =
                model.state.value.items
                    .filterIsInstance<QueueChapterItem>()
                    .single { it.jobId == "a-0" }

            assertTrue("the countdown moved: ${waiting.status} -> ${ticked.status}", waiting.status != ticked.status)
        }

    @Test
    fun aBlockedSiteGetsACheckCardThatFollowsItsOutcome() =
        runTest(dispatcher) {
            data.blocked += "rr"
            data.publish(job("a", 0, url = "https://rr.test/1"))
            val model = model()
            observe(model)
            val card =
                model.state.value.items
                    .filterIsInstance<QueueCheckItem>()
                    .single()
            assertEquals("rr", card.card.providerId)
            assertEquals(SourceCheckPhase.NEEDS_CHECK, card.card.phase)

            data.blocked -= "rr"
            data.outcomes.value = mapOf("rr" to SourceCheckOutcome(verified = true, resumedChapters = 1, atMillis = data.clock.value))
            runCurrent()

            assertEquals(
                SourceCheckPhase.VERIFIED,
                model.state.value.items
                    .filterIsInstance<QueueCheckItem>()
                    .single()
                    .card.phase,
            )

            model.verifySite("rr", "https://rr.test/1")
            assertEquals(listOf("rr" to "https://rr.test/1"), data.checks)
        }

    @Test
    fun menusReadTheQueueAtTapTime() =
        runTest(dispatcher) {
            data.publish(job("a", 0, status = "downloading"), job("b", 0), job("b", 1, status = "failed"), job("c", 0))
            val model = model()

            val menu = model.storyMenu("b")
            assertEquals(2, menu.counts.total)
            assertTrue(menu.counts.hasFailed)
            assertEquals(2, menu.upNextCount)
            assertTrue(GlobalQueueAction.RETRY_ALL in model.overflow())
            assertNotNull(model.job("b-1"))
            assertEquals("rr", model.providerOf(model.job("b-1")!!))
        }

    private fun pacing(
        storyId: String,
        jobId: String,
        at: Long,
    ) = DownloadPacingSnapshot("Royal Road", storyId, jobId, "Chapter", at)

    private fun job(
        storyId: String,
        index: Int,
        status: String = "pending",
        source: String = "rr",
        url: String = "https://$source.test/$storyId/$index",
    ) = DownloadJob(
        id = "$storyId-$index",
        storyId = storyId,
        storyTitle = "Story $storyId",
        sourceId = source,
        chapterIndex = index,
        chapter = Chapter(id = "$storyId-c$index", title = "Chapter ${index + 1}", url = url),
        status = status,
        addedAt = 0L,
    )
}

private class FakeQueueData : QueueData {
    val clock = MutableStateFlow(1_000_000L)
    val blocked = mutableSetOf<String>()
    val applied = mutableListOf<QueueControl>()
    val checks = mutableListOf<Pair<String, String>>()
    var serviceStarts = 0
    var failing = false

    override val downloads = MutableStateFlow(DownloadUiSnapshot())
    override val pacing = MutableStateFlow<Map<String, DownloadPacingSnapshot>>(emptyMap())
    val outcomes = MutableStateFlow<Map<String, SourceCheckOutcome>>(emptyMap())
    override val checkOutcomes: StateFlow<Map<String, SourceCheckOutcome>> get() = outcomes

    fun publish(vararg jobs: DownloadJob) {
        val previous = downloads.value
        val stories = jobs.map { it.storyId }.distinct().map { Story(id = it, title = "Story $it") }
        downloads.value =
            DownloadUiSnapshot(
                version = previous.version + 1,
                libraryVersion = previous.libraryVersion,
                queueVersion = previous.queueVersion + 1,
                library = stories,
                queue = jobs.toList(),
            )
    }

    override fun nowMillis(): Long = clock.value

    override fun ticker(): Flow<Long> = clock

    override fun providerIdForJob(job: DownloadJob): String? = job.sourceId

    override fun isSourceBlocked(providerId: String): Boolean = providerId in blocked

    override suspend fun apply(control: QueueControl) {
        check(!failing) { "disk full" }
        applied += control
    }

    override fun startDownloads() {
        serviceStarts += 1
    }

    override fun startSourceCheck(
        providerId: String,
        url: String,
    ) {
        checks += providerId to url
    }
}
