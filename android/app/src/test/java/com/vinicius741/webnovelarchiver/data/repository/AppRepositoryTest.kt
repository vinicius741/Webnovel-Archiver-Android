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
import org.junit.Test

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

    private class FakeRepositoryStoryStore(
        vararg initial: Story,
    ) : RepositoryStoryStore {
        override val transactionLock = Any()
        val stories = initial.associateByTo(linkedMapOf()) { it.id }
        private var queue: List<DownloadJob> = emptyList()
        var libraryReadCount = 0

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
