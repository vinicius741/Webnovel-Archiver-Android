package com.vinicius741.webnovelarchiver.data.storage

import com.vinicius741.webnovelarchiver.cleanup.ChapterMarkupCompactor
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class StoredChapterCompactionTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val dropNoise = ChapterMarkupCompactor { html -> if ("NOISE" in html) html.replace("NOISE", "") else html }

    private fun chapterFile(
        name: String,
        text: String,
    ): Chapter {
        File(temp.root, name).writeText(text)
        return Chapter(id = name, filePath = name, downloaded = true)
    }

    private fun story(
        id: String,
        vararg chapters: Chapter,
    ) = Story(id = id, chapters = chapters.toMutableList())

    private fun compaction(
        stories: List<Story>,
        compactFile: (Chapter, ChapterMarkupCompactor) -> Long = { chapter, compactor ->
            ChapterMarkupCompaction.compactFile(File(temp.root, chapter.filePath!!), compactor)
        },
        generation: () -> Long = { 1L },
    ) = StoredChapterCompaction(
        root = temp.root,
        lock = this,
        compactFile = compactFile,
        stories = { stories },
        compactorFor = { story -> dropNoise.takeIf { story.id.startsWith("rr") } },
        libraryGeneration = generation,
    )

    private fun read(name: String) = File(temp.root, name).readText()

    @Test
    fun compactsOnlyStoriesWithACompactorAndThenNeverRunsAgain() =
        runBlocking {
            val stories =
                listOf(
                    story("rr_1", chapterFile("a.html", "<p>NOISEone</p>"), chapterFile("b.html", "<p>two</p>")),
                    story("ffn_1", chapterFile("c.html", "<p>NOISEkept</p>")),
                )

            val outcome = compaction(stories).runIfPending()!!

            assertEquals(StoredChapterCompaction.Outcome(1, 2, 1, 5, finished = true), outcome)
            assertEquals("<p>one</p>", read("a.html"))
            assertEquals("<p>two</p>", read("b.html"))
            assertEquals("<p>NOISEkept</p>", read("c.html"))
            File(temp.root, "a.html").writeText("<p>NOISEagain</p>")
            assertNull(compaction(stories).runIfPending())
            assertEquals("<p>NOISEagain</p>", read("a.html"))
        }

    @Test
    fun resumesAfterTheLastFinishedStory() =
        runBlocking {
            val first = story("rr_1", chapterFile("a.html", "<p>NOISEa</p>"))
            val second = story("rr_2", chapterFile("b.html", "<p>NOISEb</p>"))
            var failOn: String? = "b.html"
            val compactFile = { chapter: Chapter, compactor: ChapterMarkupCompactor ->
                if (chapter.filePath == failOn) throw IOException("disk full")
                ChapterMarkupCompaction.compactFile(File(temp.root, chapter.filePath!!), compactor)
            }

            val interrupted = compaction(listOf(first, second), compactFile).runIfPending()!!
            File(temp.root, "a.html").writeText("<p>NOISEa</p>")
            failOn = null
            val resumed = compaction(listOf(first, second), compactFile).runIfPending()!!

            assertEquals(false, interrupted.finished)
            assertEquals(StoredChapterCompaction.Outcome(1, 1, 1, 5, finished = true), resumed)
            // The first story finished before the failure, so the resumed pass does not touch it again.
            assertEquals("<p>NOISEa</p>", read("a.html"))
            assertEquals("<p>b</p>", read("b.html"))
        }

    @Test
    fun stopsWithoutSavingProgressWhenTheLibraryIsReplaced() =
        runBlocking {
            var generation = 1L
            val stories = listOf(story("rr_1", chapterFile("a.html", "<p>NOISEa</p>"), chapterFile("b.html", "<p>NOISEb</p>")))
            val compactFile = { chapter: Chapter, compactor: ChapterMarkupCompactor ->
                generation = 2L
                ChapterMarkupCompaction.compactFile(File(temp.root, chapter.filePath!!), compactor)
            }

            val outcome = compaction(stories, compactFile) { generation }.runIfPending()!!

            assertEquals(false, outcome.finished)
            assertEquals("<p>NOISEb</p>", read("b.html"))
            assertTrue(!File(temp.root, "chapter_compaction.txt").exists())
        }

    @Test
    fun aFileThatCannotBeRewrittenDoesNotBlockTheStoriesAfterIt() =
        runBlocking {
            val broken = story("rr_1", chapterFile("a.html", "<p>NOISEa</p>"))
            val later = story("rr_2", chapterFile("b.html", "<p>NOISEb</p>"))
            val compactFile = { chapter: Chapter, compactor: ChapterMarkupCompactor ->
                if (chapter.filePath == "a.html") error("rename refused")
                ChapterMarkupCompaction.compactFile(File(temp.root, chapter.filePath!!), compactor)
            }

            val outcome = compaction(listOf(broken, later), compactFile).runIfPending()!!

            assertEquals(StoredChapterCompaction.Outcome(1, 2, 1, 5, finished = false, filesFailed = 1), outcome)
            assertEquals("<p>b</p>", read("b.html"))
            // Only the failed story is left for the next launch.
            assertEquals(listOf("progress v1", "rr_2"), File(temp.root, "chapter_compaction.txt").readLines())
        }

    @Test
    fun aProgressFileWithoutTheVersionHeaderStartsOver() =
        runBlocking {
            File(temp.root, "chapter_compaction.txt").writeText("rr_1\n")
            val stories = listOf(story("rr_1", chapterFile("a.html", "<p>NOISEa</p>")))

            val outcome = compaction(stories).runIfPending()!!

            assertEquals(StoredChapterCompaction.Outcome(1, 1, 1, 5, finished = true), outcome)
            assertEquals("<p>a</p>", read("a.html"))
            assertEquals("finished v1", File(temp.root, "chapter_compaction.txt").readText().trim())
        }

    @Test
    fun leavesFilesThatAreNotValidUtf8Untouched() {
        val bytes =
            byteArrayOf('N'.code.toByte(), 'O'.code.toByte(), 'I'.code.toByte(), 'S'.code.toByte(), 'E'.code.toByte(), 0xC3.toByte())

        assertSame(bytes, ChapterMarkupCompaction.compactBytes(bytes, dropNoise))
    }

    @Test
    fun compactFileKeepsMultibyteTextAndReportsTheSaving() {
        val file = File(temp.root, "x.html").apply { writeText("<p>NOISE“Olá” — ☆</p>") }

        val saved = ChapterMarkupCompaction.compactFile(file, dropNoise)

        assertEquals(5L, saved)
        assertArrayEquals("<p>“Olá” — ☆</p>".toByteArray(), file.readBytes())
        assertEquals(0L, ChapterMarkupCompaction.compactFile(file, dropNoise))
    }
}
