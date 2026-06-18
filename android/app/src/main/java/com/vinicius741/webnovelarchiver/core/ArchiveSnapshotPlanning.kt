package com.vinicius741.webnovelarchiver.core

import java.util.UUID

object ArchiveSnapshotPlanning {
    const val SOURCE_CHAPTERS_REMOVED_REASON = "source_chapters_removed"

    fun buildArchiveSnapshot(
        source: Story,
        archivedAt: Long,
        randomSuffix: String = UUID.randomUUID().toString().take(6),
        reason: String = SOURCE_CHAPTERS_REMOVED_REASON,
        copyChapter: (archiveId: String, index: Int, chapter: Chapter) -> String?,
    ): Story {
        val archiveId = "${source.id}__archive_${archivedAt}_$randomSuffix"
        val archivedChapters =
            source.chapters
                .mapIndexed { index, chapter ->
                    val archivedFilePath =
                        if (chapter.downloaded && !chapter.filePath.isNullOrBlank()) {
                            copyChapter(archiveId, index, chapter) ?: chapter.filePath
                        } else if (chapter.downloaded) {
                            chapter.filePath
                        } else {
                            null
                        }
                    chapter.copy(filePath = archivedFilePath)
                }.toMutableList()

        return source.copy(
            id = archiveId,
            chapters = archivedChapters,
            isArchived = true,
            archiveOfStoryId = source.id,
            archivedAt = archivedAt,
            archiveReason = reason,
            totalChapters = archivedChapters.size,
            downloadedChapters = archivedChapters.count { it.downloaded },
            epubPath = null,
            epubPaths = null,
            epubStale = null,
            pendingNewChapterIds = null,
            lastUpdated = archivedAt,
        )
    }
}
