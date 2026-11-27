package com.vinicius741.webnovelarchiver.domain.download

import com.vinicius741.webnovelarchiver.data.storage.DurableJson
import com.vinicius741.webnovelarchiver.data.storage.DurableReadResult
import com.vinicius741.webnovelarchiver.data.storage.SharedGson
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.download.DownloadDetailsPlanning
import com.vinicius741.webnovelarchiver.download.DownloadQueuePlanning
import com.vinicius741.webnovelarchiver.download.DownloadQueueSectionPlanning
import com.vinicius741.webnovelarchiver.download.downloadCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FinishedJobCompactionTest {
    @Test
    fun rangesRoundTrip() {
        assertEquals("0-2,5,7-8", FinishedJobCompaction.encodeIndexes(listOf(8, 0, 2, 1, 5, 7, 2)))
        assertEquals(listOf(0, 1, 2, 5, 7, 8), FinishedJobCompaction.parseIndexes("0-2,5,7-8"))
        assertEquals(listOf(4), FinishedJobCompaction.parseIndexes("4"))
        listOf("", "a", "3-1", "-1", "1-2-3", "0-1,x", "0-200000").forEach { bad ->
            assertNull(bad, FinishedJobCompaction.parseIndexes(bad))
        }
    }

    @Test
    fun completedJobsOfAStoryCollapseIntoOneRecordAtTheirFirstPosition() {
        val queue =
            listOf(
                job("a", 0, completed),
                job("b", 0, completed),
                job("a", 1, completed),
                job("a", 2, pending),
                job("b", 1, completed),
                job("a", 3, completed),
            )

        val stored = FinishedJobCompaction.compact(queue)

        assertEquals(listOf("a_0", "b_0", "a_2"), stored.map { it.id })
        assertEquals("0-1,3", stored[0].finishedChapterIndexes)
        assertEquals("0-1", stored[1].finishedChapterIndexes)
        assertNull(stored[2].finishedChapterIndexes)
        assertEquals(Chapter(), stored[0].chapter)
    }

    @Test
    fun expansionRestoresEveryCompletedJobAndKeepsTheScreenTheSame() {
        val queue =
            listOf(
                job("a", 0, completed),
                job("b", 0, completed),
                job("a", 1, completed),
                job("c", 0, pending),
                job("a", 2, DownloadJobStatus.Failed.wire),
                job("b", 1, completed),
                job("c", 1, DownloadJobStatus.Downloading.wire),
                job("d", 0, DownloadJobStatus.Paused.wire),
                job("d", 1, completed),
                job("a", 3, completed),
            )

        val loaded = FinishedJobCompaction.expand(FinishedJobCompaction.compact(queue))

        assertEquals(queue.map { it.id }.sorted(), loaded.map { it.id }.sorted())
        assertEquals(queue.associate { it.id to it.status }, loaded.associate { it.id to it.status })
        assertEquals(queue.downloadCounts(), loaded.downloadCounts())
        assertEquals(screen(queue), screen(loaded))
        queue.map { it.storyId }.distinct().forEach { storyId ->
            assertEquals(
                DownloadDetailsPlanning.summarizeStoryDownload(queue.filter { it.storyId == storyId }),
                DownloadDetailsPlanning.summarizeStoryDownload(loaded.filter { it.storyId == storyId }),
            )
        }
        assertEquals(
            DownloadDetailsPlanning.chapterJobStatuses(queue),
            DownloadDetailsPlanning.chapterJobStatuses(loaded),
        )
        // Stable: storing the loaded queue again writes the same records.
        assertEquals(FinishedJobCompaction.compact(queue), FinishedJobCompaction.compact(loaded))
    }

    @Test
    fun queueingAStoryAgainPlansTheSameWorkFromALoadedQueue() {
        val story = story("a", chapters = 4, downloaded = setOf(0, 1))
        val queue = (0..3).map { index -> job("a", index, completed, chapter = story.chapters[index]) } + job("b", 0, pending)
        val loaded = FinishedJobCompaction.expand(FinishedJobCompaction.compact(queue))

        val fromQueue = DownloadQueuePlanning.queueChapters(queue, story, listOf(0, 1, 2, 3))
        val fromLoaded = DownloadQueuePlanning.queueChapters(loaded, story, listOf(0, 1, 2, 3))

        fun shape(jobs: List<DownloadJob>) = jobs.map { Triple(it.id, it.chapterIndex, it.status) }
        assertEquals(shape(fromQueue.jobs), shape(fromLoaded.jobs))
        assertEquals(fromQueue.hasRunnableWork, fromLoaded.hasRunnableWork)
        assertEquals(story.chapters[2], fromLoaded.jobs.single { it.id == "a_2" }.chapter)
    }

    @Test
    fun onlyPlainCompletedJobsCollapse() {
        val keep =
            listOf(
                job("a", 0, completed).copy(id = "a_0_custom"),
                job("a", 1, completed).copy(sourceId = null),
                job("a", 2, completed).copy(errorCode = "X"),
                job("a", 3, pending),
                job("a", 4, DownloadJobStatus.Cancelled.wire),
                job("b", 0, completed),
            )
        assertSame(keep, FinishedJobCompaction.compact(keep))

        val retried = listOf(job("a", 0, completed), job("a", 1, completed).copy(retryCount = 2), job("a", 2, completed))
        val stored = FinishedJobCompaction.compact(retried)
        assertEquals(listOf("0,2", "1"), stored.map { it.finishedChapterIndexes ?: "1" })

        fun shape(jobs: List<DownloadJob>) = jobs.map { Triple(it.id, it.status, it.retryCount) }.toSet()
        assertEquals(shape(retried), shape(FinishedJobCompaction.expand(stored)))
    }

    @Test
    fun oldQueueFilesLoadUnchanged() {
        val legacy =
            """
            {"schemaVersion":1,"appVersion":"1.2.12-native","payload":[
              {"id":"a_0","storyId":"a","storyTitle":"A","sourceId":"royal_road","chapterIndex":0,
               "chapter":{"id":"c0","title":"One","url":"https://example.test/0","downloaded":false},
               "status":"completed","addedAt":5,"retryCount":0,"maxRetries":3},
              {"id":"a_1","storyId":"a","storyTitle":"A","chapterIndex":1,
               "chapter":{"id":"c1","title":"Two","url":"https://example.test/1"},"status":"pending","addedAt":5}
            ]}
            """.trimIndent()

        val jobs = FinishedJobCompaction.expand(decode(legacy))

        assertEquals(listOf("a_0", "a_1"), jobs.map { it.id })
        assertEquals("One", jobs[0].chapter.title)
        assertNull(jobs[1].sourceId)
    }

    @Test
    fun anUnreadableRecordLoadsAsOneCompletedJob() {
        val record = job("a", 0, completed).copy(chapter = Chapter(), finishedChapterIndexes = "zz")

        val jobs = FinishedJobCompaction.expand(listOf(record, job("a", 5, pending)))

        assertEquals(listOf("a_0", "a_5"), jobs.map { it.id })
        assertNull(jobs[0].finishedChapterIndexes)
    }

    /** What the current release does with a stored record: Gson into its job class, unknown keys ignored. */
    @Test
    fun anAppWithoutCompactionReadsARecordAsOneCompletedJobOfThatStory() {
        val stored = FinishedJobCompaction.compact((0..9).map { job("a", it, completed) } + job("a", 10, pending))
        val text = SharedGson.pretty.toJson(DurableJson.envelope(stored, "test"))

        val result = DurableJson.decodeText<List<ReleasedDownloadJob>>(text, SharedGson.pretty)

        val jobs = (result as DurableReadResult.Present).value
        assertEquals(listOf("a_0" to "completed", "a_10" to "pending"), jobs.map { it.id to it.status })
        assertEquals("a", jobs[0].storyId)
        assertEquals("Story a", jobs[0].storyTitle)
        assertEquals("royal_road", jobs[0].sourceId)
        assertEquals(Chapter(), jobs[0].chapter)
        assertTrue(Regex(""""finishedChapterIndexes":\s*"0-9"""").containsMatchIn(text))
    }

    private fun decode(text: String): List<DownloadJob> =
        (DurableJson.decodeText<List<DownloadJob>>(text, SharedGson.pretty) as DurableReadResult.Present).value

    private fun screen(jobs: List<DownloadJob>) =
        DownloadQueueSectionPlanning
            .plan(jobs, providerIdForJob = { it.sourceId }, isSourceBlocked = { false })
            .let { plan ->
                listOf(plan.downloading, plan.upNext, plan.paused, plan.finished).map { section ->
                    section.map { story ->
                        listOf(
                            story.storyId,
                            story.title,
                            story.providerId,
                            story.section,
                            story.counts,
                            story.blocked,
                            story.behindTitle,
                            story.jobs.filter { it.status != completed }.map { it.id },
                        )
                    }
                } + listOf(plan.counts, plan.storyCount, plan.failedChapters)
            }

    private companion object {
        val completed = DownloadJobStatus.Completed.wire
        val pending = DownloadJobStatus.Pending.wire

        fun job(
            storyId: String,
            index: Int,
            status: String,
            chapter: Chapter =
                Chapter(id = "$storyId-c$index", title = "Chapter ${index + 1}", url = "https://example.test/$storyId/$index"),
        ) = DownloadJob(
            id = "${storyId}_$index",
            storyId = storyId,
            storyTitle = "Story $storyId",
            sourceId = "royal_road",
            chapterIndex = index,
            chapter = chapter,
            status = status,
            addedAt = 1_000L,
        )

        fun story(
            id: String,
            chapters: Int,
            downloaded: Set<Int>,
        ) = Story(
            id = id,
            title = "Story $id",
            sourceId = "royal_road",
            chapters =
                (0 until chapters)
                    .map { index ->
                        Chapter(
                            id = "$id-c$index",
                            title = "Chapter ${index + 1}",
                            url = "https://example.test/$id/$index",
                            downloaded = index in downloaded,
                        )
                    }.toMutableList(),
        )
    }
}

/** The job class as the current release (without [DownloadJob.finishedChapterIndexes]) declares it. */
private data class ReleasedDownloadJob(
    var id: String = "",
    var storyId: String = "",
    var storyTitle: String = "",
    var sourceId: String? = null,
    var chapterIndex: Int = 0,
    var chapter: Chapter = Chapter(),
    var status: String = "pending",
    var addedAt: Long = System.currentTimeMillis(),
    var retryCount: Int = 0,
    var maxRetries: Int = 3,
    var error: String? = null,
    var errorCategory: String? = null,
    var errorCode: String? = null,
    var nextRetryAt: Long? = null,
)
