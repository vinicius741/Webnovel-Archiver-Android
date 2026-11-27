package com.vinicius741.webnovelarchiver.data.backup

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.DownloadStatus
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.StoryMetricHistory
import com.vinicius741.webnovelarchiver.domain.model.Tab
import com.vinicius741.webnovelarchiver.domain.model.TtsStoryPosition
import com.vinicius741.webnovelarchiver.domain.story.RemovedChapterPlanning

/**
 * How a quick backup merges into the library on this phone (`docs/architecture/quick-backup.md`).
 * The rule: **metadata comes from the backup, files stay on the phone**.
 *
 * - A story in the backup takes the backup's details, chapter list, bookmark, tab and settings.
 * - Chapter text is never deleted or replaced. A chapter keeps its local file when it has one; a
 *   chapter the backup lists as downloaded but that has no text here becomes not downloaded, so it
 *   can be downloaded again.
 * - A downloaded chapter on this phone that the backup does not list (removed from the source, or
 *   downloaded after the backup) stays, in its reading position, with its removed-from-source flag.
 * - Stories only on this phone stay as they are. Nothing is removed from the library.
 */
object QuickBackupMergePlanning {
    data class Counts(
        val updated: Int = 0,
        val added: Int = 0,
        val keptLocalOnly: Int = 0,
        /** Chapters the backup lists as downloaded whose text is not on this phone (now downloadable). */
        val toDownloadAgain: Int = 0,
        /** Downloaded chapters on this phone that the backup does not list, kept in place. */
        val keptUnlistedChapters: Int = 0,
    )

    data class LibraryPlan(
        val stories: List<Story>,
        /** Backup covers to write (story id to `covers/…` path); each path is free on this phone. */
        val coversToWrite: Map<String, String>,
        val counts: Counts,
    )

    /**
     * Merges [backup] into [local]. [hasLocalText] says whether a local chapter's text is on this
     * phone; [fileExists] whether a storage-relative path is a file; [backupCovers] are the cover
     * files the backup carries, by story id.
     */
    fun mergeLibrary(
        backup: List<Story>,
        local: List<Story>,
        hasLocalText: (Chapter) -> Boolean,
        fileExists: (String) -> Boolean,
        backupCovers: Map<String, String>,
    ): LibraryPlan {
        val localById = local.associateBy { it.id }
        val backupIds = backup.mapTo(HashSet()) { it.id }
        val coversToWrite = LinkedHashMap<String, String>()
        var counts = Counts()
        val merged =
            backup.map { incoming ->
                val existing = localById[incoming.id]
                val chapters = mergeChapters(incoming, existing, hasLocalText)
                counts =
                    counts.copy(
                        updated = counts.updated + if (existing != null) 1 else 0,
                        added = counts.added + if (existing == null) 1 else 0,
                        toDownloadAgain = counts.toDownloadAgain + chapters.toDownloadAgain,
                        keptUnlistedChapters = counts.keptUnlistedChapters + chapters.keptUnlisted,
                    )
                val cover = coverFor(incoming, existing, fileExists, backupCovers)
                cover.write?.let { coversToWrite[incoming.id] = it }
                mergedStory(incoming, existing, chapters.list, cover)
            }
        val localOnly = local.filter { it.id !in backupIds }
        counts = counts.copy(keptLocalOnly = localOnly.size)
        return LibraryPlan(stories = merged + localOnly, coversToWrite = coversToWrite, counts = counts)
    }

    /** The backup's tabs, then this phone's tabs the backup does not have, so no story points at a missing tab. */
    fun mergeTabs(
        backup: List<Tab>?,
        local: List<Tab>,
    ): List<Tab> {
        if (backup == null) return local
        val ids = HashSet<String>()
        val tabs = backup.sortedBy { it.order }.filter { ids.add(it.id) } + local.sortedBy { it.order }.filter { ids.add(it.id) }
        return tabs.mapIndexed { index, tab -> tab.copy(order = index) }
    }

    /** The backup's listening positions win for its stories; positions only on this phone stay. */
    fun mergeTtsPositions(
        backup: Map<String, TtsStoryPosition>?,
        local: Map<String, TtsStoryPosition>,
    ): Map<String, TtsStoryPosition> = if (backup == null) local else local + backup

    /**
     * Whether the backup's metric history should replace this phone's. History only grows, so the
     * one with the newer last snapshot holds more; a tie keeps the phone's file.
     */
    fun backupHistoryIsNewer(
        backup: StoryMetricHistory,
        local: StoryMetricHistory?,
    ): Boolean {
        val backupNewest = backup.snapshots.maxOfOrNull { it.capturedAt } ?: return false
        val localNewest = local?.snapshots?.maxOfOrNull { it.capturedAt } ?: return true
        return backupNewest > localNewest
    }

    /** The outcome line shown after a quick restore. */
    fun summary(counts: Counts): String =
        buildString {
            val novels = counts.updated + counts.added
            append("Restored quick backup: $novels novel${if (novels == 1) "" else "s"}")
            if (counts.added > 0) append(" (${counts.added} new)")
            append(". Chapter text on this phone was kept")
            if (counts.toDownloadAgain > 0) {
                append("; ${counts.toDownloadAgain} chapter${if (counts.toDownloadAgain == 1) "" else "s"} can be downloaded again")
            }
            append('.')
        }

    private class MergedChapters(
        val list: MutableList<Chapter>,
        val toDownloadAgain: Int,
        val keptUnlisted: Int,
    )

    private fun mergeChapters(
        incoming: Story,
        existing: Story?,
        hasLocalText: (Chapter) -> Boolean,
    ): MergedChapters {
        val localWithText =
            existing
                ?.chapters
                .orEmpty()
                .filter { it.downloaded && hasLocalText(it) }
                .associateBy { it.id }
        val localById = existing?.chapters.orEmpty().associateBy { it.id }
        var toDownloadAgain = 0
        val listed =
            incoming.chapters.map { chapter ->
                val withText = localWithText[chapter.id]
                if (withText != null) {
                    chapter.copy(
                        content = withText.content,
                        filePath = withText.filePath,
                        downloaded = true,
                        downloadedAt = withText.downloadedAt,
                        // An older backup must not unflag a chapter this phone already found removed.
                        removedFromSourceAt = withText.removedFromSourceAt ?: chapter.removedFromSourceAt,
                    )
                } else {
                    if (chapter.downloaded) toDownloadAgain += 1
                    chapter.copy(
                        content = null,
                        filePath = null,
                        downloaded = false,
                        downloadedAt = null,
                        removedFromSourceAt = localById[chapter.id]?.removedFromSourceAt ?: chapter.removedFromSourceAt,
                    )
                }
            }
        val listedIds = incoming.chapters.mapTo(HashSet()) { it.id }
        val unlisted = localWithText.values.filter { it.id !in listedIds }
        val previous = existing?.chapters.orEmpty()
        val chapters = RemovedChapterPlanning.placeKept(previous, listed, unlisted) { it.id }.toMutableList()
        return MergedChapters(chapters, toDownloadAgain, unlisted.size)
    }

    private class CoverChoice(
        val path: String?,
        val showAiCover: Boolean,
        val write: String?,
    )

    /**
     * A cover file on this phone is never replaced: the story keeps its own cover when the file is
     * here. Otherwise the backup's cover is used when its file is here or the backup carries it,
     * and a cover that is in neither place falls back to the source cover.
     */
    private fun coverFor(
        incoming: Story,
        existing: Story?,
        fileExists: (String) -> Boolean,
        backupCovers: Map<String, String>,
    ): CoverChoice {
        existing?.aiCoverPath?.takeIf(fileExists)?.let { return CoverChoice(it, existing.showAiCover, write = null) }
        val wanted = incoming.aiCoverPath ?: return CoverChoice(null, incoming.showAiCover, write = null)
        return when {
            fileExists(wanted) -> CoverChoice(wanted, incoming.showAiCover, write = null)
            backupCovers[incoming.id] == wanted -> CoverChoice(wanted, incoming.showAiCover, write = wanted)
            else -> CoverChoice(null, showAiCover = false, write = null)
        }
    }

    private fun mergedStory(
        incoming: Story,
        existing: Story?,
        chapters: MutableList<Chapter>,
        cover: CoverChoice,
    ): Story {
        val ids = chapters.mapTo(HashSet()) { it.id }
        val downloaded = chapters.count { it.downloaded }
        val placed = RemovedChapterPlanning.remapPositions(incoming, incoming.chapters, chapters)
        return placed.copy(
            chapters = chapters,
            totalChapters = chapters.size,
            downloadedChapters = downloaded,
            aiCoverPath = cover.path,
            showAiCover = cover.showAiCover,
            // EPUB files and the download state belong to this phone.
            epubPath = existing?.epubPath,
            epubPaths = existing?.epubPaths,
            epubStale = existing?.epubStale,
            // A story new to this phone has no chapter text yet.
            status = existing?.status ?: DownloadStatus.idle,
            pendingNewChapterIds = incoming.pendingNewChapterIds?.filter { it in ids }?.toMutableList(),
            lastReadChapterId = incoming.lastReadChapterId?.takeIf { it in ids } ?: existing?.lastReadChapterId?.takeIf { it in ids },
        )
    }
}
