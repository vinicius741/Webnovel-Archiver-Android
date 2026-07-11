package com.vinicius741.webnovelarchiver.sync

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.DownloadStatus
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.source.SourceProvider

/**
 * Folds a freshly-synced [Story] (built from network metadata + merged chapters, captured before a
 * long network window) onto the *current* on-disk story so a concurrent writer cannot be lost
 * (audit gap 10 / Rec 3).
 *
 * The hazard: [com.vinicius741.webnovelarchiver.sync.StorySyncEngine.fetchOrSync] reads the existing
 * story, then performs network work (seconds to minutes), then writes the merged story. If a download
 * completes for a chapter during that window, the on-disk story gains `chapter.downloaded = true` and
 * a `filePath` — but the synced story was built from the stale pre-sync snapshot, so a wholesale write
 * would clobber the download. The same applies to bookmark/`lastReadChapterId` changes a user makes
 * while a sync is in flight.
 *
 * This helper is pure (no Android, no storage) so it can be unit-tested directly. It preserves the
 * synced story's metadata/chapter-list as the source of truth, but re-applies per-chapter downloaded
 * state and the user's reading position from the current on-disk story.
 */
object StorySyncMergePlanning {
    /**
     * @param synced the story produced by the sync (metadata + merged chapter list); its chapter
     *   ordering and metadata win.
     * @param onDisk the story currently persisted, re-read immediately before the write (may carry
     *   newer per-chapter download state or a newer reading position).
     * @param provider used to derive stable chapter ids for matching [Chapter] across the two lists.
     * @return the [synced] story with concurrent on-disk progress folded in, ready to persist.
     */
    fun foldConcurrentChanges(
        synced: Story,
        onDisk: Story?,
        provider: SourceProvider,
    ): Story {
        if (onDisk == null) return synced
        if (synced.id != onDisk.id) return synced

        val onDiskByStable = linkedMapOf<String, Chapter>()
        onDisk.chapters.forEach { chapter ->
            val stable = provider.getChapterId(chapter.url) ?: chapter.id.ifBlank { chapter.url }
            if (stable.isNotBlank()) onDiskByStable.putIfAbsent(stable, chapter)
        }

        val mergedChapters =
            synced.chapters
                .map { chapter ->
                    val stable = provider.getChapterId(chapter.url) ?: chapter.id.ifBlank { chapter.url }
                    val current = if (stable.isNotBlank()) onDiskByStable[stable] else null
                    if (current != null && current.downloaded && current.filePath != null) {
                        // Preserve a download that completed during the sync window. The synced chapter's
                        // metadata (title/url/publishedAt) already won in StorySyncPlanning.mergeChapters;
                        // only the on-disk download state was at risk of being clobbered.
                        chapter.copy(
                            downloaded = true,
                            filePath = current.filePath,
                            content = current.content ?: chapter.content,
                            downloadedAt = current.downloadedAt ?: chapter.downloadedAt,
                        )
                    } else {
                        chapter
                    }
                }.toMutableList()

        val downloadedCount = mergedChapters.count { it.downloaded }
        val status =
            when {
                downloadedCount == 0 -> synced.status
                downloadedCount == mergedChapters.size -> DownloadStatus.completed
                else -> DownloadStatus.partial
            }
        // The on-disk story was re-read after the network window, so its bookmark is the latest user
        // state. Remap it through stable chapter ids and keep it only when that chapter still exists.
        val lastRead =
            remapLastRead(
                lastRead = onDisk.lastReadChapterId,
                sourceChapters = onDisk.chapters,
                targetChapters = mergedChapters,
                provider = provider,
            )

        return synced.copy(
            chapters = mergedChapters,
            downloadedChapters = downloadedCount,
            status = status,
            lastReadChapterId = lastRead,
        )
    }

    private fun remapLastRead(
        lastRead: String?,
        sourceChapters: List<Chapter>,
        targetChapters: List<Chapter>,
        provider: SourceProvider,
    ): String? {
        lastRead ?: return null
        val sourceStable =
            sourceChapters
                .firstOrNull { it.id == lastRead || it.url == lastRead }
                ?.let { stableChapterId(it, provider) }
                ?: lastRead
        return targetChapters.firstOrNull { stableChapterId(it, provider) == sourceStable }?.id
    }

    private fun stableChapterId(
        chapter: Chapter,
        provider: SourceProvider,
    ): String = provider.getChapterId(chapter.url) ?: chapter.id.ifBlank { chapter.url }
}
