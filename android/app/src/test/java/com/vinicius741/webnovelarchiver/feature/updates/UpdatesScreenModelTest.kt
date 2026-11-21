package com.vinicius741.webnovelarchiver.feature.updates

import com.vinicius741.webnovelarchiver.data.repository.DownloadUiSnapshot
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.UpdatesPlanning.Entry
import com.vinicius741.webnovelarchiver.domain.story.UpdatesPlanning.Overview
import com.vinicius741.webnovelarchiver.domain.story.UpdatesPlanning.Section
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdatesScreenModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val data = FakeUpdatesData()

    private fun TestScope.model(): UpdatesScreenModel =
        UpdatesScreenModel(data, scope = CoroutineScope(SupervisorJob() + dispatcher), planning = dispatcher)

    private fun TestScope.observe(model: UpdatesScreenModel) {
        backgroundScope.launch(dispatcher) { model.state.collect {} }
        runCurrent()
    }

    @Test
    fun theFirstRenderReusesTheBadgesPlanInsteadOfForecasting() =
        runTest(dispatcher) {
            data.publish(story("a"), story("b"))
            val kept = overview(mayBeOut = listOf("a"))
            data.kept = kept

            val state = model().state.value

            assertSame(kept, state.overview)
            assertEquals(0, data.plans)
            assertEquals(2, state.storyCount)
            assertEquals(UpdatesFilter.ALL, state.filter)
        }

    @Test
    fun withoutAKeptPlanTheFirstRenderPlans() =
        runTest(dispatcher) {
            data.publish(story("a"))

            model()

            assertEquals(1, data.plans)
        }

    @Test
    fun aBurstOfLibraryChangesReplansOnceWhileObserved() =
        runTest(dispatcher) {
            data.publish(story("a"))
            data.kept = overview()
            val model = model()
            observe(model)
            assertEquals("the kept plan is refreshed when a renderer starts", 1, data.plans)

            data.publish(story("a"), story("b"))
            runCurrent()
            data.publish(story("a"), story("b"), story("c"))
            runCurrent()
            advanceTimeBy(500)
            runCurrent()

            assertEquals(2, data.plans)
            assertEquals(3, model.state.value.storyCount)
        }

    @Test
    fun anUnobservedModelDoesNotPlan() =
        runTest(dispatcher) {
            data.kept = overview()
            model()

            data.publish(story("a"))
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(0, data.plans)
        }

    @Test
    fun syncProgressAndTheBatchApplyAtOnce() =
        runTest(dispatcher) {
            data.kept = overview()
            val model = model()
            observe(model)

            data.syncing.value = mapOf("a" to "Fetching chapters")
            data.syncs.value = UpdatesSyncsState(batch = UpdatesBatchProgress(1, 3, "Story b", stopping = false))
            runCurrent()

            assertEquals(mapOf("a" to "Fetching chapters"), model.state.value.syncing)
            assertEquals(
                1,
                model.state.value.syncs.batch
                    ?.checked,
            )
        }

    @Test
    fun theFilterIsScreenState() =
        runTest(dispatcher) {
            data.kept = overview()
            val model = model()

            model.selectFilter(UpdatesFilter.LATE)

            assertEquals(UpdatesFilter.LATE, model.state.value.filter)
        }

    @Test
    fun syncAllChecksTheNovelsThatMayBeOut() =
        runTest(dispatcher) {
            data.publish(story("a"), story("b"), story("c"))
            data.kept = overview(mayBeOut = listOf("a", "c"))
            val model = model()

            model.syncAll()
            model.sync("b")
            model.stopBatch()
            model.showPendingReviews()

            assertEquals(listOf(listOf("a", "c")), data.syncAlls)
            assertEquals(listOf("b"), data.synced)
            assertEquals(listOf("stop", "reviews"), data.calls)
        }

    @Test
    fun downloadsQueueOnlyTheChaptersStillMissing() =
        runTest(dispatcher) {
            data.publish(
                story("a", downloaded = 1, total = 3),
                story("b", downloaded = 0, total = 2),
                story("c", downloaded = 2, total = 2),
            )
            data.kept = overview(toDownload = listOf("a", "b"))
            val model = model()

            model.downloadMissing("c")
            assertTrue("nothing is missing", data.queued.isEmpty())

            model.downloadAllMissing()

            assertEquals(listOf("a" to listOf(1, 2), "b" to listOf(0, 1)), data.queued)
        }

    private fun story(
        id: String,
        downloaded: Int = 0,
        total: Int = 0,
    ) = Story(
        id = id,
        title = "Story $id",
        chapters = MutableList(total) { Chapter(id = "$id-$it", title = "Chapter ${it + 1}", downloaded = it < downloaded) },
    )

    private fun overview(
        mayBeOut: List<String> = emptyList(),
        toDownload: List<String> = emptyList(),
    ) = Overview(
        sections =
            mapOf(
                Section.MAY_BE_OUT to mayBeOut.map { Entry(story(it), null, 0) },
                Section.TO_DOWNLOAD to toDownload.map { Entry(story(it), null, 1) },
            ),
        calendar = emptyList(),
        expectedWithinTwoDays = 0,
        missingChapters = toDownload.size,
    )
}

private class FakeUpdatesData : UpdatesData {
    override val downloads = MutableStateFlow(DownloadUiSnapshot())
    override val syncs = MutableStateFlow(UpdatesSyncsState())
    override val syncing = MutableStateFlow<Map<String, String>>(emptyMap())
    var kept: Overview? = null
    var plans = 0
    val synced = mutableListOf<String>()
    val syncAlls = mutableListOf<List<String>>()
    val calls = mutableListOf<String>()
    val queued = mutableListOf<Pair<String, List<Int>>>()

    fun publish(vararg stories: Story) {
        val previous = downloads.value
        downloads.value =
            previous.copy(version = previous.version + 1, libraryVersion = previous.libraryVersion + 1, library = stories.toList())
    }

    override fun now(): Long = 1_000L

    override fun plannedOverview(library: List<Story>): Overview? = kept

    override fun overview(
        library: List<Story>,
        now: Long,
    ): Overview {
        plans += 1
        return kept ?: Overview(emptyMap(), emptyList(), 0, 0)
    }

    override fun story(id: String): Story? = downloads.value.library.firstOrNull { it.id == id }

    override fun sync(story: Story) {
        synced += story.id
    }

    override fun syncAll(storyIds: List<String>) {
        syncAlls += storyIds
    }

    override fun stopBatch() {
        calls += "stop"
    }

    override fun showPendingReviews() {
        calls += "reviews"
    }

    override fun queueDownload(
        story: Story,
        indexes: List<Int>,
    ) {
        queued += story.id to indexes
    }
}
