package com.vinicius741.webnovelarchiver.data.storage

import com.vinicius741.webnovelarchiver.domain.download.DownloadJobTransitions
import com.vinicius741.webnovelarchiver.domain.download.FinishedJobCompaction
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.download.DownloadQueuePlanning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Writer

/**
 * Bytes `download_queue.json` costs per downloaded chapter, written exactly as [AppStorage] writes
 * it (the shared Gson inside the durable envelope). A chapter rewrites the whole file twice: the
 * process loop's claim and the chapter commit. Chapters are shaped like a real Royal Road novel's.
 */
class QueueWriteCostTest {
    private data class Cost(
        val writes: Int,
        val bytes: Long,
        val largestFile: Int,
    )

    @Test
    fun aTwoThousandChapterEnqueueWritesLessPerChapter() {
        val story = royalRoadStory("rr_86141", "Cultivation Struggles of a Lowly Fox", chapters = 2_000)

        val before = downloadAll(emptyList(), story, compact = false)
        val after = downloadAll(emptyList(), story, compact = true)

        report("2,000-chapter enqueue", 2_000, before, after)
        assertEquals(before.writes, after.writes)
        assertTrue(after.bytes * 3 < before.bytes * 2)
    }

    @Test
    fun finishedNovelsStopWeighingOnLaterDownloads() {
        // Twenty finished 300-chapter novels the user has not cleared, then a new 200-chapter novel.
        val history =
            (1..20).flatMap { number ->
                val finished = royalRoadStory("rr_$number", "Finished Novel Number $number", chapters = 300)
                downloadedQueue(finished)
            }
        val story = royalRoadStory("rr_new", "A New Novel", chapters = 200)

        val before = downloadAll(history, story, compact = false)
        val after = downloadAll(history, story, compact = true)

        report("200 chapters behind 6,000 finished jobs", 200, before, after)
        assertTrue(after.bytes * 20 < before.bytes)
    }

    private fun downloadAll(
        history: List<DownloadJob>,
        story: Story,
        compact: Boolean,
    ): Cost {
        var writes = 0
        var bytes = 0L
        var largest = 0

        fun write(jobs: List<DownloadJob>) {
            val payload = if (compact) FinishedJobCompaction.compact(jobs) else jobs
            val counter = Utf8ByteCounter()
            SharedGson.pretty.toJson(DurableJson.envelope(payload, APP_VERSION), counter)
            val size = counter.bytes.toInt()
            writes += 1
            bytes += size
            largest = maxOf(largest, size)
        }
        val queue = DownloadQueuePlanning.queueChapters(history, story, story.chapters.indices.toList()).jobs
        write(queue)
        for (position in queue.indices) {
            val job = queue[position]
            if (job.storyId != story.id) continue
            DownloadJobTransitions.claim(job)
            write(queue)
            queue[position] = DownloadJobTransitions.completed(job)!!
            write(queue)
        }
        return Cost(writes, bytes, largest)
    }

    private fun report(
        scenario: String,
        chapters: Int,
        before: Cost,
        after: Cost,
    ) {
        println(
            "QueueWriteCost [$scenario] writes=${before.writes} " +
                "before: ${before.bytes / chapters} B/chapter, ${before.bytes} B total, largest file ${before.largestFile} B; " +
                "after: ${after.bytes / chapters} B/chapter, ${after.bytes} B total, largest file ${after.largestFile} B; " +
                "ratio %.2f".format(before.bytes.toDouble() / after.bytes),
        )
    }

    private fun downloadedQueue(story: Story): List<DownloadJob> =
        DownloadQueuePlanning.queueChapters(emptyList(), story, story.chapters.indices.toList()).jobs.map { job ->
            DownloadJobTransitions.claim(job)
            DownloadJobTransitions.completed(job)!!
        }

    private companion object {
        const val APP_VERSION = "1.2.12-native"

        fun royalRoadStory(
            id: String,
            title: String,
            chapters: Int,
        ): Story {
            val fiction = id.removePrefix("rr_")
            val slug = title.lowercase().replace(' ', '-')
            return Story(
                id = id,
                title = title,
                sourceId = "royal_road",
                sourceUrl = "https://www.royalroad.com/fiction/$fiction/$slug",
                chapters =
                    (0 until chapters)
                        .map { index ->
                            val chapterId = (1_565_049 + index).toString()
                            val chapterSlug = "chapter-${index + 1}-cute-grandpa"
                            Chapter(
                                id = chapterId,
                                title = "Chapter ${index + 1} – Cute Grandpa",
                                url = "https://www.royalroad.com/fiction/$fiction/$slug/chapter/$chapterId/$chapterSlug",
                                publishedAt = 1_717_610_402_000L + index * 86_400_000L,
                            )
                        }.toMutableList(),
            )
        }
    }
}

/** Counts the UTF-8 bytes Gson streams, without holding a multi-megabyte string per write. */
private class Utf8ByteCounter : Writer() {
    var bytes = 0L
        private set

    override fun write(
        cbuf: CharArray,
        off: Int,
        len: Int,
    ) {
        for (index in off until off + len) {
            val char = cbuf[index]
            bytes +=
                when {
                    char.code < 0x80 -> 1
                    char.code < 0x800 -> 2
                    // A surrogate pair is 4 bytes in all, 2 per half.
                    char.isSurrogate() -> 2
                    else -> 3
                }
        }
    }

    override fun flush() = Unit

    override fun close() = Unit
}
