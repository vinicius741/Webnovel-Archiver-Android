package com.vinicius741.webnovelarchiver.data.repository

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
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

            repository.upsertStory(store.getStory("story")!!)
            val beforeMutation = repository.getStory("story")!!
            repository.toggleBookmark("story", "two")

            val published = repository.getStory("story")!!
            assertEquals("two", published.lastReadChapterId)
            assertEquals(0, store.libraryReadCount)
            assertNotSame(store.getStory("story")!!.chapters, published.chapters)

            beforeMutation.chapters.first().title = "changed outside repository"
            assertEquals(
                "One",
                repository
                    .getStory("story")!!
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

            assertNull(repository.getStory("story"))
            assertEquals(listOf("other"), repository.getLibrary().map { it.id })
            assertEquals(0, store.libraryReadCount)
        }

    private class FakeRepositoryStoryStore(
        vararg initial: Story,
    ) : RepositoryStoryStore {
        override val transactionLock = Any()
        val stories = initial.associateByTo(linkedMapOf()) { it.id }
        private var queue: List<DownloadJob> = emptyList()
        var libraryReadCount = 0

        override fun getLibrary(): List<Story> {
            libraryReadCount += 1
            return stories.values.toList()
        }

        override fun getStory(id: String): Story? = stories[id]

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

        override fun getQueue(): List<DownloadJob> = queue

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
