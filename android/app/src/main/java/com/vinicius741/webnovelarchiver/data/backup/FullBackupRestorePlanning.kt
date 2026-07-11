package com.vinicius741.webnovelarchiver.data.backup

import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.ui.size

data class RestoredChapterFileIndex(
    val storyId: String,
    val chapterId: String,
    val path: String,
)

object FullBackupRestorePlanning {
    fun scrubTransientState(stories: MutableList<Story>): MutableList<Story> {
        stories.forEach { story ->
            story.epubPath = null
            story.epubPaths = null
            story.epubStale = null
            story.downloadedChapters = 0
            story.totalChapters = story.chapters.size
            story.chapters.forEach { chapter ->
                chapter.content = null
                chapter.filePath = null
                chapter.downloaded = false
            }
        }
        return stories
    }

    fun applyRestoredChapterFiles(
        stories: MutableList<Story>,
        chapterFiles: List<RestoredChapterFileIndex>,
        resolveExistingPath: (String) -> String?,
    ): MutableList<Story> {
        val pathByStoryAndChapter =
            chapterFiles
                .filter { it.storyId.isNotBlank() && it.chapterId.isNotBlank() && it.path.isNotBlank() }
                .associate { Pair(it.storyId, it.chapterId) to it.path }

        stories.forEach { story ->
            story.chapters.forEach { chapter ->
                val backupPath = pathByStoryAndChapter[Pair(story.id, chapter.id)] ?: return@forEach
                val absolutePath = resolveExistingPath(backupPath) ?: return@forEach
                chapter.filePath = absolutePath
                chapter.downloaded = true
            }
            story.chapters.filterNot { it.downloaded }.forEach { it.downloadedAt = null }
            story.totalChapters = story.chapters.size
            story.downloadedChapters = story.chapters.count { it.downloaded }
        }
        return stories
    }

    fun restoreSummary(stories: List<Story>): String {
        val restoredChapterCount = stories.sumOf { story -> story.chapters.count { it.downloaded } }
        return "Restored ${stories.size} novels and $restoredChapterCount downloaded chapters"
    }
}
