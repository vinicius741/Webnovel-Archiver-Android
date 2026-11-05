package com.vinicius741.webnovelarchiver.data.storage

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.LegacyArchiveFoldPlanning
import timber.log.Timber

/**
 * Executes [LegacyArchiveFoldPlanning] on [library] (the already-loaded stories) and returns the
 * library as it stands afterwards. Per story, the order is crash-safe and idempotent: adopted
 * files are copied into the live story's folder, every downloaded chapter of every snapshot is
 * verified to be readable in the result, the live story is written, and only then are the
 * snapshots deleted. A failure leaves that story's snapshots untouched for the next startup.
 */
internal fun AppStorage.foldLegacyArchives(
    library: List<Story>,
    keyFor: (Story) -> (Chapter) -> String,
): List<Story> {
    if (library.none(LegacyArchiveFoldPlanning::isLegacyArchive)) return library
    synchronized(this) {
        val byId = LinkedHashMap<String, Story>()
        library.forEach { byId[it.id] = it }
        LegacyArchiveFoldPlanning.plan(library, keyFor, ::chapterAvailable).forEach { plan ->
            runCatching { applyFoldPlan(plan, byId, keyFor) }
                .onSuccess { folded ->
                    plan.archiveIds.forEach(byId::remove)
                    byId[folded.id] = folded
                    Timber.i("Folded %d archive snapshot(s) into %s", plan.archiveIds.size, folded.id)
                }.onFailure { error -> Timber.w(error, "Could not fold archive snapshots into %s; kept them", plan.story.id) }
        }
        return byId.values.toList()
    }
}

/** Reads the whole library from disk and folds any snapshots; used after a restore or import. */
internal fun AppStorage.foldLegacyArchivesOnDisk(keyFor: (Story) -> (Chapter) -> String) {
    synchronized(this) {
        val load = loadLibraryForStartup()
        if (load.stories.none(LegacyArchiveFoldPlanning::isLegacyArchive)) return
        foldLegacyArchives(load.stories, keyFor)
    }
}

private fun AppStorage.applyFoldPlan(
    plan: LegacyArchiveFoldPlanning.Plan,
    byId: Map<String, Story>,
    keyFor: (Story) -> (Chapter) -> String,
): Story {
    val storyId = plan.story.id
    val chapters =
        plan.story.chapters
            .map { chapter ->
                if (chapter.id !in plan.adoptedChapterIds) {
                    chapter
                } else {
                    val path = checkNotNull(adoptChapterFile(storyId, chapter)) { "Missing file for chapter ${chapter.id}" }
                    chapter.copy(filePath = path)
                }
            }.toMutableList()
    val folded = plan.story.copy(chapters = chapters)
    verifyNothingLost(folded, plan.archiveIds.mapNotNull(byId::get), keyFor(folded))
    addOrUpdateStory(folded)
    plan.archiveIds.forEach(::deleteStory)
    return folded
}

private fun AppStorage.verifyNothingLost(
    folded: Story,
    archives: List<Story>,
    key: (Chapter) -> String,
) {
    val covered = LegacyArchiveFoldPlanning.coverage(folded, key, ::chapterAvailable)
    archives.forEach { archive ->
        val lost = archive.chapters.filter { chapter -> chapter.downloaded && chapterAvailable(chapter) && !covered(chapter) }
        check(lost.isEmpty()) { "${lost.size} chapter(s) of ${archive.id} would be lost" }
    }
}
