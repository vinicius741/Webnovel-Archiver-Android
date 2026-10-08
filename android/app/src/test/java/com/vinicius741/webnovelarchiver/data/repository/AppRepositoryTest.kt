package com.vinicius741.webnovelarchiver.data.repository

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus
import com.vinicius741.webnovelarchiver.domain.model.PatreonRawStats
import com.vinicius741.webnovelarchiver.domain.model.SourceAvailability
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.StoryMetricSnapshot
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class AppRepositoryTest {
    @Test
    fun chapterSelectionsAreIsolatedOnInputAndEveryPublishedSnapshot() =
        runTest {
            val input = story().copy(aiContextChapterIndices = mutableListOf(0), aiCoverContextChapterIndices = mutableListOf(1))
            val store = FakeRepositoryStoryStore(input)
            val repository = AppRepository(store, StandardTestDispatcher(testScheduler))
            repository.upsertStory(input)
            input.aiContextChapterIndices!!.clear()
            input.aiCoverContextChapterIndices!!.clear()
            val old = repository.story(input.id)!!
            old.aiContextChapterIndices!!.add(3)
            repository
                .library()
                .single()
                .aiCoverContextChapterIndices!!
                .clear()
            assertEquals(listOf(0), repository.story(input.id)!!.aiContextChapterIndices)
            assertEquals(listOf(1), repository.story(input.id)!!.aiCoverContextChapterIndices)
            repository.updateStory(input.id) { it!!.copy(sourceSyncState = it.sourceSyncState.copy(lastCheckedAt = 200)) }
            assertNull(old.sourceSyncState.lastCheckedAt)
            assertEquals(200L, repository.story(input.id)!!.sourceSyncState.lastCheckedAt)
        }

    @Test
    fun singleStoryMutationPublishesDetachedSnapshotWithoutReadingWholeLibrary() =
        runTest {
            val store = FakeRepositoryStoryStore(story())
            val repository = AppRepository(store, StandardTestDispatcher(testScheduler))

            repository.upsertStory(store.story("story")!!)
            val beforeMutation = repository.story("story")!!
            repository.toggleBookmark("story", "two")

            val published = repository.story("story")!!
            assertEquals("two", published.lastReadChapterId)
            assertEquals(0, store.libraryReadCount)
            assertNotSame(store.story("story")!!.chapters, published.chapters)

            beforeMutation.chapters.first().title = "changed outside repository"
            assertEquals(
                "One",
                repository
                    .story("story")!!
                    .chapters
                    .first()
                    .title,
            )
        }

    @Test
    fun deletingStoryUpdatesCachedLibraryWithoutReparsingRemainingStories() =
        runTest {
            val store = FakeRepositoryStoryStore(story(), story(id = "other"))
            val repository = AppRepository(store, StandardTestDispatcher(testScheduler))
            store.stories.values.forEach { repository.upsertStory(it) }

            repository.deleteStory("story")

            assertNull(repository.story("story"))
            assertEquals(listOf("other"), repository.library().map { it.id })
            assertEquals(0, store.libraryReadCount)
        }

    @Test
    fun publishingExternallyPersistedStoryRefreshesSingleStoryCache() =
        runTest {
            val store = FakeRepositoryStoryStore(story())
            val repository = AppRepository(store, StandardTestDispatcher(testScheduler))
            repository.upsertStory(store.story("story")!!)
            val syncedAt = 1_750_000_000_000L

            store.stories["story"] = store.story("story")!!.copy(lastChapterSyncAt = syncedAt)
            repository.publishDownloadState(setOf("story"), queueChanged = false)

            assertEquals(syncedAt, repository.story("story")!!.lastChapterSyncAt)
            assertEquals(0, store.libraryReadCount)
        }

    @Test
    fun syncCommitMergesLatestStoryAndPublishesOneRepositorySnapshot() =
        runTest {
            val store = FakeRepositoryStoryStore(story())
            val repository = AppRepository(store, StandardTestDispatcher(testScheduler))
            repository.upsertStory(store.story("story")!!)
            val synced = story().copy(title = "Synced")

            val committed =
                repository.commitSyncedStory(synced) { current ->
                    current!!.copy(title = synced.title, lastReadChapterId = "two")
                }

            assertEquals("Synced", committed.title)
            assertEquals("two", repository.story("story")?.lastReadChapterId)
            assertEquals("Synced", store.story("story")?.title)
        }

    @Test
    fun sourceStateMutationPreservesChaptersAndPublishesFailureState() =
        runTest {
            val store = FakeRepositoryStoryStore(story())
            val repository = AppRepository(store, StandardTestDispatcher(testScheduler))
            repository.upsertStory(store.story("story")!!)

            repository.updateStory("story") { latest ->
                latest?.copy(
                    sourceSyncState =
                        latest.sourceSyncState.copy(
                            availability = SourceAvailability.not_found,
                            lastCheckedAt = 100L,
                            consecutiveNotFoundCount = 1,
                        ),
                )
            }

            val result = repository.story("story")!!
            assertEquals(SourceAvailability.not_found, result.sourceSyncState.availability)
            assertEquals(100L, result.sourceSyncState.lastCheckedAt)
            assertEquals(listOf("one", "two"), result.chapters.map { it.id })
        }

    @Test
    fun storyAndQueueReadsDoNotWaitForTheTransactionMonitor() =
        runTest {
            val store = FakeRepositoryStoryStore(story())
            val repository = AppRepository(store, StandardTestDispatcher(testScheduler))
            repository.upsertStory(store.story("story")!!)

            // Hold the storage monitor like a long backup/restore/EPUB transaction would, then
            // verify UI-side reads still return immediately from the published snapshot (R01).
            synchronized(store.transactionLock) {
                val read =
                    Thread {
                        readResults.add(repository.story("story")?.title)
                        readResults.add(repository.library().singleOrNull()?.title)
                        readResults.add(repository.queue().size.toString())
                        readLatch.countDown()
                    }
                read.isDaemon = true
                read.start()
                assertTrue("story()/library()/queue() blocked on the storage monitor", readLatch.await(2, TimeUnit.SECONDS))
            }

            assertEquals(listOf("Story story", "Story story", "0"), readResults)
        }

    @Test
    fun syncCommitIsRejectedWhenTheLibraryWasReplacedMidFlight() =
        runTest {
            val store = FakeRepositoryStoryStore(story())
            val repository = AppRepository(store, StandardTestDispatcher(testScheduler))
            repository.upsertStory(store.story("story")!!)
            val staleGeneration = repository.libraryGeneration()

            // A clear/restore bumped the generation after the sync captured the old one.
            repository.invalidateLibraryGeneration()

            val error =
                runCatching {
                    repository.commitSyncedStory(story(), startedGeneration = staleGeneration) { current -> current!! }
                }.exceptionOrNull()

            assertTrue(error is IllegalStateException)
        }

    @Test
    fun freshPatreonStatsReachEveryLiveStorySharingTheCreator() =
        runTest {
            val oldStats = PatreonRawStats(capturedAt = 1L, paidMembers = 10)
            val sibling = story(id = "sibling").copy(patreonUrl = "https://www.patreon.com/c/Creator/posts", patreonStats = oldStats)
            val archived =
                story(
                    id = "archived",
                ).copy(patreonUrl = "https://patreon.com/creator", isArchived = true, patreonStats = oldStats)
            val unrelated = story(id = "unrelated").copy(patreonUrl = "https://patreon.com/someone", patreonStats = oldStats)
            val store = FakeRepositoryStoryStore(story(), sibling, archived, unrelated)
            val repository = AppRepository(store, StandardTestDispatcher(testScheduler))
            store.stories.values.forEach { repository.upsertStory(it) }
            val fresh = PatreonRawStats(capturedAt = 2L, paidMembers = 42)
            val synced = story().copy(patreonUrl = "https://www.patreon.com/creator", patreonStats = fresh)

            repository.commitSyncedStory(synced, metricSnapshot = StoryMetricSnapshot(capturedAt = 2L, patreonRaw = fresh))

            assertEquals(fresh, repository.story("sibling")?.patreonStats)
            assertEquals(fresh, store.story("sibling")?.patreonStats)
            assertEquals(oldStats, repository.story("archived")?.patreonStats)
            assertEquals(oldStats, repository.story("unrelated")?.patreonStats)
        }

    @Test
    fun patreonFanOutNeverRegressesAFresherSibling() =
        runTest {
            val newer = PatreonRawStats(capturedAt = 5L, paidMembers = 50)
            val sibling = story(id = "sibling").copy(patreonUrl = "https://patreon.com/creator", patreonStats = newer)
            val store = FakeRepositoryStoryStore(story(), sibling)
            val repository = AppRepository(store, StandardTestDispatcher(testScheduler))
            store.stories.values.forEach { repository.upsertStory(it) }
            // This sync's fetch finished before the sibling's but commits after it.
            val older = PatreonRawStats(capturedAt = 3L, paidMembers = 30)
            val synced = story().copy(patreonUrl = "https://patreon.com/creator", patreonStats = older)

            repository.commitSyncedStory(synced, metricSnapshot = StoryMetricSnapshot(capturedAt = 3L, patreonRaw = older))

            assertEquals(newer, repository.story("sibling")?.patreonStats)
            assertEquals(newer, store.story("sibling")?.patreonStats)
            assertEquals(older, repository.story("story")?.patreonStats)
        }

    @Test
    fun unwritableSiblingDoesNotFailTheCommittedSync() =
        runTest {
            val oldStats = PatreonRawStats(capturedAt = 1L, paidMembers = 10)
            val broken = story(id = "broken").copy(patreonUrl = "https://patreon.com/creator", patreonStats = oldStats)
            val healthy = story(id = "healthy").copy(patreonUrl = "https://patreon.com/creator", patreonStats = oldStats)
            val store = FakeRepositoryStoryStore(story(), broken, healthy)
            val repository = AppRepository(store, StandardTestDispatcher(testScheduler))
            store.stories.values.forEach { repository.upsertStory(it) }
            store.unwritableIds += "broken"
            val fresh = PatreonRawStats(capturedAt = 2L, paidMembers = 42)
            val synced = story().copy(title = "Synced", patreonUrl = "https://patreon.com/creator", patreonStats = fresh)

            val committed =
                repository.commitSyncedStory(synced, metricSnapshot = StoryMetricSnapshot(capturedAt = 2L, patreonRaw = fresh))

            assertEquals("Synced", committed.title)
            assertEquals("Synced", repository.story("story")?.title)
            assertEquals(oldStats, repository.story("broken")?.patreonStats)
            assertEquals(fresh, repository.story("healthy")?.patreonStats)
        }

    @Test
    fun syncWithoutPatreonRefreshLeavesSiblingsAlone() =
        runTest {
            val oldStats = PatreonRawStats(capturedAt = 1L, paidMembers = 10)
            val sibling = story(id = "sibling").copy(patreonUrl = "https://patreon.com/creator", patreonStats = oldStats)
            val store = FakeRepositoryStoryStore(story(), sibling)
            val repository = AppRepository(store, StandardTestDispatcher(testScheduler))
            store.stories.values.forEach { repository.upsertStory(it) }
            val carried = story().copy(patreonUrl = "https://patreon.com/creator", patreonStats = PatreonRawStats(capturedAt = 0L))

            repository.commitSyncedStory(carried, metricSnapshot = StoryMetricSnapshot(capturedAt = 2L))

            assertEquals(oldStats, repository.story("sibling")?.patreonStats)
        }

    @Test
    fun syncCommitIsRejectedWhenTheStoryWasDeletedMidFlight() =
        runTest {
            val store = FakeRepositoryStoryStore(story())
            val repository = AppRepository(store, StandardTestDispatcher(testScheduler))
            repository.upsertStory(store.story("story")!!)

            repository.deleteStory("story")

            val error =
                runCatching {
                    repository.commitSyncedStory(story(), requireExisting = true) { current -> current!! }
                }.exceptionOrNull()

            assertTrue(error is IllegalStateException)
            assertNull(store.story("story"))
        }

    @Test
    fun concurrentIndependentDisplayPreferenceUpdatesBothSurvive() =
        runTest {
            val store = FakeRepositoryStoryStore(story())
            store.display =
                com.vinicius741.webnovelarchiver.domain.model
                    .DisplayPreferences()
            val repository = AppRepository(store, StandardTestDispatcher(testScheduler))

            // Two rapid independent changes (tab + sort) started from the same old document: with
            // plain save-copies the later write would restore the earlier field's old value (R28).
            repository.updateDisplayPreferences { it.copy(libraryTabId = "reading") }
            repository.updateDisplayPreferences { it.copy(librarySortOption = "title") }

            val saved = repository.getDisplayPreferences()
            assertEquals("reading", saved.libraryTabId)
            assertEquals("title", saved.librarySortOption)
        }

    @Test
    fun latestSameFieldChoiceWins() =
        runTest {
            val store = FakeRepositoryStoryStore(story())
            store.display =
                com.vinicius741.webnovelarchiver.domain.model
                    .DisplayPreferences()
            val repository = AppRepository(store, StandardTestDispatcher(testScheduler))

            repository.updateDisplayPreferences { it.copy(libraryTabId = "reading") }
            repository.updateDisplayPreferences { it.copy(libraryTabId = "wishlist") }

            assertEquals("wishlist", repository.getDisplayPreferences().libraryTabId)
        }

    private val readResults = java.util.Collections.synchronizedList(mutableListOf<String?>())
    private val readLatch = java.util.concurrent.CountDownLatch(1)

    @Test
    fun completedChapterCommitMarksStoryAndQueueInOneTransaction() =
        runTest {
            val job =
                DownloadJob(
                    id = "story_0",
                    storyId = "story",
                    status = DownloadJobStatus.Downloading.wire,
                    chapter = Chapter(id = "one", title = "One"),
                )
            val store = FakeRepositoryStoryStore(story())
            val repository = AppRepository(store, StandardTestDispatcher(testScheduler))
            repository.saveQueue(listOf(job))

            val outcome =
                repository.completeDownloadedChapter(job, {
                    assertTrue("Chapter bytes must share the commit lock", Thread.holdsLock(store.transactionLock))
                    "/tmp/one.html"
                }, 5L, repository.libraryGeneration())

            assertEquals(AppRepository.ChapterCommit.COMMITTED, outcome)
            assertTrue(
                store.stories
                    .getValue("story")
                    .chapters
                    .first { it.id == "one" }
                    .downloaded,
            )
            assertEquals(DownloadJobStatus.Completed.wire, repository.queue().single { it.id == "story_0" }.status)
        }

    @Test
    fun completedChapterCommitIsRejectedWhenJobWasCancelledMidFetch() =
        runTest {
            val job =
                DownloadJob(
                    id = "story_0",
                    storyId = "story",
                    status = DownloadJobStatus.Downloading.wire,
                    chapter = Chapter(id = "one", title = "One"),
                )
            val store = FakeRepositoryStoryStore(story())
            val repository = AppRepository(store, StandardTestDispatcher(testScheduler))
            repository.saveQueue(listOf(job))
            store.saveQueue(store.queue().map { it.copy(status = DownloadJobStatus.Cancelled.wire) })

            val outcome =
                repository.completeDownloadedChapter(job, {
                    error("Cancelled download must not write chapter bytes")
                }, 5L, repository.libraryGeneration())

            assertEquals(AppRepository.ChapterCommit.SKIPPED, outcome)
            assertFalse(
                store.stories
                    .getValue("story")
                    .chapters
                    .first { it.id == "one" }
                    .downloaded,
            )
            assertEquals(DownloadJobStatus.Cancelled.wire, store.queue().single { it.id == "story_0" }.status)
        }

    @Test
    fun completedChapterCommitIsRejectedAfterLibraryReplacement() =
        runTest {
            val job =
                DownloadJob(
                    id = "story_0",
                    storyId = "story",
                    status = DownloadJobStatus.Downloading.wire,
                    chapter = Chapter(id = "one", title = "One"),
                )
            val store = FakeRepositoryStoryStore(story())
            val repository = AppRepository(store, StandardTestDispatcher(testScheduler))
            repository.saveQueue(listOf(job))
            val staleGeneration = repository.libraryGeneration()
            repository.invalidateLibraryGeneration()

            val outcome =
                repository.completeDownloadedChapter(job, {
                    error("Stale download must not overwrite restored content")
                }, 5L, staleGeneration)

            assertEquals(AppRepository.ChapterCommit.SKIPPED, outcome)
            assertFalse(
                store.stories
                    .getValue("story")
                    .chapters
                    .first { it.id == "one" }
                    .downloaded,
            )
        }

    @Test
    fun completedChapterCommitReportsMissingChapterInsteadOfSilentlySkipping() =
        runTest {
            val job =
                DownloadJob(
                    id = "story_0",
                    storyId = "story",
                    status = DownloadJobStatus.Downloading.wire,
                    chapter = Chapter(id = "gone", title = "Gone"),
                )
            val store = FakeRepositoryStoryStore(story())
            val repository = AppRepository(store, StandardTestDispatcher(testScheduler))
            repository.saveQueue(listOf(job))

            assertEquals(
                AppRepository.ChapterCommit.CHAPTER_MISSING,
                repository.completeDownloadedChapter(
                    job,
                    { error("Missing chapter must not write a file") },
                    5L,
                    repository.libraryGeneration(),
                ),
            )
        }

    private class FakeRepositoryStoryStore(
        vararg initial: Story,
    ) : RepositoryStoryStore {
        override val transactionLock = Any()
        val stories = initial.associateByTo(linkedMapOf()) { it.id }
        private var queue: List<DownloadJob> = emptyList()
        var libraryReadCount = 0
        var display =
            com.vinicius741.webnovelarchiver.domain.model
                .DisplayPreferences()

        override fun stories(): List<Story> {
            libraryReadCount += 1
            return stories.values.toList()
        }

        override fun story(id: String): Story? = stories[id]

        val unwritableIds = mutableSetOf<String>()

        override fun addOrUpdateStory(story: Story) {
            check(story.id !in unwritableIds) { "Refusing to overwrite unhealthy ${story.id}" }
            stories[story.id] = story
        }

        override fun deleteStory(id: String) {
            stories.remove(id)
        }

        override fun saveLibrary(stories: List<Story>) {
            this.stories.clear()
            stories.associateByTo(this.stories) { it.id }
        }

        override fun queue(): List<DownloadJob> = queue

        override fun saveQueue(jobs: List<DownloadJob>) {
            queue = jobs
        }

        override fun displayPreferences(): com.vinicius741.webnovelarchiver.domain.model.DisplayPreferences = display.copy()

        override fun saveDisplayPreferences(preferences: com.vinicius741.webnovelarchiver.domain.model.DisplayPreferences) {
            display = preferences.copy()
        }
    }

    private fun story(id: String = "story") =
        Story(
            id = id,
            title = "Story $id",
            chapters =
                mutableListOf(
                    Chapter(id = "one", title = "One"),
                    Chapter(id = "two", title = "Two"),
                ),
        )
}
