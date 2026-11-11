package com.vinicius741.webnovelarchiver.data.diagnostics

import com.vinicius741.webnovelarchiver.domain.model.Story

/** Library-wide counts recorded around backup actions and in the activity-log export header. */
data class ActivityLogLibraryStats(
    val stories: Int,
    val chapters: Int,
    val downloaded: Int,
    val removedFlagged: Int,
    val patreonCopies: Int,
    val legacyArchives: Int,
) {
    fun fields(prefix: String): List<Pair<String, Any?>> =
        listOf(
            "${prefix}Stories" to stories,
            "${prefix}Chapters" to chapters,
            "${prefix}Downloaded" to downloaded,
            "${prefix}RemovedFlagged" to removedFlagged,
        )

    companion object {
        fun of(library: Collection<Story>): ActivityLogLibraryStats =
            ActivityLogLibraryStats(
                stories = library.size,
                chapters = library.sumOf { it.chapters.size },
                downloaded = library.sumOf { story -> story.chapters.count { it.downloaded } },
                removedFlagged = library.sumOf { story -> story.chapters.count { it.isRemovedFromSource } },
                patreonCopies = library.sumOf { story -> story.chapters.count { it.isPatreonCopy } },
                legacyArchives = library.count { it.isArchived == true },
            )
    }
}
