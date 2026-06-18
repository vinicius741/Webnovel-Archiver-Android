package com.vinicius741.webnovelarchiver.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DownloadEnqueueTransactionTest {
    @Test
    fun enqueuePersistsLatestStoryInsteadOfStaleUiSnapshot() {
        val staleStory = Story(
            id = "story",
            chapters = mutableListOf(
                Chapter(id = "one"),
                Chapter(id = "two"),
            ),
        )
        val currentStory = staleStory.copy(
            chapters = staleStory.chapters.map { chapter -> chapter.copy() }.toMutableList(),
        ).apply {
            chapters[0].downloaded = true
            chapters[0].filePath = "novels/story/one.html"
            downloadedChapters = 1
        }
        var persistedStory: Story? = null

        DownloadEnqueueTransaction.execute(
            lock = Any(),
            story = staleStory,
            indexes = listOf(1),
            now = 1L,
            readQueue = { emptyList() },
            readStory = { currentStory },
            persist = { story, _ -> persistedStory = story },
        )

        assertEquals(true, persistedStory?.chapters?.get(0)?.downloaded)
        assertEquals("novels/story/one.html", persistedStory?.chapters?.get(0)?.filePath)
    }

    @Test
    fun enqueueAndServiceStatusMutationPreserveEachOthersUpdates() {
        repeat(50) {
            val lock = Any()
            val story = Story(
                id = "story",
                chapters = mutableListOf(
                    Chapter(id = "one"),
                    Chapter(id = "two"),
                ),
            )
            var queue: List<DownloadJob> = DownloadQueuePlanning
                .queueChapters(emptyList(), story, listOf(0))
                .jobs
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)

            executor.submit {
                ready.countDown()
                start.await()
                synchronized(lock) {
                    val current = queue.map { job -> job.copy(chapter = job.chapter.copy()) }.toMutableList()
                    Thread.sleep(2)
                    current.first { job -> job.id == "story_0" }.status = DownloadJobStatus.Downloading.wire
                    queue = current
                }
            }
            executor.submit {
                ready.countDown()
                start.await()
                DownloadEnqueueTransaction.execute(
                    lock = lock,
                    story = story,
                    indexes = listOf(1),
                    now = 1L,
                    readQueue = { queue },
                    readStory = { story },
                    persist = { _, jobs -> queue = jobs },
                )
            }

            ready.await(1, TimeUnit.SECONDS)
            start.countDown()
            executor.shutdown()
            executor.awaitTermination(2, TimeUnit.SECONDS)

            assertEquals(setOf("story_0", "story_1"), queue.map { job -> job.id }.toSet())
            assertEquals(
                DownloadJobStatus.Downloading.wire,
                queue.first { job -> job.id == "story_0" }.status,
            )
        }
    }

    @Test
    fun concurrentEnqueuesPreserveBothQueueUpdates() {
        repeat(50) {
            val lock = Any()
            var queue: List<DownloadJob> = emptyList()
            val story = Story(
                id = "story",
                title = "Story",
                chapters = mutableListOf(
                    Chapter(id = "one", title = "One"),
                    Chapter(id = "two", title = "Two"),
                ),
            )
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)

            listOf(0, 1).forEach { index ->
                executor.submit {
                    ready.countDown()
                    start.await()
                    DownloadEnqueueTransaction.execute(
                        lock = lock,
                        story = story,
                        indexes = listOf(index),
                        now = 1L,
                        readQueue = {
                            Thread.sleep(2)
                            queue
                        },
                        readStory = { story },
                        persist = { _, jobs -> queue = jobs },
                    )
                }
            }

            ready.await(1, TimeUnit.SECONDS)
            start.countDown()
            executor.shutdown()
            executor.awaitTermination(2, TimeUnit.SECONDS)

            assertEquals(setOf("story_0", "story_1"), queue.map { job -> job.id }.toSet())
        }
    }
}
