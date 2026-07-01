package com.vinicius741.webnovelarchiver.feature.library

import com.vinicius741.webnovelarchiver.domain.model.PatreonStats
import com.vinicius741.webnovelarchiver.domain.model.Story
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryQueryTest {
    @Test
    fun filtersBySourceNameAndContentTagsTogether() {
        val stories =
            listOf(
                story("rr-action", "Royal Action", "https://www.royalroad.com/fiction/1/story", tags = mutableListOf("Action")),
                story("rr-romance", "Royal Romance", "https://www.royalroad.com/fiction/2/story", tags = mutableListOf("Romance")),
                story("sh-action", "Hub Action", "https://www.scribblehub.com/series/3/story", tags = mutableListOf("Action")),
            )

        val filtered =
            LibraryQuery.filterAndSort(
                stories = stories,
                searchQuery = "",
                selectedTabId = "__all__",
                selectedTags = setOf("RoyalRoad", "Action"),
                sortOption = "title",
                sortAscending = true,
            )

        assertEquals(listOf("rr-action"), filtered.map { it.id })
    }

    @Test
    fun defaultSortUsesMostRecentOfLastUpdatedAndDateAddedDescending() {
        val stories =
            listOf(
                story("old", "Old", lastUpdated = 100, dateAdded = 500),
                story("new-update", "New Update", lastUpdated = 900, dateAdded = 100),
                story("new-add", "New Add", lastUpdated = null, dateAdded = 700),
            )

        val sorted = LibraryQuery.filterAndSort(stories, "", "__all__", emptySet(), "default", sortAscending = false)

        assertEquals(listOf("new-update", "new-add", "old"), sorted.map { it.id })
    }

    @Test
    fun scoreSortParsesNumericScoresDescending() {
        val stories =
            listOf(
                story("low", "Low", score = "3.50 / 5"),
                story("none", "None", score = null),
                story("high", "High", score = "4.75"),
            )

        val sorted = LibraryQuery.filterAndSort(stories, "", "__all__", emptySet(), "score", sortAscending = false)

        assertEquals(listOf("high", "low", "none"), sorted.map { it.id })
    }

    @Test
    fun patreonMonthlySortUsesHighestEarningsDescending() {
        val stories =
            listOf(
                story("low", "Low").withPatreonStats(paidMembers = 50, monthlyUsdCents = 25_000),
                story("none", "None"),
                story("high", "High").withPatreonStats(paidMembers = 12, monthlyUsdCents = 100_000),
            )

        val sorted = LibraryQuery.filterAndSort(stories, "", "__all__", emptySet(), "patreonMonthly", sortAscending = false)

        assertEquals(listOf("high", "low", "none"), sorted.map { it.id })
    }

    @Test
    fun patreonMembersSortUsesHighestPaidMembersDescending() {
        val stories =
            listOf(
                story("few", "Few").withPatreonStats(paidMembers = 2, monthlyUsdCents = 100_000),
                story("many", "Many").withPatreonStats(paidMembers = 200, monthlyUsdCents = 10_000),
                story("none", "None"),
            )

        val sorted = LibraryQuery.filterAndSort(stories, "", "__all__", emptySet(), "patreonMembers", sortAscending = false)

        assertEquals(listOf("many", "few", "none"), sorted.map { it.id })
    }

    @Test
    fun availableFilterLabelsReturnPopularSourcesBeforeTags() {
        val stories =
            listOf(
                story("rr1", "A", "https://www.royalroad.com/fiction/1/story", tags = mutableListOf("Action")),
                story("rr2", "B", "https://www.royalroad.com/fiction/2/story", tags = mutableListOf("Action", "Fantasy")),
                story("sh1", "C", "https://www.scribblehub.com/series/3/story", tags = mutableListOf("Fantasy")),
            )

        val labels = LibraryQuery.availableFilterLabels(stories, "__all__")

        assertEquals(listOf("RoyalRoad", "Scribble Hub", "Action", "Fantasy"), labels)
    }

    @Test
    fun availableFilterLabelsFollowTheActiveTab() {
        // Two custom tabs hold disjoint sources/tags; "All" should surface the union, a specific
        // tab only its own. Mirrors the legacy RN `matchesTab` gate on tag collection.
        val stories =
            listOf(
                story("rr-action", "Royal Action", "https://www.royalroad.com/fiction/1/story", tags = mutableListOf("Action"), tabId = "tab-1"),
                story("rr-fantasy", "Royal Fantasy", "https://www.royalroad.com/fiction/2/story", tags = mutableListOf("Fantasy"), tabId = "tab-1"),
                story("sh-romance", "Hub Romance", "https://www.scribblehub.com/series/3/story", tags = mutableListOf("Romance"), tabId = "tab-2"),
            )

        val allLabels = LibraryQuery.availableFilterLabels(stories, "__all__")
        assertEquals(listOf("RoyalRoad", "Scribble Hub", "Action", "Fantasy", "Romance"), allLabels)

        val tab1Labels = LibraryQuery.availableFilterLabels(stories, "tab-1")
        assertEquals(listOf("RoyalRoad", "Action", "Fantasy"), tab1Labels)

        val tab2Labels = LibraryQuery.availableFilterLabels(stories, "tab-2")
        assertEquals(listOf("Scribble Hub", "Romance"), tab2Labels)
    }

    private fun story(
        id: String,
        title: String,
        sourceUrl: String = "https://www.royalroad.com/fiction/1/story",
        tags: MutableList<String>? = null,
        score: String? = null,
        lastUpdated: Long? = null,
        dateAdded: Long? = null,
        tabId: String? = null,
    ): Story =
        Story(
            id = id,
            title = title,
            author = "Author",
            sourceUrl = sourceUrl,
            tags = tags,
            score = score,
            lastUpdated = lastUpdated,
            dateAdded = dateAdded,
            tabId = tabId,
        )

    private fun Story.withPatreonStats(
        paidMembers: Int,
        monthlyUsdCents: Long,
    ): Story =
        apply {
            patreonStats = PatreonStats(paidMembers = paidMembers, monthlyUsdCents = monthlyUsdCents)
        }
}
