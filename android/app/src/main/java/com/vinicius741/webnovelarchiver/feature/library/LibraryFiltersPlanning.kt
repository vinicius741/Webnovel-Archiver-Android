package com.vinicius741.webnovelarchiver.feature.library

import com.vinicius741.webnovelarchiver.domain.model.Story

/** Pure rules for Library filtering and sort controls. */
object LibraryFiltersPlanning {
    fun hasActiveFilters(
        query: CharSequence,
        selectedTags: Set<String>,
    ): Boolean = query.isNotBlank() || selectedTags.isNotEmpty()

    /** Default ascending/descending direction when a user picks a *new* sort option. */
    fun defaultDirectionFor(option: String): Boolean =
        when (option) {
            "title" -> true
            else -> false
        }

    /**
     * Maps legacy sort keys onto the dialog's option keys. LibraryScreen historically defaulted to
     * `"updated"` while the dialog lists `"lastUpdated"`; without this, no row ever matched and the
     * selected check/highlight never appeared.
     */
    fun normalizeSortOption(option: String): String =
        when (option) {
            "updated" -> "lastUpdated"
            else -> option
        }

    /** Short human label for a sort option key, shown on the Library sort chip. */
    fun sortOptionLabel(option: String): String =
        when (normalizeSortOption(option)) {
            "title" -> "Title"
            "lastUpdated" -> "Updated"
            "dateAdded" -> "Added"
            "totalChapters" -> "Chapters"
            "score" -> "Score"
            "patreonMonthly" -> "Patreon ${'$'}"
            "patreonMembers" -> "Patrons"
            "default" -> "Default"
            else -> "Default"
        }
}

/** Shared mutable UI snapshot for Library filtering. */
data class LibraryFilterState(
    val query: String = "",
    val selectedTabId: String? = LibraryTabSelection.ALL_TAB_ID,
    val selectedTags: Set<String> = emptySet(),
    val sortOption: String = "lastUpdated",
    val sortAscending: Boolean = false,
) {
    fun clearFilters(): LibraryFilterState = copy(query = "", selectedTags = emptySet())

    fun applyTo(stories: List<Story>): List<Story> =
        LibraryQuery.filterAndSort(
            stories,
            query,
            selectedTabId,
            selectedTags,
            sortOption,
            sortAscending,
        )
}
