package com.vinicius741.webnovelarchiver.data.repository

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.DisplayPreferences
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.Story

/** In-memory [RepositoryStoryStore] that counts reads and writes, shared by the repository tests. */
internal class FakeRepositoryStoryStore(
    vararg initial: Story,
) : RepositoryStoryStore {
    override val transactionLock = Any()
    val stories = initial.associateByTo(linkedMapOf()) { it.id }
    private var queue: List<DownloadJob> = emptyList()
    var libraryReadCount = 0
    var queueReadCount = 0
    var membershipSaves = 0
    var memberSaves = 0
    var failQueueWrites = false
    val unwritableIds = mutableSetOf<String>()
    var display = DisplayPreferences()

    /** The stored document, for assertions; the repository itself never reads single stories. */
    fun story(id: String): Story? = stories[id]

    override fun stories(): List<Story> {
        libraryReadCount += 1
        return stories.values.toList()
    }

    override fun addOrUpdateStory(story: Story) {
        check(story.id !in unwritableIds) { "Refusing to overwrite unhealthy ${story.id}" }
        membershipSaves += 1
        story.dateAdded = stories[story.id]?.dateAdded ?: story.dateAdded
        stories[story.id] = story
    }

    override fun saveLibraryMember(story: Story) {
        check(story.id !in unwritableIds) { "Refusing to overwrite unhealthy ${story.id}" }
        check(story.id in stories) { "saveLibraryMember called for a story outside the library" }
        memberSaves += 1
        stories[story.id] = story
    }

    override fun deleteStory(id: String) {
        stories.remove(id)
        queue = queue.filterNot { it.storyId == id }
    }

    override fun saveLibrary(stories: List<Story>) {
        this.stories.clear()
        stories.associateByTo(this.stories) { it.id }
    }

    override fun queue(): List<DownloadJob> {
        queueReadCount += 1
        return queue
    }

    /** The stored queue, for assertions; not counted as a repository read. */
    fun storedQueue(): List<DownloadJob> = queue

    override fun saveQueue(jobs: List<DownloadJob>) {
        check(!failQueueWrites) { "Queue write failed" }
        queue = jobs.map { it.copy(chapter = it.chapter.copy()) }
    }

    override fun displayPreferences(): DisplayPreferences = display.copy()

    override fun saveDisplayPreferences(preferences: DisplayPreferences) {
        display = preferences.copy()
    }
}

internal fun fakeStory(id: String = "story") =
    Story(
        id = id,
        title = "Story $id",
        chapters =
            mutableListOf(
                Chapter(id = "one", title = "One"),
                Chapter(id = "two", title = "Two"),
            ),
    )
