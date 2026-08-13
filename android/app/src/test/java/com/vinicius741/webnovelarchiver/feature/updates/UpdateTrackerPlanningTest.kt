package com.vinicius741.webnovelarchiver.feature.updates

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.SourceAvailability
import com.vinicius741.webnovelarchiver.domain.model.SourceSyncState
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.feature.library.LibraryFilterState
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateTrackerPlanningTest {
    @Test
    fun unavailableSummaryUsesCorrectPlural() {
        assertEquals(
            "1 unavailable novel will be skipped. Check manually from its details screen.",
            UpdateTrackerPlanning.unavailableSummary(1),
        )
        assertEquals(
            "2 unavailable novels will be skipped. Check manually from its details screen.",
            UpdateTrackerPlanning.unavailableSummary(2),
        )
    }

    @Test
    fun filterStoriesMatchesTitleOrAuthorWithoutChangingOrder() {
        val stories =
            listOf(
                story("b", title = "Second", author = "Matching Author"),
                story("a", title = "Matching Title", author = "Someone"),
                story("c", title = "Third", author = "Else"),
            )

        val filtered = UpdateTrackerPlanning.filterStories(stories, " matching ")

        assertEquals(listOf("b", "a"), filtered.map { it.id })
    }

    @Test
    fun normalizeFollowedIdsDropsMissingAndDuplicates() {
        val stories = listOf(story("a"), story("b"))

        val normalized = UpdateTrackerPlanning.normalizeFollowedIds(stories, listOf("missing", "b", "a", "b"))

        assertEquals(listOf("b", "a"), normalized)
    }

    @Test
    fun visibleFollowStoriesApplyLibraryFiltersAndKeepUnselectedHiddenWhenReviewing() {
        val stories =
            listOf(
                story("keep", title = "Alpha Keep", author = "Ann", tabId = "tab-a", tags = listOf("fantasy")),
                story("other-tab", title = "Alpha Other", author = "Ann", tabId = "tab-b", tags = listOf("fantasy")),
                story("other-tag", title = "Alpha Tag", author = "Ann", tabId = "tab-a", tags = listOf("romance")),
                story("unselected", title = "Alpha Unselected", author = "Ann", tabId = "tab-a", tags = listOf("fantasy")),
            )
        val filter =
            LibraryFilterState(
                query = "alpha",
                selectedTabId = "tab-a",
                selectedTags = setOf("fantasy"),
                sortOption = "title",
                sortAscending = true,
            )

        assertEquals(
            listOf("keep", "unselected"),
            UpdateTrackerPlanning
                .visibleFollowStories(
                    stories,
                    filter,
                    selectedIds = setOf("keep"),
                    showSelectedOnly = false,
                ).map { it.id },
        )
        assertEquals(
            listOf("keep"),
            UpdateTrackerPlanning.visibleFollowStories(stories, filter, selectedIds = setOf("keep"), showSelectedOnly = true).map { it.id },
        )
    }

    @Test
    fun clearingSearchKeepsSelectedIdsVisibleInTheUnfilteredList() {
        val stories =
            listOf(
                story("a", title = "Zebra Selected"),
                story("b", title = "Alpha Other"),
            )
        val selected = setOf("a")
        val searched =
            UpdateTrackerPlanning.visibleFollowStories(
                stories,
                LibraryFilterState(query = "zebra", sortOption = "title", sortAscending = true),
                selected,
                showSelectedOnly = false,
            )
        val cleared =
            UpdateTrackerPlanning.visibleFollowStories(
                stories,
                LibraryFilterState(query = "", sortOption = "title", sortAscending = true),
                selected,
                showSelectedOnly = false,
            )

        assertEquals(listOf("a"), searched.map { it.id })
        assertEquals(listOf("b", "a"), cleared.map { it.id })
        assertEquals(setOf("a"), selected)
    }

    @Test
    fun selectedReviewLabelAndEmptyCopyDescribeTheReviewToggle() {
        assertEquals("Selected (8)", UpdateTrackerPlanning.selectedReviewLabel(8, showingSelectedOnly = false))
        assertEquals("Show all", UpdateTrackerPlanning.selectedReviewLabel(8, showingSelectedOnly = true))
        assertEquals(
            "No selected novels" to "Select novels from the full list, or turn off the selected filter.",
            UpdateTrackerPlanning.followSelectionEmptyCopy(showSelectedOnly = true),
        )
        assertEquals(
            "No matches" to "Try clearing your search or filters.",
            UpdateTrackerPlanning.followSelectionEmptyCopy(showSelectedOnly = false),
        )
    }

    @Test
    fun followSelectionNovelsLabelShowsTotalOnlyWhenUnfiltered() {
        assertEquals("Novels (5)", UpdateTrackerPlanning.followSelectionNovelsLabel(5, 5))
        assertEquals("Novels (2 of 5)", UpdateTrackerPlanning.followSelectionNovelsLabel(2, 5))
        assertEquals("Novels (0 of 5)", UpdateTrackerPlanning.followSelectionNovelsLabel(0, 5))
    }

    @Test
    fun followableStoriesExcludeArchivedSnapshots() {
        val stories =
            listOf(
                story("live", title = "Live"),
                story("arch", title = "Live", archived = true),
            )

        assertEquals(listOf("live"), UpdateTrackerPlanning.followableStories(stories).map { it.id })
    }

    @Test
    fun normalizeFollowedIdsDropsArchivedStoryIds() {
        val stories =
            listOf(
                story("live"),
                story("arch", archived = true),
            )

        val normalized =
            UpdateTrackerPlanning.normalizeFollowedIds(stories, listOf("live", "arch", "missing"))

        assertEquals(listOf("live"), normalized)
    }

    @Test
    fun followedStoriesKeepFollowedOrder() {
        val stories = listOf(story("a", title = "A"), story("b", title = "B"))

        val followed = UpdateTrackerPlanning.followedStories(stories, listOf("b", "a"))

        assertEquals(listOf("B", "A"), followed.map { it.title })
    }

    @Test
    fun unavailableStoriesStayFollowedButAreSkippedByAutomaticSync() {
        val stories = listOf(story("available"), story("missing", unavailable = true))
        val followedIds = listOf("missing", "available")

        assertEquals(listOf("missing", "available"), UpdateTrackerPlanning.followedStories(stories, followedIds).map { it.id })
        assertEquals(listOf("available"), UpdateTrackerPlanning.syncableFollowedStories(stories, followedIds).map { it.id })
    }

    @Test
    fun updatedChaptersReturnPendingChaptersInStoryOrder() {
        val story =
            story(
                "story",
                chapters =
                    mutableListOf(
                        Chapter(id = "c1", title = "One"),
                        Chapter(id = "c2", title = "Two"),
                        Chapter(id = "c3", title = "Three"),
                    ),
                pending = mutableListOf("c3", "missing", "c1"),
            )

        val updates = UpdateTrackerPlanning.updatedChapters(story)

        assertEquals(listOf(0, 2), updates.map { it.index })
        assertEquals(listOf("One", "Three"), updates.map { it.chapter.title })
    }

    @Test
    fun updatedChaptersCanUseSyncResultIdsAfterPendingIdsAreCleared() {
        val story =
            story(
                "story",
                chapters =
                    mutableListOf(
                        Chapter(id = "c1", title = "One", downloaded = true),
                        Chapter(id = "c2", title = "Two", downloaded = true),
                    ),
                pending = null,
            )

        val updates = UpdateTrackerPlanning.updatedChapters(story, chapterIds = listOf("c2"))

        assertEquals(listOf(1), updates.map { it.index })
        assertEquals(listOf("Two"), updates.map { it.chapter.title })
    }

    @Test
    fun updatedCountsUseSyncResultIdsWhenAvailable() {
        val stories =
            listOf(
                story("a", chapters = mutableListOf(Chapter(id = "a1"))),
                story("b", chapters = mutableListOf(Chapter(id = "b1")), pending = mutableListOf("b1")),
            )

        val count = UpdateTrackerPlanning.updatedChapterCount(stories, mapOf("a" to listOf("a1")))

        assertEquals(2, count)
    }

    @Test
    fun syncBatchesCapConcurrentStoryGroups() {
        val stories = listOf(story("a"), story("b"), story("c"), story("d"), story("e"))

        val batches = UpdateTrackerPlanning.syncBatches(stories, maxConcurrent = 2)

        assertEquals(
            listOf(listOf("a", "b"), listOf("c", "d"), listOf("e")),
            batches.map { batch -> batch.map { it.id } },
        )
    }

    @Test
    fun syncBatchesTreatInvalidConcurrencyAsSingleWorker() {
        val stories = listOf(story("a"), story("b"))

        val batches = UpdateTrackerPlanning.syncBatches(stories, maxConcurrent = 0)

        assertEquals(
            listOf(listOf("a"), listOf("b")),
            batches.map { batch -> batch.map { it.id } },
        )
    }

    private fun story(
        id: String,
        title: String = id,
        author: String = "",
        chapters: MutableList<Chapter> = mutableListOf(),
        pending: MutableList<String>? = null,
        archived: Boolean = false,
        unavailable: Boolean = false,
        tabId: String? = null,
        tags: List<String> = emptyList(),
    ): Story =
        Story(
            id = id,
            title = title,
            author = author,
            sourceUrl = "https://example.com/$id",
            chapters = chapters,
            pendingNewChapterIds = pending,
            isArchived = archived.takeIf { it },
            tabId = tabId,
            tags = tags.toMutableList().takeIf { it.isNotEmpty() },
            sourceSyncState =
                SourceSyncState(
                    availability = if (unavailable) SourceAvailability.not_found else SourceAvailability.available,
                ),
        )
}
