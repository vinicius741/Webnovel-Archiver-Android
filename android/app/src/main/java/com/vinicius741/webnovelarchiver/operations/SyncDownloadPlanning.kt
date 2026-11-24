package com.vinicius741.webnovelarchiver.operations

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
 * cancelled or failed backlog, so tapping Sync cannot silently restart earlier work. Local novels
 * ([plan]'s `readsEveryChapter`) read every missing chapter instead.
 */
object SyncDownloadPlanning {
    const val AUTO_DOWNLOAD_LIMIT = 20

    fun plan(
        before: Story?,
        synced: Story,
        autoDownloadLimit: Int = AUTO_DOWNLOAD_LIMIT,
        readsEveryChapter: Boolean = false,
    ): SyncDownloadPlan {
        if (readsEveryChapter) return everyChapter(synced)
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

    /**
     * A local novel's chapters are its own files: reading them costs no network and no site's
     * patience, so every chapter not yet in the library is read, on import and on each rescan.
     */
    private fun everyChapter(synced: Story): SyncDownloadPlan {
        val indexes = synced.chapters.mapIndexedNotNull { index, chapter -> index.takeIf { !chapter.downloaded && !chapter.isPatreonCopy } }
        if (indexes.isEmpty()) return SyncDownloadPlan(SyncDownloadAction.NONE, emptyList(), emptyList())
        return SyncDownloadPlan(SyncDownloadAction.AUTO_QUEUE, indexes, indexes.map { synced.chapters[it].id })
    }
}
