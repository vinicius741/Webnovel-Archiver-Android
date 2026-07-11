package com.vinicius741.webnovelarchiver.data.backup

import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.sync.StorySyncPlanning
import com.vinicius741.webnovelarchiver.ui.size

object BackupMergePlanning {
    fun mergeJsonBackupStory(
        incoming: Story,
        existing: Story?,
    ): Story {
        if (existing == null) return scrubPortableIncomingStory(incoming)

        val existingChaptersById = existing.chapters.associateBy { it.id }
        val mergedChapters =
            incoming.chapters
                .map { chapter ->
                    val existingChapter = existingChaptersById[chapter.id]
                    if (existingChapter == null) {
                        chapter.copy(
                            content = null,
                            filePath = null,
                            downloaded = false,
                            downloadedAt = null,
                        )
                    } else {
                        chapter.copy(
                            content = existingChapter.content,
                            filePath = existingChapter.filePath,
                            downloaded = existingChapter.downloaded,
                            downloadedAt = existingChapter.downloadedAt,
                        )
                    }
                }.toMutableList()

        val merged =
            incoming.copy(
                chapters = mergedChapters,
                downloadedChapters = mergedChapters.count { it.downloaded },
                lastUpdated = existing.lastUpdated,
                lastReadChapterId = existing.lastReadChapterId,
                dateAdded = existing.dateAdded,
                epubPath = existing.epubPath,
                epubPaths = existing.epubPaths,
                epubStale = existing.epubStale,
                pendingNewChapterIds =
                    StorySyncPlanning.buildPendingNewChapterIds(
                        existing.pendingNewChapterIds,
                        incoming.pendingNewChapterIds.orEmpty(),
                        mergedChapters,
                    ),
            )
        merged.totalChapters = mergedChapters.size
        return merged
    }

    private fun scrubPortableIncomingStory(incoming: Story): Story {
        val chapters =
            incoming.chapters
                .map { chapter ->
                    chapter.copy(
                        content = null,
                        filePath = null,
                        downloaded = false,
                        downloadedAt = null,
                    )
                }.toMutableList()
        return incoming.copy(
            chapters = chapters,
            totalChapters = chapters.size,
            downloadedChapters = 0,
            epubPath = null,
            epubPaths = null,
            epubStale = null,
        )
    }
}
