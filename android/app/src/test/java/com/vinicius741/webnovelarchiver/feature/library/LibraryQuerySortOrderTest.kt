package com.vinicius741.webnovelarchiver.feature.library

import com.vinicius741.webnovelarchiver.domain.model.PatreonRawStats
import com.vinicius741.webnovelarchiver.domain.model.SourceMetadata
import com.vinicius741.webnovelarchiver.domain.model.Story
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the exact order of every sort option, ascending and descending, over a fixture with ties on
 * every key. Ascending is a stable sort (ties keep library order); descending is that list reversed,
 * so ties come out in reverse library order. Library order here is a, b, c, d, e.
 */
class LibraryQuerySortOrderTest {
    private val stories =
        listOf(
            story("a", "beta", dateAdded = 300, lastUpdated = 200, total = 10, downloaded = 5, score = "4.5 / 5")
                .withPatreon(PatreonRawStats(paidMembers = 10, exactMonthlyUsdCents = 5_000)),
            story("b", "Alpha", dateAdded = null, lastUpdated = 200, total = 10, downloaded = 10, score = null),
            story("c", "alpha", dateAdded = 100, lastUpdated = null, total = 0, downloaded = 0, score = "4.50")
                .withPatreon(PatreonRawStats(paidMembers = 10, exactMonthlyUsdCents = 9_000)),
            // 100 public members, no paid count and no tiers: 70 estimated members, no estimable amount.
            story("d", "Gamma", dateAdded = 0, lastUpdated = 50, total = 20, downloaded = 10, score = "unrated")
                .withPatreon(PatreonRawStats(totalMembers = 100)),
            story("e", "delta", dateAdded = 300, lastUpdated = 0, total = 5, downloaded = 1, score = "Rated 3 of 5")
                .withPatreon(PatreonRawStats(paidMembers = 0, exactMonthlyUsdCents = 5_000)),
        )

    @Test
    fun titleSortIsCaseInsensitiveAndStable() = assertOrder("title", ascending = "b c a e d")

    @Test
    fun dateAddedSortTreatsMissingAsZero() = assertOrder("dateAdded", ascending = "b d c a e")

    @Test
    fun lastUpdatedSortTreatsMissingAsZero() = assertOrder("lastUpdated", ascending = "c e d a b")

    @Test
    fun updatedDefaultAndUnknownOptionsSortLikeLastUpdated() {
        listOf("updated", "default", "no-such-option").forEach { option ->
            assertOrder(option, ascending = "c e d a b")
        }
    }

    @Test
    fun totalChaptersSort() = assertOrder("totalChapters", ascending = "c e a b d")

    @Test
    fun scoreSortUsesTheFirstNumberAndTreatsUnparsableAsZero() = assertOrder("score", ascending = "b d e a c")

    @Test
    fun patreonMonthlySortTreatsMissingOrUnestimableAmountsAsZero() = assertOrder("patreonMonthly", ascending = "b d a e c")

    @Test
    fun patreonMembersSortIncludesEstimatedMembers() = assertOrder("patreonMembers", ascending = "b e a c d")

    @Test
    fun progressSortTreatsNoChaptersAsZero() = assertOrder("progress", ascending = "c e a d b")

    @Test
    fun sortAppliesOnlyToTheStoriesThatPassTheFilters() {
        val sorted = LibraryQuery.filterAndSort(stories, " ALPHA ", LibraryTabSelection.ALL_TAB_ID, emptySet(), "score", false)

        assertEquals(listOf("c", "b"), sorted.map { it.id })
    }

    @Test
    fun tagFilterMatchesSourceNamesAndEveryClassificationFacetTogether() {
        val tagged =
            listOf(
                story("rr", "Royal").copy(
                    tags = mutableListOf("Action"),
                    sourceMetadata = SourceMetadata(genres = mutableListOf("Fantasy"), sourceType = "Original"),
                ),
                story("rr-other", "Royal other").copy(tags = mutableListOf("Action")),
                story("sh", "Hub", sourceUrl = "https://www.scribblehub.com/series/3/story").copy(
                    tags = mutableListOf("Action"),
                    sourceMetadata = SourceMetadata(genres = mutableListOf("Fantasy"), sourceType = "Original"),
                ),
            )

        fun matching(tags: Set<String>) =
            LibraryQuery.filterAndSort(tagged, "", LibraryTabSelection.ALL_TAB_ID, tags, "title", true).map { it.id }

        assertEquals(listOf("rr"), matching(setOf("RoyalRoad", "Action", "Fantasy", "Original")))
        assertEquals(listOf("sh"), matching(setOf("Scribble Hub", "Fantasy")))
        assertEquals(emptyList<String>(), matching(setOf("RoyalRoad", "Scribble Hub")))
        assertEquals(emptyList<String>(), matching(setOf("Fantasy", "Horror")))
    }

    private fun assertOrder(
        sortOption: String,
        ascending: String,
    ) {
        val expected = ascending.split(" ")
        val asc = LibraryQuery.filterAndSort(stories, "", LibraryTabSelection.ALL_TAB_ID, emptySet(), sortOption, sortAscending = true)
        val desc = LibraryQuery.filterAndSort(stories, "", LibraryTabSelection.ALL_TAB_ID, emptySet(), sortOption, sortAscending = false)
        assertEquals("$sortOption ascending", expected, asc.map { it.id })
        assertEquals("$sortOption descending", expected.reversed(), desc.map { it.id })
    }

    private fun story(
        id: String,
        title: String,
        sourceUrl: String = "https://www.royalroad.com/fiction/1/story",
        dateAdded: Long? = null,
        lastUpdated: Long? = null,
        total: Int = 0,
        downloaded: Int = 0,
        score: String? = null,
    ): Story =
        Story(
            id = id,
            title = title,
            author = "Author",
            sourceUrl = sourceUrl,
            score = score,
            lastUpdated = lastUpdated,
            dateAdded = dateAdded,
            totalChapters = total,
            downloadedChapters = downloaded,
        )

    private fun Story.withPatreon(stats: PatreonRawStats): Story = apply { patreonStats = stats }
}
