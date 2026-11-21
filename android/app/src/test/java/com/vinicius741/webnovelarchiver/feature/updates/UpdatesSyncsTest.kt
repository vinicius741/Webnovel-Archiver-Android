package com.vinicius741.webnovelarchiver.feature.updates

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.operations.ManualStorySyncResult
import com.vinicius741.webnovelarchiver.operations.StoryOperationKind
import com.vinicius741.webnovelarchiver.operations.StoryOperations
import com.vinicius741.webnovelarchiver.operations.SyncDownloadAction
import com.vinicius741.webnovelarchiver.operations.SyncDownloadPlan
import com.vinicius741.webnovelarchiver.source.network.SourceAccessBlockedException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdatesSyncsTest {
    private val dispatcher = StandardTestDispatcher()
    private val operations = StoryOperations()
    private val host = FakeSyncHost()
    private val syncs = UpdatesSyncs(host, operations, CoroutineScope(SupervisorJob() + dispatcher))

    @Test
    fun aRowSyncIsListedUntilItEndsThenQueuesAndSaysWhatItFound() =
        runTest(dispatcher) {
            val a = host.add("a")
            val gate = host.gate("a")

            syncs.sync(a)
            syncs.sync(a)
            runCurrent()

            assertTrue(operations.isSyncingFromUpdates("a"))
            assertEquals("a second tap is ignored", listOf("a" to false), host.synced)

            gate.complete(result(a, SyncDownloadAction.AUTO_QUEUE, "a-0", "a-1"))
            runCurrent()

            assertFalse(operations.isSyncingFromUpdates("a"))
            assertEquals(listOf("a"), host.handled.map { it.first.id })
            assertEquals(listOf("Found 2 new chapters in Story a"), host.toasts)
        }

    @Test
    fun aRowSyncRefusesAnArchivedOrBusyNovel() =
        runTest(dispatcher) {
            val archived = host.add("a").apply { isArchived = true }
            val busy = host.add("b")
            operations.setContent("b", StoryOperationKind.CLEANUP, "Processing...")

            syncs.sync(archived)
            syncs.sync(busy)
            runCurrent()

            assertTrue(host.synced.isEmpty())
            assertEquals(2, host.toasts.size)
            assertEquals("This novel is busy. Try again when it finishes.", host.toasts.last())
        }

    @Test
    fun aBlockedRowSyncOffersTheSourceCheck() =
        runTest(dispatcher) {
            val a = host.add("a")
            host.gate("a").completeExceptionally(SourceAccessBlockedException("https://source.test/a"))

            syncs.sync(a)
            runCurrent()

            assertEquals(listOf("a"), host.blocked.map { it.id })
            assertFalse(operations.isSyncingFromUpdates("a"))
        }

    @Test
    fun syncAllChecksNovelsOneAtATimeAndSkipsBusyOrMissingOnes() =
        runTest(dispatcher) {
            val a = host.add("a")
            host.add("b")
            val d = host.add("d")
            operations.setContent("b", StoryOperationKind.EPUB, "Preparing...")
            val gateA = host.gate("a")
            val gateD = host.gate("d")

            syncs.syncAll(listOf("a", "b", "missing", "d"))
            runCurrent()
            assertEquals(UpdatesBatchProgress(checked = 0, total = 4, currentTitle = "Story a", stopping = false), syncs.state.value.batch)
            assertEquals("one novel at a time", listOf("a" to true), host.synced)

            gateA.complete(result(a, SyncDownloadAction.AUTO_QUEUE, "a-1"))
            runCurrent()
            assertEquals("b and the missing novel are skipped", listOf("a" to true, "d" to true), host.synced)
            assertEquals(
                3,
                syncs.state.value.batch
                    ?.checked,
            )

            gateD.complete(result(d, SyncDownloadAction.NONE))
            runCurrent()

            val state = syncs.state.value
            assertNull(state.batch)
            assertNotNull(state.lastResult)
            assertEquals(state.lastResult, host.toasts.last())
            assertEquals(listOf("a", "d"), host.handled.map { it.first.id })
        }

    @Test
    fun aRunHoldsLargeUpdatesAndBlockedSourcesUntilUpdatesShowsThem() =
        runTest(dispatcher) {
            val a = host.add("a", chapters = 3)
            host.add("b")
            host.gate("a").complete(result(a, SyncDownloadAction.REVIEW, "a-1", "a-2"))
            host.gate("b").completeExceptionally(SourceAccessBlockedException("https://source.test/b"))
            host.onUpdates = false

            syncs.syncAll(listOf("a", "b"))
            runCurrent()

            assertTrue("a review waits for the end of the run", host.handled.isEmpty())
            assertEquals(1, syncs.state.value.pendingReviews)
            assertTrue(syncs.state.value.pendingBlocked)
            assertTrue(host.blocked.isEmpty())

            syncs.showPendingReviews()

            assertEquals(listOf(listOf(1, 2)), host.handled.map { it.second.chapterIndexes })
            assertEquals(listOf("b"), host.blocked.map { it.id })
            assertEquals(UpdatesSyncsState(lastResult = syncs.state.value.lastResult), syncs.state.value)
        }

    @Test
    fun aFinishedRunShowsItsDialogsAtOnceWhenUpdatesIsShowing() =
        runTest(dispatcher) {
            val a = host.add("a", chapters = 2)
            host.gate("a").complete(result(a, SyncDownloadAction.REVIEW, "a-1"))

            syncs.syncAll(listOf("a"))
            runCurrent()

            assertEquals(1, host.handled.size)
            assertEquals(0, syncs.state.value.pendingReviews)
        }

    @Test
    fun stopLetsTheCurrentNovelFinishThenEndsTheRun() =
        runTest(dispatcher) {
            val a = host.add("a")
            host.add("b")
            val gate = host.gate("a")

            syncs.syncAll(listOf("a", "b"))
            runCurrent()
            syncs.stop()
            assertTrue(
                syncs.state.value.batch!!
                    .stopping,
            )

            gate.complete(result(a, SyncDownloadAction.NONE))
            runCurrent()

            assertEquals(listOf("a" to true), host.synced)
            assertNull(syncs.state.value.batch)
        }

    @Test
    fun syncAllWithNothingDueSaysSoAndASecondRunWaits() =
        runTest(dispatcher) {
            host.add("a")
            host.gate("a")

            syncs.syncAll(emptyList())
            assertEquals(listOf("Nothing looks due right now"), host.toasts)

            syncs.syncAll(listOf("a"))
            syncs.syncAll(listOf("a"))
            runCurrent()
            assertEquals(1, host.synced.size)
        }

    private fun result(
        story: Story,
        action: SyncDownloadAction,
        vararg chapterIds: String,
    ) = ManualStorySyncResult(
        story,
        SyncDownloadPlan(action, chapterIds.map { id -> story.chapters.indexOfFirst { it.id == id } }, chapterIds.toList()),
    )
}

private class FakeSyncHost : UpdatesSyncHost {
    private val stories = mutableMapOf<String, Story>()
    private val gates = mutableMapOf<String, CompletableDeferred<ManualStorySyncResult>>()
    val synced = mutableListOf<Pair<String, Boolean>>()
    val handled = mutableListOf<Pair<Story, SyncDownloadPlan>>()
    val blocked = mutableListOf<Story>()
    val toasts = mutableListOf<String>()
    var onUpdates = true

    fun add(
        id: String,
        chapters: Int = 2,
    ): Story =
        Story(
            id = id,
            title = "Story $id",
            sourceUrl = "https://source.test/$id",
            chapters = MutableList(chapters) { Chapter(id = "$id-$it", title = "Chapter ${it + 1}") },
        ).also { stories[id] = it }

    fun gate(id: String) = CompletableDeferred<ManualStorySyncResult>().also { gates[id] = it }

    override suspend fun sync(
        story: Story,
        inBatch: Boolean,
    ): ManualStorySyncResult {
        synced += story.id to inBatch
        return gates.getValue(story.id).await()
    }

    override fun story(id: String): Story? = stories[id]

    override fun handleDownloads(
        story: Story,
        plan: SyncDownloadPlan,
    ) {
        handled += story to plan
    }

    override fun showBlocked(story: Story) {
        blocked += story
    }

    override fun toast(message: String) {
        toasts += message
    }

    override fun onUpdates(): Boolean = onUpdates

    override fun canShow(): Boolean = true
}
