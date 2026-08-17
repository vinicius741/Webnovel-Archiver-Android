package com.vinicius741.webnovelarchiver.data.backup

import com.vinicius741.webnovelarchiver.domain.model.Story

data class RestoredChapterFileIndex(
    val storyId: String,
    val chapterId: String,
    val path: String,
)

/** A trend-history file listed in a full-backup manifest. Unlike [RestoredChapterFileIndex], the file
 *  is an opaque JSON blob restored verbatim into `metrics/` — it does not mutate any `Story` field, so
 *  there is no `apply…` step parallel to [FullBackupRestorePlanning.applyRestoredChapterFiles]. */
data class RestoredMetricFileIndex(
    val storyId: String,
    val path: String,
)

/** A generated AI cover listed in a full-backup manifest, restored verbatim into `covers/` under the
 *  story's own relative `aiCoverPath` — so the story JSON keeps resolving without rewriting it. */
data class RestoredCoverFileIndex(
    val storyId: String,
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

    /**
     * Keeps each story's [Story.aiCoverPath] only when it is exactly that story's validated
     * `coverFiles` entry from the backup manifest. The path arrives inside untrusted backup JSON
     * and is re-resolved against the live storage root after the swap, so it must never be probed
     * on the filesystem (`File(staged, path).isFile` follows `../` traversal) — anything that is
     * not the manifest's already-constrained flat `covers/<name>.<image>` entry falls back to the
     * source cover URL instead. Legit backups satisfy the equality by construction (the exporter
     * records `path = story.aiCoverPath`); backups predating AI covers carry null on both sides.
     */
    fun retainRestoredCoverPaths(
        stories: MutableList<Story>,
        coverFiles: List<RestoredCoverFileIndex>,
    ): MutableList<Story> {
        val restoredPaths = coverFiles.associate { it.storyId to it.path }
        stories.forEach { story ->
            if (story.aiCoverPath != null && story.aiCoverPath != restoredPaths[story.id]) {
                story.aiCoverPath = null
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
