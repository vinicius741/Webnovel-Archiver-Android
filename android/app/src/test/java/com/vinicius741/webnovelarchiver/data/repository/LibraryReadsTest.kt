package com.vinicius741.webnovelarchiver.data.repository

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.Story
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryReadsTest {
    @Test
    fun readOnlyAccessorsServeThePublishedInstancesWithoutCopying() =
        runTest {
            val repository = repositoryWith(story("a"), story("b"))

            val published = repository.downloadState.value.library
            assertEquals(listOf("a", "b"), repository.readOnlyLibrary().map { it.id })
            repository.readOnlyLibrary().zip(published).forEach { (served, shared) -> assertSame(shared, served) }
            assertSame(published.first(), repository.readOnlyStory("a"))
            assertNull(repository.readOnlyStory("missing"))
        }

    @Test
    fun copyingAccessorsStillDetachEveryChapter() =
        runTest {
            val repository = repositoryWith(story("a"))

            val copy = repository.story("a")!!
            assertNotSame(repository.readOnlyStory("a"), copy)
            assertNotSame(repository.readOnlyStory("a")!!.chapters.first(), copy.chapters.first())
            copy.chapters.first().title = "edited by a caller"
            copy.tabId = "tab"
            val copies = repository.library()
            copies.single().chapters.clear()

            val published = repository.readOnlyStory("a")!!
            assertEquals("One", published.chapters.first().title)
            assertNull(published.tabId)
            assertEquals(2, published.chapters.size)
        }

    @Test
    fun aReadOnlyViewKeepsItsSnapshotWhileATransactionPublishesANewOne() =
        runTest {
            val repository = repositoryWith(story("a"))
            val before = repository.readOnlyLibrary()
            val beforeStory = repository.readOnlyStory("a")!!

            repository.toggleBookmark("a", "two")
            repository.upsertStory(story("c"))

            assertEquals(listOf("a"), before.map { it.id })
            assertNull(beforeStory.lastReadChapterId)
            assertEquals("two", repository.readOnlyStory("a")!!.lastReadChapterId)
            assertEquals(listOf("a", "c"), repository.readOnlyLibrary().map { it.id })
        }

    @Test
    fun narrowQueriesAnswerFromThePublishedLibrary() =
        runTest {
            val repository = repositoryWith(story("a"))

            assertTrue(repository.hasStory("a"))
            assertFalse(repository.hasStory("missing"))
            assertEquals("Story a", repository.storyTitle("a"))
            assertNull(repository.storyTitle("missing"))
            assertTrue(repository.hasChapter("a", "two"))
            assertFalse(repository.hasChapter("a", "three"))
            assertFalse(repository.hasChapter("missing", "two"))
            assertEquals("Two", repository.chapterTitle("a", "two"))
            assertNull(repository.chapterTitle("a", "three"))
            assertNull(repository.chapterTitle("missing", "two"))

            repository.deleteStory("a")

            assertFalse(repository.hasStory("a"))
            assertFalse(repository.hasChapter("a", "two"))
        }

    private suspend fun kotlinx.coroutines.test.TestScope.repositoryWith(vararg stories: Story): AppRepository {
        val repository = AppRepository(InMemoryStore(), StandardTestDispatcher(testScheduler))
        repository.saveLibrary(stories.toList())
        return repository
    }

    private class InMemoryStore : RepositoryStoryStore {
        override val transactionLock = Any()
        private val stories = linkedMapOf<String, Story>()
        private var queue: List<DownloadJob> = emptyList()

        override fun stories(): List<Story> = stories.values.toList()

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

    private fun story(id: String) =
        Story(
            id = id,
            title = "Story $id",
            chapters = mutableListOf(Chapter(id = "one", title = "One"), Chapter(id = "two", title = "Two")),
        )
}
