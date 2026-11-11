package com.vinicius741.webnovelarchiver.feature.library

/** Pure rules for the Library's multi-select mode. Selection mode is active while the set is non-empty. */
object LibrarySelectionPlanning {
    fun toggle(
        selected: Set<String>,
        id: String,
    ): Set<String> = if (id in selected) selected - id else selected + id

    /** Selects every visible novel, or clears just those when they are all already selected. */
    fun toggleAll(
        selected: Set<String>,
        visibleIds: List<String>,
    ): Set<String> =
        if (visibleIds.isNotEmpty() &&
            selected.containsAll(visibleIds)
        ) {
            selected - visibleIds.toSet()
        } else {
            selected + visibleIds
        }

    /** Drops ids whose novels no longer exist (deleted or restored away while the mode was open). */
    fun prune(
        selected: Set<String>,
        libraryIds: Collection<String>,
    ): Set<String> = if (selected.isEmpty()) selected else selected.intersect(libraryIds.toSet())

    fun title(count: Int): String = "$count selected"

    fun screenTitle(selectedCount: Int): String = if (selectedCount > 0) title(selectedCount) else "Library"

    fun screenSubtitle(
        totalNovels: Int,
        selectedCount: Int,
    ): String? =
        when {
            totalNovels == 0 || selectedCount > 0 -> null
            else -> "$totalNovels novel${if (totalNovels == 1) "" else "s"}"
        }

    fun deletePrompt(
        count: Int,
        removedFromSourceChapters: Int = 0,
    ): String = (if (count == 1) "Delete this novel?" else "Delete $count novels?") + removedChaptersWarning(removedFromSourceChapters)

    /**
     * Extra delete warning for novels holding chapters their source no longer lists: the local copy
     * is the only one left, so deleting the novel loses that text for good.
     */
    fun removedChaptersWarning(removedFromSourceChapters: Int): String =
        if (removedFromSourceChapters <= 0) {
            ""
        } else {
            val noun = if (removedFromSourceChapters == 1) "chapter" else "chapters"
            "\n\nThis includes $removedFromSourceChapters $noun the source no longer lists. " +
                "Your copy is the only one left and can't be downloaded again."
        }
}
