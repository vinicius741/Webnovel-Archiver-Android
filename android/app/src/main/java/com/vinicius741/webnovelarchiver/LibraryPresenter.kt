package com.vinicius741.webnovelarchiver

import com.vinicius741.webnovelarchiver.core.AppRepository
import com.vinicius741.webnovelarchiver.core.LibraryQuery
import com.vinicius741.webnovelarchiver.core.Story

/**
 * Incremental ViewModel/presenter for the Library screen (Maintainability M4). The screen keeps its
 * programmatic Views; this class takes ownership of *state derivation* (filtering, sorting, tab
 * selection) so the view builder becomes a pure function of [LibraryUiState] instead of re-running
 * queries + storage reads inline during render (Speed S3 — disk reads move off the render path).
 *
 * Reads from the cached [AppRepository.libraryFlow] snapshot rather than re-parsing every story JSON
 * on each render. Call [state] to derive the current view state; the screen binds that to views.
 */
data class LibraryStorySummary(
    val id: String,
    val title: String,
    val author: String,
    val coverUrl: String?,
    val downloadedChapters: Int,
    val totalChapters: Int,
    val isArchived: Boolean,
)

enum class LibrarySort(
    val label: String,
) {
    RECENT("Recently updated"),
    TITLE("Title (A–Z)"),
    DATE_ADDED("Date added"),
}

data class LibraryUiState(
    val stories: List<LibraryStorySummary>,
    val selectedTabId: String?,
    val query: String,
    val sort: LibrarySort,
)

class LibraryPresenter(
    private val repository: AppRepository,
) {
    /**
     * Derives the current library view state from the cached library snapshot + selection. Filtering
     * + sorting reuse the tested [LibraryQuery.filterAndSort] (operating on Story); the result is then
     * mapped to lightweight [LibraryStorySummary]s for the view builder.
     */
    fun state(
        selectedTabId: String?,
        query: String,
        selectedTags: Set<String>,
        sort: LibrarySort,
        sortAscending: Boolean,
    ): LibraryUiState {
        val sortOption =
            when (sort) {
                LibrarySort.TITLE -> "title"
                LibrarySort.DATE_ADDED -> "dateAdded"
                LibrarySort.RECENT -> "default"
            }
        val filtered =
            LibraryQuery
                .filterAndSort(
                    stories = repository.library(),
                    searchQuery = query,
                    selectedTabId = selectedTabId,
                    selectedTags = selectedTags,
                    sortOption = sortOption,
                    sortAscending = sortAscending,
                ).map { it.toSummary() }
        return LibraryUiState(
            stories = filtered,
            selectedTabId = selectedTabId,
            query = query,
            sort = sort,
        )
    }

    private fun Story.toSummary() =
        LibraryStorySummary(
            id = id,
            title = title,
            author = author,
            coverUrl = coverUrl,
            downloadedChapters = downloadedChapters,
            totalChapters = totalChapters,
            isArchived = isArchived == true,
        )
}
