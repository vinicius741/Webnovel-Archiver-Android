package com.vinicius741.webnovelarchiver.data.backup

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.vinicius741.webnovelarchiver.domain.model.AppSettings
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.DisplayPreferences
import com.vinicius741.webnovelarchiver.domain.model.SourceMetadata
import com.vinicius741.webnovelarchiver.domain.model.SourceMetric
import com.vinicius741.webnovelarchiver.domain.model.SourceMetricKind
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.Tab
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.system.exitProcess

/**
 * Opt-in size, time and heap benchmark for the full-backup manifest. Run with
 * `WA_MANIFEST_BENCH=1` (and optionally `WA_MANIFEST_BENCH_STORIES=<a stories directory of story JSON envelopes>` to use a
 * real library pulled read-only instead of the generated 305-story / 33,000-chapter one):
 *
 * `WA_MANIFEST_BENCH=1 android/gradlew -p android :app:testInstrumentationUnitTest --tests "*FullBackupManifestBenchmarkTest" --rerun`
 *
 * Results go to `app/build/manifest-bench.txt`. "Legacy" replays the former restore path exactly:
 * read the manifest into a String, parse it into `Map<String, Any>`, then re-serialize `library` and
 * `config` with the pretty storage Gson and parse them again typed. Peak heap is the smallest
 * `-Xmx` (4 MB steps, serial GC) at which a fresh JVM completes the parse.
 */
class FullBackupManifestBenchmarkTest {
    @Test
    fun manifestSizeTimeAndHeap() {
        assumeTrue("set WA_MANIFEST_BENCH=1 to run", System.getenv("WA_MANIFEST_BENCH") != null)
        val dir = File("build/manifest-bench").apply { mkdirs() }
        val library = System.getenv("WA_MANIFEST_BENCH_STORIES")?.let(::loadStories) ?: generatedLibrary()
        val plan = plan(library)
        val v1 = File(dir, "v1.json").apply { writeBytes(legacyManifest(library, plan)) }
        val v2 = File(dir, "v2.json").apply { outputStream().use { FullBackupManifestWriter.write(it, content(library, plan)) } }
        val report = StringBuilder()
        val chapters = library.sumOf { it.chapters.size }
        report.appendLine("library: ${library.size} stories, $chapters chapters, ${plan.size} downloaded")
        report.appendLine("manifest v1 (pretty, chapterFiles): ${v1.length()} B raw, ${deflated(v1.readBytes())} B deflated")
        report.appendLine("manifest v2 (compact, derived):     ${v2.length()} B raw, ${deflated(v2.readBytes())} B deflated")
        report.appendLine(
            "export write v1: ${measure { legacyManifest(library, plan).size }}",
        )
        report.appendLine(
            "export write v2: ${measure {
                CountingStream()
                    .also {
                        FullBackupManifestWriter.write(
                            it,
                            content(library, plan),
                        )
                    }.count
                    .toInt()
            }}",
        )
        val parses = listOf("legacy-v1" to v1, "stream-v1" to v1, "stream-v2" to v2)
        parses.forEach { (mode, file) ->
            assertEquals(plan.size, ManifestBenchChild.parse(mode, file))
            report.appendLine(
                "restore parse $mode: ${measure { ManifestBenchChild.parse(mode, file) }}, min -Xmx ${minHeapMb(mode, file)} MB",
            )
        }
        File("build/manifest-bench.txt").writeText(report.toString())
        println(report)
    }

    private fun content(
        library: List<Story>,
        plan: List<FullBackupContentPlanning.ChapterContent>,
    ) = FullBackupManifestContent(
        exportDate = "2026-11-27T00:00:00Z",
        library = library,
        config = config(),
        metricFiles = library.map { RestoredMetricFileIndex(it.id, FullBackupPaths.metricPath(it.id)) },
        coverFiles = emptyList(),
        missingContent = FullBackupContentPlanning.missingContentReport(plan),
    )

    /** The former exporter's manifest: pretty storage Gson over a Map with a full chapterFiles list. */
    private fun legacyManifest(
        library: List<Story>,
        plan: List<FullBackupContentPlanning.ChapterContent>,
    ): ByteArray =
        PRETTY
            .toJson(
                mapOf(
                    "format" to "webnovel-archiver-full-backup",
                    "version" to 1,
                    "exportDate" to "2026-11-27T00:00:00Z",
                    "library" to library,
                    "config" to config(),
                    "chapterFiles" to
                        plan.filterIsInstance<FullBackupContentPlanning.ChapterContent.FromFile>().map {
                            mapOf(
                                "storyId" to it.storyId,
                                "chapterId" to it.chapterId,
                                "chapterIndex" to it.chapterIndex,
                                "title" to it.title,
                                "path" to it.path,
                            )
                        },
                    "metricFiles" to library.map { mapOf("storyId" to it.id, "path" to FullBackupPaths.metricPath(it.id)) },
                    "coverFiles" to emptyList<Any>(),
                    "missingContent" to emptyList<Any>(),
                ),
            ).toByteArray()

    private fun plan(library: List<Story>) =
        FullBackupContentPlanning.planChapterContent(library, { File("x") }, FullBackupPaths::chapterPath)

    private fun config(): Map<String, Any?> =
        mapOf(
            "settings" to AppSettings(),
            "displayPreferences" to DisplayPreferences(),
            "tabs" to (1..8).map { Tab(id = "tab_$it", name = "Tab $it", order = it, createdAt = 1_780_000_000_000L + it) },
            "sentenceRemovalList" to (1..40).map { "Sentence to remove number $it from chapters" },
            "ttsSettings" to TtsSettings(),
        )

    private fun deflated(bytes: ByteArray): Long {
        val counter = CountingStream()
        ZipOutputStream(counter).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(bytes)
            zip.closeEntry()
        }
        return counter.count
    }

    /** Median wall time and thread-allocated bytes over 5 runs after 2 warm-ups. */
    private fun measure(block: () -> Int): String {
        repeat(2) { block() }
        val runs =
            (1..5).map {
                val allocatedBefore = threadAllocatedBytes()
                val start = System.nanoTime()
                block()
                (System.nanoTime() - start) / 1_000_000 to threadAllocatedBytes() - allocatedBefore
            }
        val millis = runs.map { it.first }.sorted()[2]
        val allocated = runs.map { it.second }.sorted()[2]
        return "$millis ms, ${allocated / (1024 * 1024)} MB allocated"
    }

    /**
     * `com.sun.management.ThreadMXBean.getCurrentThreadAllocatedBytes`, by reflection: unit tests
     * compile against android.jar, which has no `java.lang.management`.
     */
    private fun threadAllocatedBytes(): Long {
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        return Class.forName("com.sun.management.ThreadMXBean").getMethod("getCurrentThreadAllocatedBytes").invoke(bean) as Long
    }

    /** Smallest -Xmx (MB, 4 MB resolution) at which a fresh serial-GC JVM finishes [mode] on [file]. */
    private fun minHeapMb(
        mode: String,
        file: File,
    ): Int {
        var low = 4
        var high = 1024
        check(runsWithin(high, mode, file)) { "$mode does not finish within $high MB" }
        while (high - low > 4) {
            val mid = (low + high) / 2
            if (runsWithin(mid, mode, file)) high = mid else low = mid
        }
        return high
    }

    private fun runsWithin(
        heapMb: Int,
        mode: String,
        file: File,
    ): Boolean {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process =
            ProcessBuilder(
                java,
                "-Xmx${heapMb}m",
                "-XX:+UseSerialGC",
                "-cp",
                System.getProperty("java.class.path"),
                ManifestBenchChild::class.java.name,
                mode,
                file.absolutePath,
            ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null"))).start()
        check(process.waitFor(5, TimeUnit.MINUTES)) { "bench child timed out" }
        return process.exitValue() == 0
    }

    private fun loadStories(directory: String): List<Story> =
        File(directory)
            .listFiles { file -> file.name.endsWith(".json") }
            .orEmpty()
            .sortedBy { it.name }
            .map { file ->
                val payload = Gson().fromJson(file.readText(), JsonObject::class.java).getAsJsonObject("payload")
                Gson().fromJson(payload, Story::class.java).apply {
                    chapters.forEach {
                        it.filePath = null
                        it.content = null
                    }
                    epubPath = null
                    epubPaths = null
                }
            }

    /**
     * 305 stories and exactly 33,000 chapters (74% downloaded), shaped like the owner's library:
     * Royal Road-style ids and URLs, ~26-character titles, two timestamps per chapter, and about
     * 2.5 KB of story metadata.
     */
    private fun generatedLibrary(): List<Story> {
        val counts = (0 until STORIES).map { 108 + (it * 37) % 61 - 30 }.toMutableList()
        counts[counts.lastIndex] += CHAPTERS - counts.sum()
        return counts.mapIndexed { storyIndex, count ->
            val fiction = 40_000 + storyIndex * 97
            Story(
                id = "rr_$fiction",
                title = "Generated Story Number $storyIndex: A Tale",
                author = "Author $storyIndex",
                coverUrl = "https://www.royalroadcdn.com/public/covers-large/$fiction-generated-story.jpg?time=1700000000",
                description = "A long synopsis sentence that keeps going for a while. ".repeat(28),
                sourceUrl = "https://www.royalroad.com/fiction/$fiction/generated-story-number-$storyIndex",
                sourceId = "royal_road",
                tags = mutableListOf("Fantasy", "Action", "Adventure", "Progression", "Magic", "LitRPG"),
                score = "4.6",
                tabId = "tab_1",
                patreonUrl = "https://www.patreon.com/author$storyIndex",
                lastUpdated = 1_795_000_000_000L,
                dateAdded = 1_780_000_000_000L,
                sourceMetadata =
                    SourceMetadata(
                        metrics =
                            SourceMetricKind.entries
                                .take(5)
                                .map { SourceMetric(it, 120_000L, false) }
                                .toMutableList(),
                        createdAt = 1_700_000_000_000L,
                    ),
                chapters =
                    (0 until count)
                        .map { chapterIndex ->
                            val chapterId = 1_000_000 + storyIndex * 1_000 + chapterIndex
                            Chapter(
                                id = "$chapterId",
                                title = "Chapter ${chapterIndex + 1}: Into the Dark",
                                url = "$ROYAL_ROAD/$fiction/generated-story/chapter/$chapterId/chapter-${chapterIndex + 1}",
                                downloaded = chapterIndex < count * 74 / 100,
                                downloadedAt = if (chapterIndex < count * 74 / 100) 1_790_000_000_000L + chapterIndex else null,
                                publishedAt = 1_700_000_000_000L + chapterIndex * 86_400_000L,
                            )
                        }.toMutableList(),
            )
        }
    }

    private class CountingStream : OutputStream() {
        var count = 0L

        override fun write(b: Int) {
            count += 1
        }

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            count += len
        }
    }

    private companion object {
        const val STORIES = 305
        const val CHAPTERS = 33_000
        const val ROYAL_ROAD = "https://www.royalroad.com/fiction"
        val PRETTY: Gson = GsonBuilder().setPrettyPrinting().create()
    }
}

/** Entry point of the heap-bisection child JVM; also the in-process parse under test. */
object ManifestBenchChild {
    private val pretty: Gson = GsonBuilder().setPrettyPrinting().create()

    @JvmStatic
    fun main(args: Array<String>) {
        val chapters =
            try {
                parse(args[0], File(args[1]))
            } catch (_: OutOfMemoryError) {
                exitProcess(3)
            }
        exitProcess(if (chapters > 0) 0 else 4)
    }

    /** Runs one restore-side manifest parse and returns the chapter-file count it produced. */
    fun parse(
        mode: String,
        file: File,
    ): Int =
        when (mode) {
            "legacy-v1" -> legacyParse(file)
            else ->
                file.inputStream().use { input ->
                    FullBackupManifestValidation
                        .parseValidated(FullBackupManifestReader.read(pretty, input, BackupInputLimits.MAX_MANIFEST_BYTES))
                        .chapterFiles.size
                }
        }

    /** The former `readAndValidateManifest` + `parseValidated` data path, step for step. */
    private fun legacyParse(file: File): Int {
        val text = file.inputStream().use { BackupInputLimits.readUtf8(it, BackupInputLimits.MAX_MANIFEST_BYTES, "Full-backup manifest") }
        val manifest: Map<String, Any> = pretty.fromJson(text, object : TypeToken<Map<String, Any>>() {}.type)
        val chapterEntries = (manifest["chapterFiles"] as List<*>).map { it as Map<*, *> }
        check(chapterEntries.map { it["path"] as String }.distinct().size == chapterEntries.size)
        check(chapterEntries.map { Pair(it["storyId"] as String, it["chapterId"] as String) }.distinct().size == chapterEntries.size)
        val stories: List<Story> = pretty.fromJson(pretty.toJson(manifest["library"]), object : TypeToken<List<Story>>() {}.type)
        val config: Map<String, Any> = pretty.fromJson(pretty.toJson(manifest["config"]), object : TypeToken<Map<String, Any>>() {}.type)
        val chapterFiles =
            chapterEntries.map { RestoredChapterFileIndex(it["storyId"] as String, it["chapterId"] as String, it["path"] as String) }
        check(stories.isNotEmpty() && config.isNotEmpty())
        return chapterFiles.size
    }
}
