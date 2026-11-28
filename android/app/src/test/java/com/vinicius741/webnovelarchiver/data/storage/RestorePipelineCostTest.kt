package com.vinicius741.webnovelarchiver.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Measures what a full restore costs on disk: wall time, bytes written, and the peak extra disk
 * held beside the live library before the commit. It compares the production pipeline
 * (extract once, then rename the trees into the staged root and beside the live root) with a
 * replica of the previous pipeline (extract, then `copyRecursively` into the staged root, then
 * `copyRecursively` again beside the live root).
 *
 * The small run is a regression test. Set `RESTORE_BENCH_MB` (and optionally `RESTORE_BENCH_DIR`, a
 * disk-backed directory) to measure a library-sized backup; results print to the test's stdout.
 */
class RestorePipelineCostTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private data class Cost(
        val millis: Long,
        val bytesWritten: Long?,
        val peakExtraBytes: Long,
    )

    private class Layout(
        base: File,
    ) {
        val filesDir = File(base, "files")
        val live = File(filesDir, "webnovel_archiver")
        val snapshot = File(filesDir, "webnovel_restore_snapshot")
        val journal = RestoreTransactionJournal(File(filesDir, RestoreTransactionJournal.FILE_NAME))
        val work = File(base, "cache/webnovel_restore/1")
        val raw = File(work, "raw_extraction")
        val staged = File(work, "staged_root")

        /** Restore data on disk that is not the live library: extraction, staged root, swap candidates. */
        fun extraBytes(): Long =
            bytes(work) +
                filesDir
                    .listFiles { file -> file.name.startsWith(RestoreStartupRecovery.SWAP_CANDIDATE_PREFIX) }
                    .orEmpty()
                    .sumOf(::bytes)

        fun committer() = RestoreRootCommitter(live, snapshot, journal, { live.mkdirs() })
    }

    @Test
    fun restoreWritesTheBackupDataOnce() {
        val zip = syntheticBackup(tmp.newFolder("input"), totalBytes = 8L * MB, seed = 1)
        val dataBytes = uncompressedBytes(zip)

        val current = measure(Layout(tmp.newFolder("current")), zip, legacy = false)
        val legacy = measure(Layout(tmp.newFolder("legacy")), zip, legacy = true)

        // The extraction is the only copy: the peak is one copy, where the old pipeline held three.
        assertTrue("current peak ${current.peakExtraBytes} vs data $dataBytes", current.peakExtraBytes <= dataBytes)
        assertTrue("legacy peak ${legacy.peakExtraBytes} vs data $dataBytes", legacy.peakExtraBytes >= 3L * dataBytes - 3L * MB)
        current.bytesWritten?.let { written -> assertTrue("current wrote $written for $dataBytes", written < dataBytes + MB) }
        legacy.bytesWritten?.let { written -> assertTrue("legacy wrote $written for $dataBytes", written >= 3L * dataBytes) }
    }

    @Test
    fun largeLibraryBenchmark() {
        val megabytes = System.getenv("RESTORE_BENCH_MB")?.toLongOrNull()
        assumeTrue("set RESTORE_BENCH_MB to run", megabytes != null)
        val base =
            System.getenv("RESTORE_BENCH_DIR")?.let { File(it, "run_${System.nanoTime()}").apply { mkdirs() } } ?: tmp.newFolder("bench")
        try {
            val zip = syntheticBackup(File(base, "input").apply { mkdirs() }, totalBytes = checkNotNull(megabytes) * MB, seed = 2)
            val dataBytes = uncompressedBytes(zip)
            println("restore-bench: data=${dataBytes / MB} MB zip=${zip.length() / MB} MB files=${entryCount(zip)}")
            // Each pipeline restores over the same live library, one after the other, cleaned between.
            for (legacy in listOf(true, false, true, false)) {
                val layout = Layout(File(base, if (legacy) "legacy" else "current"))
                val cost = measure(layout, zip, legacy)
                println(
                    "restore-bench: ${if (legacy) "legacy " else "current"} time=${cost.millis} ms " +
                        "written=${cost.bytesWritten?.let { it / MB }} MB peakExtra=${cost.peakExtraBytes / MB} MB",
                )
                layout.filesDir.parentFile.deleteRecursively()
            }
        } finally {
            base.deleteRecursively()
        }
    }

    private fun measure(
        layout: Layout,
        zip: File,
        legacy: Boolean,
    ): Cost {
        File(layout.live, "stories/old.json").apply { parentFile?.mkdirs() }.writeText("old")
        layout.work.mkdirs()
        var peak = 0L
        val sample = { peak = maxOf(peak, layout.extraBytes()) }
        val writtenBefore = processWrittenBytes()
        val started = System.nanoTime()

        FullBackupZipExtractor.extract(zip, layout.raw, layout.work.usableSpace)
        sample()
        layout.staged.mkdirs()
        if (legacy) {
            // Replica of the previous FullBackupRestorer.buildStagedRoot copies.
            listOf("novels", "metrics", "covers").forEach { name ->
                File(layout.raw, name).takeIf(File::exists)?.copyRecursively(File(layout.staged, name).apply { mkdirs() }, overwrite = true)
            }
        } else {
            RestoreTreeMove().adoptExtractedTrees(layout.raw, layout.staged)
        }
        sample()
        val committer = layout.committer()
        val candidate =
            if (legacy) {
                // Replica of the previous RestoreRootCommitter.copyVerified swap-candidate copy.
                File(layout.filesDir, "${RestoreStartupRecovery.SWAP_CANDIDATE_PREFIX}1").also { target ->
                    check(layout.staged.copyRecursively(target, overwrite = true))
                    check(bytes(target) == bytes(layout.staged))
                }
            } else {
                committer.stageBesideLiveRoot(layout.staged)
            }
        sample()
        committer.commit(candidate)
        layout.work.deleteRecursively()

        val millis = (System.nanoTime() - started) / 1_000_000L
        val written = processWrittenBytes()?.let { after -> writtenBefore?.let { after - it } }
        assertEquals("restored chapters", entryCount(zip) - 1, File(layout.live, "novels").walkTopDown().count(File::isFile))
        return Cost(millis, written, peak)
    }

    /** Chapter-like HTML in story folders, sized like the owner's library (~29 KB per chapter). */
    private fun syntheticBackup(
        directory: File,
        totalBytes: Long,
        seed: Long,
    ): File {
        val random = Random(seed)
        val words = List(WORDS) { (1..(3 + random.nextInt(7))).map { 'a' + random.nextInt(26) }.joinToString("") }
        val zip = File(directory, "backup.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            out.putNextEntry(ZipEntry("manifest.json"))
            out.write("{}".toByteArray())
            out.closeEntry()
            var written = 0L
            var index = 0
            while (written < totalBytes) {
                val text = StringBuilder("<p>")
                val target = CHAPTER_BYTES / 2 + random.nextInt(CHAPTER_BYTES)
                while (text.length <
                    target
                ) {
                    text.append(words[random.nextInt(WORDS)]).append(if (random.nextInt(12) == 0) ".</p><p>" else " ")
                }
                val bytes = text.append("</p>").toString().toByteArray()
                out.putNextEntry(ZipEntry("novels/story_${index / CHAPTERS_PER_STORY}/$index.html"))
                out.write(bytes)
                out.closeEntry()
                written += bytes.size
                index += 1
            }
        }
        return zip
    }

    private fun uncompressedBytes(zip: File): Long = ZipFile(zip).use { file -> file.entries().asSequence().sumOf { it.size } }

    private fun entryCount(zip: File): Int = ZipFile(zip).use { it.size() }

    /** Bytes this process passed to write(2) (Linux `/proc/self/io` wchar); null elsewhere. */
    private fun processWrittenBytes(): Long? =
        runCatching {
            File("/proc/self/io")
                .readLines()
                .first { it.startsWith("wchar:") }
                .substringAfter(':')
                .trim()
                .toLong()
        }.getOrNull()

    private companion object {
        const val MB = 1024L * 1024L
        const val WORDS = 4_000
        const val CHAPTER_BYTES = 29_000
        const val CHAPTERS_PER_STORY = 80

        fun bytes(file: File): Long = file.walkTopDown().filter(File::isFile).sumOf(File::length)
    }
}
