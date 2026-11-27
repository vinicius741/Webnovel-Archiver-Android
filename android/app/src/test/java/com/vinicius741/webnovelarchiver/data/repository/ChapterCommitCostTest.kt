package com.vinicius741.webnovelarchiver.data.repository

import com.google.gson.reflect.TypeToken
import com.vinicius741.webnovelarchiver.data.storage.DurableJson
import com.vinicius741.webnovelarchiver.data.storage.DurableReadResult
import com.vinicius741.webnovelarchiver.data.storage.SharedGson
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.StoryNormalization
import com.vinicius741.webnovelarchiver.download.downloadQueueStore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Type
import java.util.Locale

/**
 * Disk work per downloaded chapter of a 931-chapter story (about 320 KB of pretty story JSON), over a
 * store that mirrors AppStorage's documents: real Gson parse, pretty envelope and fsync'd writes.
 * It drives the same calls the download loop makes per chapter (unsupported-source cleanup, claim,
 * the engine's story lookup, the commit) and pins that none of them parses a document; the printed
 * timings (`COST` lines in the test's stdout) are the evidence for the in-memory read path.
 */
class ChapterCommitCostTest {
    private lateinit var dir: File

    @Before fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "commit_cost_${System.nanoTime()}").apply { mkdirs() }
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun measureWithWholeStoryQueued() = measure(queued = 931)

    @Test
    fun measureWithShortQueue() = measure(queued = 10)

    private fun measure(queued: Int) =
        runTest {
            val chapters = 931
            File(dir, "q$queued").mkdirs()
            val store = DiskStoryStore(File(dir, "q$queued"))
            val story = bigStory(chapters)
            val warmup = 10
            val measured = 40
            val jobs = (0 until chapters).map { job(story, it) }.take(maxOf(queued, warmup + measured))
            store.addOrUpdateStory(story)
            store.saveQueue(jobs)
            val repository = AppRepository(store, StandardTestDispatcher(testScheduler)).apply { refresh() }
            val queueStore = repository.downloadQueueStore()
            println(
                "COST queued=$queued storyBytes=${File(
                    dir,
                    "q$queued/stories/big.json",
                ).length()} queueBytes=${File(dir, "q$queued/download_queue.json").length()}",
            )
            val commitNanos = mutableListOf<Long>()
            val chapterNanos = mutableListOf<Long>()
            store.reset()
            for (i in 0 until warmup + measured) {
                if (i == warmup) store.reset()
                val start = System.nanoTime()
                // Process loop: cleanupUnsupportedSourceJobs reads the queue, then the claim saves it.
                synchronized(queueStore.lock) { repository.queueForWriteLocked() }
                val claimed =
                    synchronized(queueStore.lock) {
                        val current = queueStore.queue()
                        // Short queue: the next `queued` chapters, finished ones cleared.
                        val window = if (queued >= chapters) current else jobs.filter { it.chapterIndex >= i }.take(queued)
                        window
                            .map {
                                if (it.chapterIndex ==
                                    i
                                ) {
                                    it.copy(status = DownloadJobStatus.Downloading.wire)
                                } else {
                                    it
                                }
                            }.also(queueStore::saveQueue)
                    }
                repository.publishDownloadState()
                // DownloadEngine.processJob: the story's source URL.
                checkNotNull(repository.storySourceUrl("big"))
                val commitStart = System.nanoTime()
                val outcome =
                    repository.completeDownloadedChapter(
                        claimed.single {
                            it.chapterIndex == i
                        },
                        { "novels/big/$i.html" },
                        1_000L + i,
                        repository.libraryGeneration(),
                    )
                val end = System.nanoTime()
                check(outcome == AppRepository.ChapterCommit.COMMITTED) { "commit $i: $outcome" }
                if (i >= warmup) {
                    commitNanos += end - commitStart
                    chapterNanos += end - start
                }
            }
            println("COST queued=$queued commit median=${median(commitNanos)}ms per-chapter median=${median(chapterNanos)}ms")
            println("COST queued=$queued per chapter: " + store.counts(measured))
            assertEquals("no document is parsed per chapter", "queue write=2.0, story write=1.0", store.counts(measured))
        }

    private companion object {
        const val SOURCE_URL = "https://www.royalroad.com/fiction/86141/cultivation-struggles-of-a-lowly-fox"
    }

    private fun median(values: List<Long>): String = String.format(Locale.ROOT, "%.2f", values.sorted()[values.size / 2] / 1_000_000.0)

    private fun bigStory(chapters: Int) =
        Story(
            id = "big",
            title = "Cultivation Struggles of a Lowly Fox",
            author = "Author",
            sourceUrl = SOURCE_URL,
            description = "x".repeat(2_000),
            chapters =
                MutableList(chapters) { i ->
                    Chapter(
                        id = "${1_600_000 + i}",
                        title = "Chapter ${i + 1} – Even Odder Fellows and How to Power Level",
                        url = "$SOURCE_URL/chapter/${1_600_000 + i}/chapter-${i + 1}-even-odder-fellows-and-how-to-power",
                        publishedAt = 1_715_000_465_000L + i,
                    )
                },
        )

    private fun job(
        story: Story,
        index: Int,
    ) = DownloadJob(
        id = "big_$index",
        storyId = story.id,
        storyTitle = story.title,
        sourceId = "royal_road",
        chapterIndex = index,
        chapter = story.chapters[index].copy(),
        status = DownloadJobStatus.Pending.wire,
    )

    /** Mirrors AppStorage's file layout and per-call disk work (parse, pretty envelope, fsync'd atomic write). */
    private class DiskStoryStore(
        private val root: File,
    ) : RepositoryStoryStore {
        override val transactionLock = Any()
        private val gson = SharedGson.pretty
        private val index = File(root, "library_index.json")
        private val queueFile = File(root, "download_queue.json")
        private val storyDir = File(root, "stories").apply { mkdirs() }
        private val counts = linkedMapOf<String, Int>()

        fun reset() = counts.clear()

        fun counts(per: Int) = counts.entries.joinToString { (k, v) -> "$k=" + String.format(Locale.ROOT, "%.1f", v.toDouble() / per) }

        private fun count(key: String) {
            counts[key] = (counts[key] ?: 0) + 1
        }

        private fun storyFile(id: String) = File(storyDir, "$id.json")

        private fun <T> read(
            file: File,
            type: Type,
            key: String,
        ): T? {
            if (!file.exists()) return null
            count("$key parse")
            return (DurableJson.decodeTextOfType<T>(file.readText(), gson, type) as DurableReadResult.Present).value
        }

        private fun write(
            file: File,
            value: Any,
            key: String,
        ) {
            count("$key write")
            val tmp = File(file.path + ".new")
            FileOutputStream(tmp).use { out ->
                out.write(gson.toJson(DurableJson.envelope(value, "bench")).toByteArray())
                out.fd.sync()
            }
            check(tmp.renameTo(file))
        }

        private fun ids(): List<String> = read(index, object : TypeToken<List<String>>() {}.type, "index") ?: emptyList()

        private fun readStory(id: String): Story? =
            read<Story>(storyFile(id), Story::class.java, "story")?.let { StoryNormalization.coerceDefaults(it).story }

        override fun stories(): List<Story> = ids().mapNotNull(::readStory)

        override fun saveLibraryMember(story: Story) = write(storyFile(story.id), story, "story")

        override fun addOrUpdateStory(story: Story) {
            val ids = ids().toMutableList()
            if (story.id !in ids) {
                ids += story.id
                write(storyFile(story.id), story, "story")
                write(index, ids, "index")
            } else {
                story.dateAdded = readStory(story.id)?.dateAdded ?: story.dateAdded
                write(storyFile(story.id), story, "story")
            }
        }

        override fun deleteStory(id: String) = error("unused")

        override fun saveLibrary(stories: List<Story>) = error("unused")

        override fun queue(): List<DownloadJob> =
            read(queueFile, object : TypeToken<MutableList<DownloadJob>>() {}.type, "queue") ?: emptyList()

        override fun saveQueue(jobs: List<DownloadJob>) = write(queueFile, jobs, "queue")
    }
}
