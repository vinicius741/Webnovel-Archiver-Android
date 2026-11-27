package com.vinicius741.webnovelarchiver.data.repository

import com.google.gson.JsonParser
import com.vinicius741.webnovelarchiver.data.storage.SharedGson
import com.vinicius741.webnovelarchiver.domain.metrics.PatreonCreatorPlanning
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.StoryNormalization
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Opt-in measurement of the defensive copies behind [AppRepository.library]/[AppRepository.story]
 * against the read-only paths, over a real library. Pull one read-only from the emulator
 * (`run-as … tar -C files/webnovel_archiver -cf - stories`), point `WA_BENCH_LIBRARY_DIR` at the
 * `stories` folder and run this class; it writes its table to `WA_BENCH_OUT` (default
 * `build/story-copy-bench.txt`). Skipped without the variable, so CI never runs it.
 */
class StoryCopyBenchmarkTest {
    @Test
    fun copyingReadsAgainstReadOnlyReads() {
        val dir = System.getenv(LIBRARY_ENV)?.let(::File)
        assumeTrue("Set $LIBRARY_ENV to a pulled stories folder", dir?.isDirectory == true)
        val stories = loadStories(dir!!)
        val repository = AppRepository(Store(), kotlinx.coroutines.Dispatchers.Unconfined)
        runBlocking { repository.saveLibrary(stories) }
        val rows =
            scenarios(repository, stories).flatMap {
                listOf(time("${it.name}: copy (main)", it.before), time("${it.name}: read-only (this PR)", it.after))
            }
        val chapters = stories.sumOf { it.chapters.size }
        val report =
            buildString {
                appendLine("Library: ${stories.size} stories, $chapters chapters (largest ${stories.maxOf { it.chapters.size }})")
                appendLine("JVM ${System.getProperty("java.version")}, $ITERATIONS iterations after $WARMUP warm-up")
                appendLine(String.format(Locale.ROOT, "%-58s %12s %12s", "scenario", "µs/op", "KiB/op"))
                rows.forEach { appendLine(String.format(Locale.ROOT, "%-58s %12.1f %12.1f", it.name, it.micros, it.kib)) }
            }
        println(report)
        File(System.getenv(OUT_ENV) ?: "build/story-copy-bench.txt").apply { parentFile?.mkdirs() }.writeText(report)
    }

    private class Scenario(
        val name: String,
        val before: () -> Any?,
        val after: () -> Any?,
    )

    private class Row(
        val name: String,
        val micros: Double,
        val kib: Double,
    )

    private fun scenarios(
        repository: AppRepository,
        stories: List<Story>,
    ): List<Scenario> {
        val largest = stories.maxBy { it.chapters.size }
        val listened = closest(stories, LISTENED_CHAPTERS)
        val second = closest(stories.filter { it.id != listened.id }, SECOND_CHAPTERS)
        // The emulator's narration packs: 24 on a 217-chapter story, 6 on a 92-chapter one, 25 of a deleted story.
        val packs =
            listened.chapters.take(LISTENED_PACKS).map { listened.id to it.id } +
                second.chapters.take(SECOND_PACKS).map { second.id to it.id } +
                (1..DETACHED_PACKS).map { "deleted" to "chapter$it" }
        val queued = listened.chapters.takeLast(QUEUED_JOBS).map { listened.id to it.id }
        val tabId = stories.firstNotNullOfOrNull { it.tabId }
        return listOf(
            Scenario(
                "Settings hub phone summary",
                { repository.library().let { it.size to it.sumOf { s -> s.downloadedChapters } } },
                { repository.readOnlyLibrary().let { it.size to it.sumOf { s -> s.downloadedChapters } } },
            ),
            Scenario(
                "Manage tabs novel counts",
                { tabCounts(repository.library()) },
                { tabCounts(repository.readOnlyLibrary()) },
            ),
            Scenario(
                "Manage tabs: copies to unassign one tab",
                { repository.library().filter { tabId != null && it.tabId == tabId } },
                { repository.readOnlyLibrary().filter { tabId != null && it.tabId == tabId }.mapNotNull { repository.story(it.id) } },
            ),
            Scenario(
                "Trends Patreon siblings",
                { PatreonCreatorPlanning.siblings(largest, repository.library()) },
                { PatreonCreatorPlanning.siblings(largest, repository.readOnlyLibrary()) },
            ),
            Scenario(
                "Listen: detachedPacks (${packs.size} packs)",
                { packs.filter { (s, c) -> repository.story(s)?.chapters?.any { it.id == c } != true } },
                { packs.filterNot { (s, c) -> repository.hasChapter(s, c) } },
            ),
            Scenario(
                "Listen: queue chapter titles ($QUEUED_JOBS jobs)",
                {
                    queued.map { (s, c) ->
                        repository
                            .story(s)
                            ?.chapters
                            ?.firstOrNull { it.id == c }
                            ?.title
                    }
                },
                { queued.map { (s, c) -> repository.chapterTitle(s, c) } },
            ),
            Scenario(
                "AI chip / notification title",
                { repository.story(listened.id)?.title },
                { repository.storyTitle(listened.id) },
            ),
            Scenario(
                "story(id), largest story (${largest.chapters.size} ch)",
                { repository.story(largest.id) },
                { repository.readOnlyStory(largest.id) },
            ),
        )
    }

    private fun tabCounts(library: List<Story>): Map<String, Int> = library.mapNotNull { it.tabId }.groupingBy { it }.eachCount()

    private fun closest(
        stories: List<Story>,
        chapters: Int,
    ): Story = stories.minBy { abs(it.chapters.size - chapters) }

    private fun time(
        name: String,
        block: () -> Any?,
    ): Row {
        repeat(WARMUP) { sink = block() }
        val allocatedBefore = allocatedBytes()
        val startedAt = System.nanoTime()
        repeat(ITERATIONS) { sink = block() }
        val nanos = System.nanoTime() - startedAt
        val bytes = allocatedBytes() - allocatedBefore
        return Row(name, nanos / NANOS_PER_MICRO / ITERATIONS, bytes / BYTES_PER_KIB / ITERATIONS)
    }

    /**
     * This thread's allocated bytes, from the host JVM's `com.sun.management.ThreadMXBean`. Unit tests
     * compile against android.jar, which lacks `java.lang.management`, so it is reached reflectively.
     */
    private fun allocatedBytes(): Long {
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val method = Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        return method.invoke(bean, Thread.currentThread().id) as Long
    }

    private fun loadStories(dir: File): List<Story> =
        dir
            .listFiles { file -> file.extension == "json" }
            .orEmpty()
            .sortedBy { it.name }
            .map { file ->
                val payload = JsonParser.parseString(file.readText()).asJsonObject.get("payload")
                StoryNormalization.coerceDefaults(SharedGson.plain.fromJson(payload, Story::class.java)).story
            }

    private class Store : RepositoryStoryStore {
        override val transactionLock = Any()
        private val stories = linkedMapOf<String, Story>()

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

        override fun queue(): List<DownloadJob> = emptyList()

        override fun saveQueue(jobs: List<DownloadJob>) = Unit
    }

    private companion object {
        const val LIBRARY_ENV = "WA_BENCH_LIBRARY_DIR"
        const val OUT_ENV = "WA_BENCH_OUT"
        const val WARMUP = 200
        const val ITERATIONS = 1_000
        const val LISTENED_CHAPTERS = 217
        const val SECOND_CHAPTERS = 92
        const val LISTENED_PACKS = 24
        const val SECOND_PACKS = 6
        const val DETACHED_PACKS = 25
        const val QUEUED_JOBS = 20
        const val NANOS_PER_MICRO = 1_000.0
        const val BYTES_PER_KIB = 1_024.0

        @Volatile
        var sink: Any? = null
    }
}
