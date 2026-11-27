package com.vinicius741.webnovelarchiver.data.storage

import com.vinicius741.webnovelarchiver.domain.model.Story
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Manual before/after benchmark of the durable JSON path over real library files. Skipped unless
 * `WA_JSON_BENCH_DIR` names a folder of story JSON pulled read-only from a device, e.g.
 * `adb exec-out run-as <debug package> tar -C files/webnovel_archiver -cf - stories`. Writes go to a
 * temp folder; the input files are only read. "Before" replays the old path (pretty String, then
 * bytes; whole file to String, then a StringReader parse), "after" the streaming one.
 */
class DurableJsonBenchmarkTest {
    @Test
    fun benchmarkStoryFiles() {
        val dir = System.getenv("WA_JSON_BENCH_DIR")?.let(::File)
        assumeTrue("Set WA_JSON_BENCH_DIR to run", dir != null && dir.isDirectory)
        val inputs = dir!!.listFiles { file -> file.name.endsWith(".json") }!!.sortedBy { it.name }
        val out = File(System.getProperty("java.io.tmpdir"), "wa_json_bench_${System.nanoTime()}").apply { mkdirs() }
        val plain = SharedGson.plain
        val pretty = SharedGson.pretty
        val stories = inputs.map { decodeOld(it) }
        val oldFiles = inputs.indices.map { File(out, "old_$it.json") }
        val newFiles = inputs.indices.map { File(out, "new_$it.json") }

        val phases =
            linkedMapOf<String, () -> Unit>(
                "read device files, old (bytes -> String -> StringReader)" to { inputs.forEach { decodeOld(it) } },
                "read device files, new (stream)" to { inputs.forEach { decodeNew(it) } },
                "write, old (pretty String -> bytes)" to {
                    stories.forEachIndexed { i, story ->
                        FileOutputStream(oldFiles[i]).use { stream ->
                            stream.write(pretty.toJson(DurableJson.envelope(story, VERSION)).toByteArray(Charsets.UTF_8))
                        }
                    }
                },
                "write, new (compact stream)" to {
                    stories.forEachIndexed { i, story ->
                        FileOutputStream(newFiles[i]).use { stream ->
                            DurableJson.writeEnvelope(stream, plain, DurableJson.envelope(story, VERSION))
                        }
                    }
                },
                "read rewritten files, new (compact, schema first)" to { newFiles.forEach { decodeNew(it) } },
            )
        val report = StringBuilder("files=${inputs.size}\n")
        phases.forEach { (name, run) ->
            repeat(WARMUP) { run() }
            val times = mutableListOf<Long>()
            var allocated = 0L
            repeat(ROUNDS) {
                val before = allocatedBytes()
                val start = System.nanoTime()
                run()
                times += System.nanoTime() - start
                allocated = allocatedBytes() - before
            }
            val median = times.sorted()[times.size / 2] / NANOS_PER_MILLI
            report.append("$name: median ${median}ms (min ${times.min() / NANOS_PER_MILLI}ms), alloc ${allocated / MIB} MiB\n")
        }
        report.append(
            "bytes device=${inputs.sumOf { it.length() }} oldWriter=${oldFiles.sumOf { it.length() }} newWriter=${newFiles.sumOf {
                it
                    .length()
            }}\n",
        )
        newFiles.forEachIndexed { i, file -> assertEquals(stories[i], decodeNew(file)) }
        println(report)
        System.getenv("WA_JSON_BENCH_REPORT")?.let { File(it).writeText(report.toString()) }
        out.deleteRecursively()
    }

    /** HotSpot's per-thread allocation counter, via reflection: unit tests compile against android.jar. */
    private fun allocatedBytes(): Long {
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val method = Class.forName("com.sun.management.ThreadMXBean").getMethod("getCurrentThreadAllocatedBytes")
        return method.invoke(bean) as Long
    }

    private fun decodeOld(file: File): Story {
        val text = String(file.readBytes(), Charsets.UTF_8)
        return (DurableJson.decodeTextOfType<Story>(text, SharedGson.plain, Story::class.java) as DurableReadResult.Present).value
    }

    private fun decodeNew(file: File): Story =
        (DurableJson.decodeStreamOfType<Story>(SharedGson.plain, Story::class.java) { FileInputStream(file) } as DurableReadResult.Present)
            .value

    private companion object {
        const val VERSION = "1.2.12-native"
        const val WARMUP = 3
        const val ROUNDS = 7
        const val NANOS_PER_MILLI = 1_000_000L
        const val MIB = 1024L * 1024L
    }
}
