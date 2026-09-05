package com.vinicius741.webnovelarchiver.data.backup

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/*
 * R10: the full-backup plan must classify every downloaded chapter as file-backed, inline-backed,
 * or missing — and produce a manifest-ready missing report. A success must imply all expected
 * supported content was included.
 */
class FullBackupContentPlanningTest {
    private val dir = createTempDirectory("backup_planning").toFile()

    @Test
    fun chapterWithFileIsStreamedFromFile() {
        val file = File(dir, "0001_ch.html").apply { writeText("<p>chapter html</p>") }

        val plan =
            FullBackupContentPlanning.planChapterContent(
                library = listOf(story(chapter(filePath = file.absolutePath))),
                resolveFile = { chapter -> chapter.filePath?.let(::File)?.takeIf(File::isFile) },
                chapterPath = { storyId, chapterId, index -> "novels/$storyId/$index-$chapterId.html" },
            )

        val entry = plan.single() as FullBackupContentPlanning.ChapterContent.FromFile
        assertEquals(file, entry.file)
        assertTrue(FullBackupContentPlanning.missingContentReport(plan, emptyMap()).isEmpty())
    }

    @Test
    fun inlineOnlyChapterIsMaterializedInsteadOfDropped() {
        val plan =
            FullBackupContentPlanning.planChapterContent(
                library = listOf(story(chapter(content = "<p>legacy inline</p>"))),
                resolveFile = { null },
                chapterPath = { storyId, chapterId, index -> "novels/$storyId/$index-$chapterId.html" },
            )

        val entry = plan.single() as FullBackupContentPlanning.ChapterContent.Inline
        assertEquals("<p>legacy inline</p>", entry.content)
        assertTrue(FullBackupContentPlanning.missingContentReport(plan, emptyMap()).isEmpty())
    }

    @Test
    fun missingFileAndContentIsReportedNotSilentlyOmitted() {
        val plan =
            FullBackupContentPlanning.planChapterContent(
                library = listOf(story(chapter(), chapter(id = "gone-too"))),
                resolveFile = { null },
                chapterPath = { storyId, chapterId, index -> "novels/$storyId/$index-$chapterId.html" },
            )

        val missing =
            FullBackupContentPlanning.missingContentReport(
                chapterPlan = plan,
                missingAppliedByStory = mapOf("story-1" to 2),
            )

        assertEquals(listOf("chapter", "chapter", "applied_rewrite", "applied_rewrite"), missing.map { it.kind })
        assertEquals(setOf("ch-1", "gone-too"), missing.filter { it.kind == "chapter" }.mapNotNull { it.chapterId }.toSet())
    }

    @Test
    fun notDownloadedChaptersAreNeitherExportedNorReported() {
        val plan =
            FullBackupContentPlanning.planChapterContent(
                library = listOf(story(chapter(downloaded = false))),
                resolveFile = { null },
                chapterPath = { storyId, chapterId, index -> "novels/$storyId/$index-$chapterId.html" },
            )

        assertTrue(plan.isEmpty())
    }

    private fun story(vararg chapters: Chapter) = Story(id = "story-1", title = "Story", chapters = chapters.toMutableList())

    private fun chapter(
        id: String = "ch-1",
        downloaded: Boolean = true,
        content: String? = null,
        filePath: String? = null,
    ) = Chapter(id = id, title = "Chapter $id", downloaded = downloaded, content = content, filePath = filePath)
}
