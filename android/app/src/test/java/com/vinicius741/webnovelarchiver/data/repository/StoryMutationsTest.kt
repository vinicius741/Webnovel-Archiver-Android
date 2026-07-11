package com.vinicius741.webnovelarchiver.data.repository

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.DownloadStatus
import com.vinicius741.webnovelarchiver.domain.model.Story
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StoryMutationsTest {
    @Test
    fun epubCompletionAppliedAfterBookmarkPreservesBothConcurrentChanges() {
        val operationSnapshot = story()
        val afterBookmark = StoryMutations.toggleBookmark(operationSnapshot, "c2")!!

        // EPUB generation started with operationSnapshot, but commits its semantic result to the
        // latest persisted value after the bookmark transaction wins the race.
        val committed = StoryMutations.markEpubGenerated(afterBookmark, listOf("epubs/s/book.epub"))

        assertEquals("c2", committed.lastReadChapterId)
        assertEquals(listOf("epubs/s/book.epub"), committed.epubPaths)
        assertFalse(committed.epubStale!!)
    }

    @Test
    fun setLastReadChapterUpdatesOnlyProgressField() {
        val latest = story().copy(title = "Keep me", lastReadChapterId = "c1")

        val committed = StoryMutations.setLastReadChapter(latest, "c2")

        assertEquals("c2", committed.lastReadChapterId)
        assertEquals("Keep me", committed.title)
        assertEquals(latest.chapters.map { it.id }, committed.chapters.map { it.id })
    }

    @Test
    fun cleanupCompletionAppliedAfterDownloadPreservesDownloadedChapter() {
        val operationSnapshot = story().copy(epubStale = false)
        val afterDownload =
            StoryMutations.markChapterDownloaded(
                latest = operationSnapshot,
                chapterId = "c2",
                path = "novels/s/c2.html",
                completedAt = 200L,
            )!!

        val committed = StoryMutations.markCleanupApplied(afterDownload)

        val c2 = committed.chapters.single { it.id == "c2" }
        assertTrue(c2.downloaded)
        assertEquals("novels/s/c2.html", c2.filePath)
        assertEquals(200L, c2.downloadedAt)
        assertEquals(2, committed.downloadedChapters)
        assertEquals(DownloadStatus.completed, committed.status)
        assertTrue(committed.epubStale!!)
    }

    @Test
    fun bookmarkAppliedToLatestSyncResultPreservesSyncMetadataAndChapters() {
        val operationSnapshot = story()
        val synced =
            operationSnapshot.copy(
                title = "Title refreshed by sync",
                chapters =
                    (operationSnapshot.chapters + Chapter(id = "c3", title = "Three"))
                        .toMutableList(),
                totalChapters = 3,
                lastChapterSyncAt = 500L,
            )

        val committed = StoryMutations.toggleBookmark(synced, "c2")!!

        assertEquals("c2", committed.lastReadChapterId)
        assertEquals("Title refreshed by sync", committed.title)
        assertEquals(listOf("c1", "c2", "c3"), committed.chapters.map { it.id })
        assertEquals(500L, committed.lastChapterSyncAt)
    }

    @Test
    fun observableSnapshotDoesNotShareMutableCollections() {
        val persisted = story().copy(tags = mutableListOf("fantasy"))

        val published = StoryMutations.snapshot(persisted)
        persisted.chapters.first().title = "mutated"
        persisted.tags!!.add("new")

        assertNotSame(persisted.chapters, published.chapters)
        assertEquals("One", published.chapters.first().title)
        assertEquals(listOf("fantasy"), published.tags)
    }

    @Test
    fun concurrentSemanticTransactionsPreserveEpubBookmarkCleanupAndDownloadChanges() {
        val epubStore = FakeStoryStore(story())

        runConcurrently(
            { epubStore.update { StoryMutations.toggleBookmark(it, "c2")!! } },
            { epubStore.update { StoryMutations.markEpubGenerated(it, listOf("epubs/s/book.epub")) } },
        )
        val epubCommitted = epubStore.snapshot()
        assertEquals("c2", epubCommitted.lastReadChapterId)
        assertEquals(listOf("epubs/s/book.epub"), epubCommitted.epubPaths)
        assertFalse(epubCommitted.epubStale!!)

        val cleanupStore = FakeStoryStore(story().copy(epubStale = false))
        runConcurrently(
            {
                cleanupStore.update {
                    StoryMutations.markChapterDownloaded(it, "c2", "novels/s/c2.html", 300L)!!
                }
            },
            { cleanupStore.update(StoryMutations::markCleanupApplied) },
        )
        val cleanupCommitted = cleanupStore.snapshot()
        assertTrue(cleanupCommitted.chapters.single { it.id == "c2" }.downloaded)
        assertEquals(2, cleanupCommitted.downloadedChapters)
        assertTrue(cleanupCommitted.epubStale!!)
    }

    private fun runConcurrently(vararg operations: () -> Unit) {
        val ready = CountDownLatch(operations.size)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(operations.size)
        try {
            val futures =
                operations.map { operation ->
                    executor.submit {
                        ready.countDown()
                        check(start.await(5, TimeUnit.SECONDS))
                        operation()
                    }
                }
            check(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    private class FakeStoryStore(
        initial: Story,
    ) {
        private var current = initial

        @Synchronized
        fun update(block: (Story) -> Story) {
            current = block(current)
        }

        @Synchronized
        fun snapshot(): Story = StoryMutations.snapshot(current)
    }

    private fun story(): Story =
        Story(
            id = "s",
            title = "Story",
            totalChapters = 2,
            downloadedChapters = 1,
            chapters =
                mutableListOf(
                    Chapter(id = "c1", title = "One", downloaded = true, filePath = "novels/s/c1.html"),
                    Chapter(id = "c2", title = "Two"),
                ),
            epubStale = true,
            pendingNewChapterIds = mutableListOf("c2"),
        )
}
