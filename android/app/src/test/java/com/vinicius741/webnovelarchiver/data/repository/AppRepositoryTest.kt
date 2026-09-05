package com.vinicius741.webnovelarchiver.data.repository

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.SourceAvailability
import com.vinicius741.webnovelarchiver.domain.model.Story
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class AppRepositoryTest {
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
    fun manifestOnlyRepublishBumpsVersionsWithoutRereadingStoryDocuments() =
        runTest {
            val store = FakeRepositoryStoryStore(story())
            val repository = AppRepository(store, StandardTestDispatcher(testScheduler))
            repository.upsertStory(store.story("story")!!)
            val cachedTitle = repository.story("story")!!.title
            val before = repository.downloadState.value

            store.stories["story"] = store.story("story")!!.copy(title = "Externally Changed")
            repository.republishLibrarySnapshot()

            // R26: a rewrite toggle must not re-read the story document; the cached snapshot wins.
            assertEquals(cachedTitle, repository.story("story")!!.title)
            assertTrue(repository.downloadState.value.libraryVersion > before.libraryVersion)
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

        override fun addOrUpdateStory(story: Story) {
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
