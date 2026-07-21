package com.vinicius741.webnovelarchiver.feature.library

/**
 * Pure sort-key helpers for the Library filter bar, split out of [LibraryFilters] so the
 * deterministic sort normalization/labeling can be unit-tested and reused by both the sort chip
 * and the sort dialog. No Android dependencies — matches the repo's `*Planning` convention for
 * pure domain logic.
 */
object LibraryFiltersPlanning {
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
