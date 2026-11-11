package com.vinicius741.webnovelarchiver.domain.story

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story

/**
 * Folds legacy whole-story archive snapshots (`<storyId>__archive_<time>_<suffix>` entries with
 * [Story.isArchived]) back into their live story. Every downloaded chapter a snapshot holds that
 * the live story lacks becomes a removed-from-source chapter in its old reading position; one the
 * live story lists but never downloaded takes the snapshot's copy. A snapshot whose live story was
 * deleted becomes the live story again under the original id.
 *
 * Pure: the storage executor copies each adopted chapter file into the live story's folder,
 * verifies the result, persists it, and only then deletes the snapshots.
 */
object LegacyArchiveFoldPlanning {
    private const val ARCHIVE_ID_MARKER = "__archive_"

    data class Plan(
        /** The live story after folding, still pointing adopted chapters at the snapshot files. */
        val story: Story,
        val archiveIds: List<String>,
        /** Chapters of [story] whose file belongs to a snapshot and must move into the story's folder. */
        val adoptedChapterIds: Set<String>,
        /** True when no live story existed and [story] is a promoted snapshot. */
        val createsStory: Boolean,
    )

    fun isLegacyArchive(story: Story): Boolean = story.isArchived == true

    /**
     * Builds a check of whether [story] already holds readable text for a snapshot chapter: the
     * same chapter downloaded, or, for a Patreon copy, the downloaded public chapter it is a copy
     * of. The story's readable chapters are read once, so checking a whole snapshot stays linear.
     * The executor uses the same rule to verify that folding loses nothing.
     */
    fun coverage(
        story: Story,
        key: (Chapter) -> String,
        hasContent: (Chapter) -> Boolean,
    ): (Chapter) -> Boolean {
        val readable = story.chapters.filter { it.downloaded && hasContent(it) }
        val readableKeys = readable.mapTo(HashSet(), key)
        val publicKeys by lazy { PatreonCopyPlanning.publicKeys(readable.filterNot { it.isPatreonCopy }) }
        return { saved ->
            key(saved) in readableKeys ||
                (saved.isPatreonCopy && PatreonCopyPlanning.publicMatch(saved.title, publicKeys) != null)
        }
    }

    fun baseStoryId(archive: Story): String =
        archive.archiveOfStoryId?.takeIf { it.isNotBlank() } ?: archive.id.substringBefore(ARCHIVE_ID_MARKER)

    /**
     * @param keyFor stable chapter identity within a story (the sync matcher's), so a chapter
     *   whose id format changed still matches.
     * @param hasContent whether a downloaded chapter's text is actually readable (file or inline).
     */
    fun plan(
        library: List<Story>,
        keyFor: (Story) -> (Chapter) -> String,
        hasContent: (Chapter) -> Boolean,
    ): List<Plan> {
        val liveById = library.filterNot(::isLegacyArchive).associateBy { it.id }
        return library
            .filter(::isLegacyArchive)
            .groupBy(::baseStoryId)
            .map { (baseId, archives) ->
                val newestFirst = archives.sortedByDescending { it.archivedAt ?: it.lastUpdated ?: 0L }
                val live = liveById[baseId]
                val adopted = mutableSetOf<String>()
                var story =
                    live ?: promote(newestFirst.first(), baseId, hasContent).also { promoted ->
                        // Every file of a promoted snapshot still lives in the snapshot's folder.
                        promoted.chapters.filter { it.downloaded && it.content == null }.mapTo(adopted) { it.id }
                    }
                val chapterKey = keyFor(story)
                val toFold = if (live == null) newestFirst.drop(1) else newestFirst
                toFold.forEach { archive -> story = foldOne(story, archive, chapterKey, hasContent, adopted) }
                Plan(story, archives.map { it.id }, adopted, createsStory = live == null)
            }
    }

    private fun promote(
        archive: Story,
        baseId: String,
        hasContent: (Chapter) -> Boolean,
    ): Story {
        val chapters =
            archive.chapters
                .map { chapter ->
                    if (chapter.downloaded && !hasContent(chapter)) {
                        chapter.copy(downloaded = false, filePath = null, downloadedAt = null)
                    } else {
                        chapter
                    }
                }.toMutableList()
        return archive.copy(
            id = baseId,
            chapters = chapters,
            totalChapters = chapters.size,
            downloadedChapters = chapters.count { it.downloaded },
            isArchived = null,
            archiveOfStoryId = null,
            archivedAt = null,
            archiveReason = null,
        )
    }

    /**
     * Folds one [archive] snapshot into [story]: downloaded snapshot chapters the story lacks become
     * removed-from-source chapters in their old position, and story rows that are not downloaded
     * take the snapshot's copy. Ids of chapters whose file still lives outside the story's folder
     * are added to [adopted]. Also used by [BackupChapterRecoveryPlanning].
     */
    internal fun foldOne(
        story: Story,
        archive: Story,
        key: (Chapter) -> String,
        hasContent: (Chapter) -> Boolean,
        adopted: MutableSet<String>,
    ): Story {
        val removedAt = archive.archivedAt ?: archive.lastUpdated ?: 0L
        val currentByKey = HashMap<String, Chapter>()
        story.chapters.forEach { currentByKey.putIfAbsent(key(it), it) }
        val filled = HashMap<String, Chapter>()
        val kept = mutableListOf<Chapter>()
        val covered = coverage(story, key, hasContent)
        archive.chapters
            .filter { it.downloaded && hasContent(it) }
            .forEach { saved ->
                val current = currentByKey[key(saved)]
                when {
                    saved.isPatreonCopy && covered(saved) -> Unit
                    // The snapshot holds the only copy of this early-access text: keep it as an
                    // ordinary removed chapter, since no Patreon link manages it any more.
                    current == null && saved.isPatreonCopy ->
                        kept +=
                            saved.copy(
                                patreonPostId = null,
                                patreonReplacedBy = null,
                                removedFromSourceAt = saved.removedFromSourceAt ?: removedAt,
                            )
                    current == null -> kept += saved.copy(removedFromSourceAt = saved.removedFromSourceAt ?: removedAt)
                    // A live row whose file went missing takes the snapshot's copy too.
                    !current.downloaded || !hasContent(current) ->
                        filled[current.id] =
                            current.copy(
                                downloaded = true,
                                filePath = saved.filePath,
                                content = saved.content,
                                downloadedAt = saved.downloadedAt,
                            )
                }
            }
        if (kept.isEmpty() && filled.isEmpty()) return story
        val withFills = story.chapters.map { filled[it.id] ?: it }
        val chapters = RemovedChapterPlanning.placeKept(archive.chapters, withFills, kept, key).toMutableList()
        (kept + filled.values).filter { it.content == null }.mapTo(adopted) { it.id }
        val keptIds = kept.mapTo(HashSet()) { it.id }
        val hasEpub = story.epubPath != null || !story.epubPaths.isNullOrEmpty()
        return RemovedChapterPlanning.remapPositions(story, story.chapters, chapters).copy(
            chapters = chapters,
            totalChapters = chapters.size,
            downloadedChapters = chapters.count { it.downloaded },
            // The source reset the bookmark when it dropped the chapter it pointed at.
            lastReadChapterId = story.lastReadChapterId ?: archive.lastReadChapterId?.takeIf { it in keptIds },
            pendingNewChapterIds =
                story.pendingNewChapterIds
                    ?.filterNot { it in filled }
                    ?.toMutableList()
                    ?.ifEmpty { null },
            epubStale = if (hasEpub) true else story.epubStale,
        )
    }
}
