package com.vinicius741.webnovelarchiver.data.repository

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus
import com.vinicius741.webnovelarchiver.domain.model.DownloadStatus
import com.vinicius741.webnovelarchiver.download.DownloadEnqueueTransaction
import com.vinicius741.webnovelarchiver.download.downloadQueueStore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The repository's working library and queue are the read source of every transaction: after
 * [AppRepository.refresh] the store is only written. These tests pin that, and that writers outside
 * the repository's own transactions (the download loop, failure handling, enqueue) keep it current.
 */
class AppRepositoryWorkingStateTest {
    private fun TestScope.loaded(store: FakeRepositoryStoryStore): AppRepository =
        AppRepository(store, StandardTestDispatcher(testScheduler)).apply { refresh() }

    private fun job(
        index: Int,
        status: DownloadJobStatus = DownloadJobStatus.Pending,
        storyId: String = "story",
    ) = DownloadJob(
        id = "${storyId}_$index",
        storyId = storyId,
        chapterIndex = index,
        chapter = Chapter(id = listOf("one", "two")[index], title = "Chapter $index"),
        status = status.wire,
    )

    @Test
    fun transactionsReadOnlyTheWorkingStateAfterRefresh() =
        runTest {
            val store = FakeRepositoryStoryStore(fakeStory(), fakeStory(id = "other"))
            store.saveQueue(listOf(job(0, DownloadJobStatus.Downloading)))
            val repository = loaded(store)

            repository.toggleBookmark("story", "two")
            repository.commitSyncedStory(fakeStory(id = "other")) { current -> current!!.copy(title = "Synced") }
            repository.updateQueue { it }
            val outcome =
                repository.completeDownloadedChapter(
                    job(0, DownloadJobStatus.Downloading),
                    { "novels/story/0000_one.html" },
                    5L,
                    repository.libraryGeneration(),
                )

            assertEquals(AppRepository.ChapterCommit.COMMITTED, outcome)
            assertEquals("only refresh reads the library", 1, store.libraryReadCount)
            assertEquals("only refresh reads the queue", 1, store.queueReadCount)
            assertEquals("existing stories are rewritten without a membership lookup", 3, store.memberSaves)
            assertEquals(0, store.membershipSaves)
            assertTrue(
                store
                    .story("story")!!
                    .chapters
                    .first()
                    .downloaded,
            )
            assertEquals("two", store.story("story")!!.lastReadChapterId)
            assertEquals("Synced", store.story("other")!!.title)
            assertEquals(DownloadJobStatus.Completed.wire, store.storedQueue().single().status)
        }

    @Test
    fun aTransactionBeforeStartupRefreshLoadsTheStoreInsteadOfSavingAnEmptyQueue() =
        runTest {
            val store = FakeRepositoryStoryStore(fakeStory())
            store.saveQueue(listOf(job(0), job(1)))
            // The sticky download service can act before startup's refresh().
            val repository = AppRepository(store, StandardTestDispatcher(testScheduler))

            repository.updateQueue { queue -> queue.map { it.copy(status = DownloadJobStatus.Paused.wire) } }
            repository.toggleBookmark("story", "one")

            assertEquals(listOf("story_0", "story_1"), store.storedQueue().map { it.id })
            assertEquals(setOf(DownloadJobStatus.Paused.wire), store.storedQueue().map { it.status }.toSet())
            assertEquals("one", store.story("story")!!.lastReadChapterId)
            assertEquals(1, store.libraryReadCount)
            assertEquals(1, store.queueReadCount)
        }

    @Test
    fun rewritingAMemberKeepsItsRecordedDateAdded() =
        runTest {
            val store = FakeRepositoryStoryStore(fakeStory().copy(dateAdded = 42L))
            val repository = loaded(store)

            repository.updateStory("story") { it!!.copy(title = "Renamed", dateAdded = 999L) }
            repository.upsertStory(fakeStory().copy(dateAdded = null))

            assertEquals(42L, store.story("story")!!.dateAdded)
            assertEquals(42L, repository.story("story")!!.dateAdded)
        }

    @Test
    fun aNewStoryTakesTheMembershipPathAndLaterWritesDoNot() =
        runTest {
            val store = FakeRepositoryStoryStore()
            val repository = loaded(store)

            repository.upsertStory(fakeStory(id = "new"))
            repository.toggleBookmark("new", "one")

            assertEquals(1, store.membershipSaves)
            assertEquals(1, store.memberSaves)
            assertEquals("one", store.story("new")!!.lastReadChapterId)
        }

    @Test
    fun aFailedStoryWriteLeavesTheWorkingStateUntouched() =
        runTest {
            val store = FakeRepositoryStoryStore(fakeStory())
            val repository = loaded(store)
            store.unwritableIds += "story"

            val error = runCatching { repository.updateStory("story") { it!!.copy(title = "Lost") } }.exceptionOrNull()

            assertTrue(error is IllegalStateException)
            assertEquals("Story story", repository.story("story")!!.title)
            store.unwritableIds.clear()
            repository.toggleBookmark("story", "one")
            assertEquals("Story story", store.story("story")!!.title)
        }

    @Test
    fun aFailedQueueWriteLeavesTheWorkingQueueUntouched() =
        runTest {
            val store = FakeRepositoryStoryStore(fakeStory())
            store.saveQueue(listOf(job(0)))
            val repository = loaded(store)
            store.failQueueWrites = true

            runCatching { repository.updateQueue { emptyList() } }

            assertEquals(listOf("story_0"), repository.queue().map { it.id })
            store.failQueueWrites = false
            repository.updateQueue { it }
            assertEquals(listOf("story_0"), store.storedQueue().map { it.id })
        }

    @Test
    fun downloadLoopSavesReachTheNextTransactionWithoutAnyRead() =
        runTest {
            val store = FakeRepositoryStoryStore(fakeStory())
            store.saveQueue(listOf(job(0), job(1)))
            val repository = loaded(store)
            val queueStore = repository.downloadQueueStore()
            val versionBefore = repository.downloadState.value.queueVersion

            // The process loop claims under the monitor, publishing afterwards.
            synchronized(queueStore.lock) {
                val queue = queueStore.queue()
                queue.first().status = DownloadJobStatus.Downloading.wire
                queueStore.saveQueue(queue)
            }
            // Between the claim and its publish, a repository transaction must see the claim.
            val outcome =
                repository.completeDownloadedChapter(job(0, DownloadJobStatus.Downloading), { "path" }, 5L, repository.libraryGeneration())
            repository.publishDownloadState()

            assertEquals(AppRepository.ChapterCommit.COMMITTED, outcome)
            assertEquals(1, store.queueReadCount)
            assertEquals(
                listOf(DownloadJobStatus.Completed.wire, DownloadJobStatus.Pending.wire),
                store.storedQueue().map { it.status },
            )
            assertEquals(repository.queue().map { it.status }, store.storedQueue().map { it.status })
            assertEquals(versionBefore + 1, repository.downloadState.value.queueVersion)
        }

    @Test
    fun queueStoreHandsOutCopiesAndPublishesOnlyAfterTheCallerDoes() =
        runTest {
            val store = FakeRepositoryStoryStore(fakeStory())
            store.saveQueue(listOf(job(0)))
            val repository = loaded(store)
            val queueStore = repository.downloadQueueStore()

            synchronized(queueStore.lock) { queueStore.queue().single().status = DownloadJobStatus.Failed.wire }
            assertEquals(DownloadJobStatus.Pending.wire, repository.queue().single().status)

            queueStore.mutateQueueInPlace { jobs -> jobs.onEach { it.status = DownloadJobStatus.Paused.wire } }
            assertEquals(
                "published state waits for publishDownloadState",
                DownloadJobStatus.Pending.wire,
                repository.queue().single().status,
            )
            repository.publishDownloadState()
            assertEquals(DownloadJobStatus.Paused.wire, repository.queue().single().status)
            assertEquals(DownloadJobStatus.Paused.wire, store.storedQueue().single().status)
        }

    @Test
    fun workingStateIsOnlyReachableUnderTheMonitor() =
        runTest {
            val repository = loaded(FakeRepositoryStoryStore(fakeStory()))

            assertTrue(runCatching { repository.downloadQueueStore().queue() }.exceptionOrNull() is IllegalStateException)
            assertTrue(runCatching { repository.storyForWriteLocked("story") }.exceptionOrNull() is IllegalStateException)
        }

    @Test
    fun enqueueWritesStoryAndQueueThroughTheWorkingState() =
        runTest {
            val store = FakeRepositoryStoryStore(fakeStory())
            val repository = loaded(store)
            repository.toggleBookmark("story", "two")
            val staleUiStory = fakeStory()

            val plan =
                DownloadEnqueueTransaction.execute(
                    lock = repository.transactionLock,
                    story = staleUiStory,
                    indexes = listOf(0, 1),
                    now = 7L,
                    readQueue = repository::queueForWriteLocked,
                    readStory = repository::storyForWriteLocked,
                    persist = repository::saveEnqueueLocked,
                )
            repository.publishDownloadState()

            assertTrue(plan.changed)
            assertEquals(2, repository.queue().size)
            assertEquals(2, store.storedQueue().size)
            val published = repository.story("story")!!
            assertEquals(DownloadStatus.downloading, published.status)
            assertEquals("planned from the working story, not the stale UI copy", "two", published.lastReadChapterId)
            assertEquals(1, store.queueReadCount)
        }

    @Test
    fun deletingAStoryDropsItsJobsFromTheWorkingQueue() =
        runTest {
            val store = FakeRepositoryStoryStore(fakeStory(), fakeStory(id = "other"))
            store.saveQueue(listOf(job(0), job(1, storyId = "other")))
            val repository = loaded(store)

            repository.deleteStory("story")

            assertNull(repository.story("story"))
            assertEquals(listOf("other_1"), repository.queue().map { it.id })
            assertEquals(store.storedQueue().map { it.id }, repository.queue().map { it.id })
            assertEquals(1, store.queueReadCount)
        }

    @Test
    fun storyExistenceChecksUseTheWorkingLibrary() =
        runTest {
            val store = FakeRepositoryStoryStore(fakeStory())
            val repository = loaded(store)

            val present = synchronized(repository.transactionLock) { repository.containsStoryLocked("story") }
            repository.deleteStory("story")
            val absent = synchronized(repository.transactionLock) { repository.containsStoryLocked("story") }

            assertTrue(present)
            assertTrue(!absent)
            assertEquals(1, store.libraryReadCount)
        }
}
