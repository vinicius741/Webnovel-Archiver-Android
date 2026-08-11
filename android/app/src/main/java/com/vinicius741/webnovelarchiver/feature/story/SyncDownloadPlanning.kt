package com.vinicius741.webnovelarchiver.feature.story

import com.vinicius741.webnovelarchiver.domain.model.Story

enum class SyncDownloadAction {
    NONE,
    AUTO_QUEUE,
    REVIEW,
}

data class SyncDownloadPlan(
    val action: SyncDownloadAction,
    val chapterIndexes: List<Int>,
    val chapterIds: List<String>,
)

/**
 * Decides which chapters a completed sync may download automatically.
 *
 * [Story.pendingNewChapterIds] is intentionally cumulative until each chapter downloads. Comparing
 * the post-sync list with the pre-sync list separates chapters discovered by this sync from an old
 * cancelled or failed backlog, so tapping Sync cannot silently restart earlier work.
 */
object SyncDownloadPlanning {
    const val AUTO_DOWNLOAD_LIMIT = 20

    fun plan(
        before: Story?,
        synced: Story,
        autoDownloadLimit: Int = AUTO_DOWNLOAD_LIMIT,
    ): SyncDownloadPlan {
        if (before == null) return SyncDownloadPlan(SyncDownloadAction.NONE, emptyList(), emptyList())

        val previouslyPending = before.pendingNewChapterIds.orEmpty().toSet()
        val newlyPending =
            synced.pendingNewChapterIds
                .orEmpty()
                .filterNot { it in previouslyPending }
                .toSet()
        if (newlyPending.isEmpty()) return SyncDownloadPlan(SyncDownloadAction.NONE, emptyList(), emptyList())

        val indexes =
            synced.chapters.mapIndexedNotNull { index, chapter ->
                index.takeIf { chapter.id in newlyPending && !chapter.downloaded }
            }
        if (indexes.isEmpty()) return SyncDownloadPlan(SyncDownloadAction.NONE, emptyList(), emptyList())

        val ids = indexes.map { synced.chapters[it].id }
        val action =
            if (indexes.size <= autoDownloadLimit.coerceAtLeast(0)) {
                SyncDownloadAction.AUTO_QUEUE
            } else {
                SyncDownloadAction.REVIEW
            }
        return SyncDownloadPlan(action, indexes, ids)
    }
}
