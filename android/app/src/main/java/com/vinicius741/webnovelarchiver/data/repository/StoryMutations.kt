package com.vinicius741.webnovelarchiver.data.repository

import com.vinicius741.webnovelarchiver.domain.model.DownloadStatus
import com.vinicius741.webnovelarchiver.domain.model.EpubConfig
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.StoryBookmarkPlanning

/**
 * Pure, narrow story mutations used by [AppRepository]'s storage transactions. Each function accepts
 * the latest persisted story rather than a screen/engine snapshot, so unrelated fields survive an
 * operation that was in flight while another writer changed the same story.
 */
internal object StoryMutations {
    fun markEpubGenerated(
        latest: Story,
        paths: List<String>,
    ): Story =
        latest.copy(
            epubPaths = paths.toMutableList(),
            epubPath = paths.firstOrNull(),
            epubStale = false,
        )

    fun markCleanupApplied(latest: Story): Story = latest.copy(epubStale = true)

    fun setLastReadChapter(
        latest: Story,
        chapterId: String,
    ): Story = latest.copy(lastReadChapterId = chapterId)

    fun toggleBookmark(
        latest: Story,
        chapterId: String,
    ): Story? =
        latest
            .takeIf { story -> story.chapters.any { it.id == chapterId } }
            ?.let { StoryBookmarkPlanning.withBookmark(it, chapterId, toggleExisting = true) }

    fun setBookmark(
        latest: Story,
        chapterId: String,
    ): Story? =
        latest
            .takeIf { story -> story.chapters.any { it.id == chapterId } }
            ?.let { StoryBookmarkPlanning.withBookmark(it, chapterId, toggleExisting = false) }

    fun setEpubConfig(
        latest: Story,
        config: EpubConfig,
    ): Story = latest.copy(epubConfig = config)

    fun retainEpubPaths(
        latest: Story,
        paths: List<String>,
    ): Story =
        latest.copy(
            epubPaths = paths.toMutableList().ifEmpty { null },
            epubPath = paths.firstOrNull(),
            epubStale = if (paths.isEmpty()) false else latest.epubStale,
        )

    fun markChapterDownloaded(
        latest: Story,
        chapterId: String,
        path: String,
        completedAt: Long,
    ): Story? {
        val chapterIndex = latest.chapters.indexOfFirst { it.id == chapterId }
        if (chapterIndex < 0) return null
        val chapters = latest.chapters.map { it.copy() }.toMutableList()
        chapters[chapterIndex] = chapters[chapterIndex].copy(filePath = path, downloaded = true)
        val downloadedCount = chapters.count { it.downloaded }
        return latest.copy(
            chapters = chapters,
            downloadedChapters = downloadedCount,
            status = if (downloadedCount == chapters.size) DownloadStatus.completed else DownloadStatus.partial,
            epubStale = true,
            pendingNewChapterIds =
                latest.pendingNewChapterIds
                    ?.filterNot { it == chapterId }
                    ?.toMutableList()
                    ?.ifEmpty { null },
            lastUpdated = completedAt,
        )
    }

    /** Isolates observable snapshots from mutable model instances retained by engines or screens. */
    fun snapshot(story: Story): Story =
        story.copy(
            chapters = story.chapters.map { it.copy() }.toMutableList(),
            epubPaths = story.epubPaths?.toMutableList(),
            pendingNewChapterIds = story.pendingNewChapterIds?.toMutableList(),
            tags = story.tags?.toMutableList(),
        )
}
