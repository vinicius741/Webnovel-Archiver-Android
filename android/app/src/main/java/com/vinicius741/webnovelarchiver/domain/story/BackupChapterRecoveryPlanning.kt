package com.vinicius741.webnovelarchiver.domain.story

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story

/**
 * Recovers downloaded chapters from a full backup into the live library without replacing it.
 * Each backup novel (and each legacy archive snapshot the backup still holds) is treated like a
 * legacy archive snapshot of the live novel with the same id, through
 * [LegacyArchiveFoldPlanning.foldOne]:
 *
 * - a downloaded backup chapter the live novel no longer lists comes back as a removed-from-source
 *   chapter in its old reading position;
 * - a live chapter that is not downloaded (or whose file is missing) takes the backup's copy;
 * - a chapter the live novel already has readable is left alone.
 *
 * Novels that no longer exist in the live library are skipped: the user may have deleted them on
 * purpose, and recovery only ever adds text to novels that are still there. Pure: the storage
 * executor copies each adopted file into the live novel's folder, verifies, and persists.
 */
object BackupChapterRecoveryPlanning {
    data class StoryPlan(
        /** The live novel after recovery, still pointing adopted chapters at the backup's files. */
        val story: Story,
        /** Chapters of [story] whose file belongs to the backup and must move into the novel's folder. */
        val adoptedChapterIds: Set<String>,
        /** Backup novels and snapshots folded into [story], newest first. */
        val sources: List<Story>,
        /** Chapters added back as removed-from-source rows. */
        val restoredRemoved: Int,
        /** Existing rows that were not readable and now have the backup's text. */
        val filled: Int,
    ) {
        val recovered: Int get() = restoredRemoved + filled
    }

    data class Plan(
        val stories: List<StoryPlan>,
        /** Backup novel ids with readable chapters but no live novel; not recovered. */
        val skippedMissingStoryIds: List<String>,
    ) {
        val recoveredChapters: Int get() = stories.sumOf { it.recovered }
    }

    /**
     * @param backupTakenAt when the backup was made; marks recovered chapters' removal time when the
     *   backup copy itself is not a legacy snapshot with its own `archivedAt`.
     * @param keyFor stable chapter identity within a story (the sync matcher's).
     * @param hasContent whether a downloaded chapter's text is actually readable.
     */
    fun plan(
        live: List<Story>,
        backup: List<Story>,
        backupTakenAt: Long,
        keyFor: (Story) -> (Chapter) -> String,
        hasContent: (Chapter) -> Boolean,
    ): Plan {
        val liveById = live.filterNot(LegacyArchiveFoldPlanning::isLegacyArchive).associateBy { it.id }
        val skipped = mutableListOf<String>()
        val plans =
            backup
                .filter { story -> story.chapters.any { it.downloaded && hasContent(it) } }
                .groupBy(::baseId)
                .mapNotNull { (baseId, sources) ->
                    val target = liveById[baseId]
                    if (target == null) {
                        skipped += baseId
                        return@mapNotNull null
                    }
                    planStory(target, sources, backupTakenAt, keyFor(target), hasContent)
                }
        return Plan(plans, skipped.sorted())
    }

    private fun baseId(story: Story): String =
        if (LegacyArchiveFoldPlanning.isLegacyArchive(story)) LegacyArchiveFoldPlanning.baseStoryId(story) else story.id

    private fun planStory(
        live: Story,
        sources: List<Story>,
        backupTakenAt: Long,
        key: (Chapter) -> String,
        hasContent: (Chapter) -> Boolean,
    ): StoryPlan? {
        // The backup's own copy of the novel is its newest state; its archive snapshots are older.
        val snapshots =
            sources
                .map { source -> source.copy(archivedAt = source.archivedAt ?: backupTakenAt) }
                .sortedByDescending { it.archivedAt }
        val adopted = mutableSetOf<String>()
        var story = live
        snapshots.forEach { snapshot -> story = LegacyArchiveFoldPlanning.foldOne(story, snapshot, key, hasContent, adopted) }
        if (story === live) return null
        val liveKeys = live.chapters.mapTo(HashSet(), key)
        val liveReadable = live.chapters.filter { it.downloaded && hasContent(it) }.mapTo(HashSet(), key)
        val recovered = story.chapters.filter { it.downloaded && key(it) !in liveReadable }
        if (recovered.isEmpty()) return null
        val restoredRemoved = recovered.count { key(it) !in liveKeys }
        return StoryPlan(
            story = story,
            adoptedChapterIds = adopted.filterTo(HashSet()) { id -> story.chapters.any { it.id == id } },
            sources = snapshots,
            restoredRemoved = restoredRemoved,
            filled = recovered.size - restoredRemoved,
        )
    }

    fun summary(plan: Plan): String {
        if (plan.stories.isEmpty()) {
            return "Nothing to recover: every downloaded chapter in this backup is already in your library."
        }
        val chapters = plan.recoveredChapters
        val novels = plan.stories.size
        return "Recovered $chapters chapter${if (chapters == 1) "" else "s"} in $novels novel${if (novels == 1) "" else "s"}"
    }
}
