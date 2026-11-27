package com.vinicius741.webnovelarchiver.data.storage

import com.vinicius741.webnovelarchiver.data.backup.ChapterPack
import com.vinicius741.webnovelarchiver.data.backup.FullBackupPaths
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipOutputStream

class FullBackupZipExtractorTest {
    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = File(System.getProperty("java.io.tmpdir"), "backup_extract_${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun extractsOnlyAllowlistedManifestAndChapterFiles() {
        val zip = zipOf("manifest.json" to "{}", "novels/story/chapter.html" to "chapter")
        val output = File(directory, "output")

        val result = FullBackupZipExtractor.extract(zip, output, Long.MAX_VALUE)

        assertEquals(setOf("manifest.json", "novels/story/chapter.html"), result.files)
        assertEquals("chapter", File(output, "novels/story/chapter.html").readText())
    }

    @Test
    fun rejectsUnindexedFormatEntriesBeforeWritingThem() {
        val zip = zipOf("manifest.json" to "{}", "settings.json" to "secret")
        val output = File(directory, "output")

        assertThrows(IllegalStateException::class.java) {
            FullBackupZipExtractor.extract(zip, output, Long.MAX_VALUE)
        }

        assertEquals(false, File(output, "settings.json").exists())
    }

    @Test
    fun extractsMetricHistoryFilesAlongsideChapters() {
        val zip =
            zipOf(
                "manifest.json" to "{}",
                "novels/story/chapter.html" to "chapter",
                "metrics/story.json" to """{"snapshots":[]}""",
            )
        val output = File(directory, "output")

        val result = FullBackupZipExtractor.extract(zip, output, Long.MAX_VALUE)

        assertEquals(
            setOf("manifest.json", "novels/story/chapter.html", "metrics/story.json"),
            result.files,
        )
        assertEquals("""{"snapshots":[]}""", File(output, "metrics/story.json").readText())
    }

    @Test
    fun expandsChapterPacksIntoTheirStoryDirectory() {
        val zip =
            zipOf(
                "manifest.json" to "{}",
                bytes = listOf(FullBackupPaths.chapterPackPath("rr 1") to pack("0000_a.html" to "one", "0001_b.html" to "two")),
            )
        val output = File(directory, "output")

        val result = FullBackupZipExtractor.extract(zip, output, Long.MAX_VALUE)

        assertEquals(
            setOf("manifest.json", "chapter_packs/rr%201.zst", "novels/rr%201/0000_a.html", "novels/rr%201/0001_b.html"),
            result.files,
        )
        assertEquals("two", File(output, "novels/rr%201/0001_b.html").readText())
        assertEquals(false, File(output, "chapter_packs").exists())
    }

    @Test
    fun exportedPacksRoundTripThroughTheExtractor() {
        val source = File(directory, "chapter.html").apply { writeText("<p>from disk</p>") }
        val chapters =
            listOf(
                chapterFile("story", "c1", 0, source = source),
                chapterFile("story", "c2", 1, inline = "<p>inline</p>"),
                chapterFile("other", "c1", 0, inline = "<p>other</p>"),
            )
        val packs = ChapterPackExport.plan(chapters)
        val zip = File(directory, "export.zip")
        var written = 0
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("manifest.json"))
            out.write("{}".toByteArray())
            out.closeEntry()
            ChapterPackExport.write(out, packs, chapters) { written += 1 }
            out.putNextEntry(ZipEntry("metrics/story.json"))
            out.write("{}".toByteArray())
            out.closeEntry()
        }
        val output = File(directory, "output")

        val result = FullBackupZipExtractor.extract(zip, output, Long.MAX_VALUE)

        assertEquals(listOf("chapter_packs/story.zst", "chapter_packs/other.zst"), packs.map { it.path })
        assertEquals(3, written)
        assertEquals(setOf("manifest.json", "metrics/story.json") + packs.map { it.path } + chapters.map { it.path }, result.files)
        chapters.forEach { chapter ->
            assertEquals(chapter.inlineContent ?: source.readText(), File(output, chapter.path).readText())
        }
    }

    @Test
    fun packedChaptersMeetTheSameLimitsAsLooseEntries() {
        val packPath = FullBackupPaths.chapterPackPath("story")

        fun extract(vararg entries: Pair<String, ByteArray>) =
            FullBackupZipExtractor.extract(
                zipOf("manifest.json" to "{}", bytes = entries.toList()),
                File(directory, "out_${System.nanoTime()}"),
                Long.MAX_VALUE,
                budgetBytes = 100_000,
            )

        // Only chapter HTML may come out of a pack.
        val notHtml = assertThrows(IllegalStateException::class.java) { extract(packPath to pack("settings.json" to "{}")) }
        assertEquals("Invalid full backup: unsupported ZIP entry novels/story/settings.json", notHtml.message)
        // A packed chapter may not shadow a loose one.
        val duplicate =
            assertThrows(IllegalStateException::class.java) {
                extract("novels/story/0000_a.html" to "x".toByteArray(), packPath to pack("0000_a.html" to "y"))
            }
        assertEquals("Invalid full backup: duplicate ZIP entry novels/story/0000_a.html", duplicate.message)
        // Decompressed bytes count against the budget: 200 KB of zeros packs to a few hundred bytes.
        val bomb = assertThrows(IllegalStateException::class.java) { extract(packPath to pack("0000_a.html" to "\u0000".repeat(200_000))) }
        assertEquals("Backup uncompressed size exceeds available-space limit", bomb.message)
        // A corrupt pack is reported as a corrupt backup.
        assertThrows(ZipException::class.java) { extract(packPath to "garbage".toByteArray()) }
        // A pack outside chapter_packs/<name>.zst is not a pack.
        assertThrows(IllegalStateException::class.java) { extract("chapter_packs/a/b.zst" to pack("0000_a.html" to "x")) }
    }

    private fun pack(vararg files: Pair<String, String>): ByteArray =
        ByteArrayOutputStream()
            .also { out -> ChapterPack.Writer(out).use { w -> files.forEach { (n, t) -> w.add(n, t.toByteArray()) } } }
            .toByteArray()

    private fun chapterFile(
        storyId: String,
        chapterId: String,
        index: Int,
        source: File? = null,
        inline: String? = null,
    ) = FullBackupChapterFile(
        storyId = storyId,
        chapterId = chapterId,
        chapterIndex = index,
        title = chapterId,
        path = FullBackupPaths.chapterPath(storyId, chapterId, index),
        source = source,
        inlineContent = inline,
    )

    private fun zipOf(
        vararg entries: Pair<String, String>,
        bytes: List<Pair<String, ByteArray>>,
    ): File =
        File(directory, "backup_${System.nanoTime()}.zip").also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                (entries.map { it.first to it.second.toByteArray() } + bytes).forEach { (name, contents) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(contents)
                    zip.closeEntry()
                }
            }
        }

    private fun zipOf(vararg entries: Pair<String, String>): File =
        File(directory, "backup_${System.nanoTime()}.zip").also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                entries.forEach { (name, contents) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(contents.toByteArray())
                    zip.closeEntry()
                }
            }
        }
}
