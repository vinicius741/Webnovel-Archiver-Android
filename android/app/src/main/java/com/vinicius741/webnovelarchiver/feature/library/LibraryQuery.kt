package com.vinicius741.webnovelarchiver.feature.library

import com.vinicius741.webnovelarchiver.domain.metrics.PatreonEarningsPlanning
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.source.SourceRegistry

object LibraryQuery {
    private val scoreNumber = Regex("(\\d+\\.?\\d*)")

    /** The registered providers are fixed at compile time, so their names are collected once. */
    private val sourceNames: Set<String> by lazy { SourceRegistry.all().mapTo(HashSet()) { it.name } }

    fun filterAndSort(
        stories: List<Story>,
        searchQuery: String,
        selectedTabId: String?,
        selectedTags: Set<String>,
        sortOption: String,
        sortAscending: Boolean,
    ): List<Story> {
        val query = searchQuery.trim()
        val filtered =
            stories.filter { story ->
                (selectedTabId == LibraryTabSelection.ALL_TAB_ID || story.tabId == selectedTabId) &&
                    matchesFilters(story, query, selectedTags)
            }

        val sorted = sortStories(filtered, sortOption)
        return if (sortAscending) sorted else sorted.asReversed()
    }

    fun availableFilterLabels(
        stories: List<Story>,
        selectedTabId: String?,
        searchQuery: String = "",
        selectedTags: Set<String> = emptySet(),
    ): List<String> = availableFilterLabelsWithCounts(stories, selectedTabId, searchQuery, selectedTags).map { it.first }

    fun availableFilterLabelsWithCounts(
        stories: List<Story>,
        selectedTabId: String?,
        searchQuery: String = "",
        selectedTags: Set<String> = emptySet(),
    ): List<Pair<String, Int>> {
        val (sources, tags) = availableFilterGroups(stories, selectedTabId, searchQuery, selectedTags)
        return sources + tags
    }

    /**
     * Splits the available filter labels into two groups so the UI can render source filters
     * distinctly from genre tags (audit L4): [sourceNamesWithCounts, tagsWithCounts].
     * Sources come from registered providers; tags come from each story's `tags`.
     *
     * The chip set follows the current filter context: when a search query or tag selection is
     * active, only labels that exist among the stories still passing those filters are returned, so
     * the UI offers only combinations that actually match something. If the active filters match
     * nothing at all, the tab's full label set is returned instead so the user can always see (and
     * un-select) their active chips rather than the chip row vanishing.
     */
    fun availableFilterGroups(
        stories: List<Story>,
        selectedTabId: String?,
        searchQuery: String = "",
        selectedTags: Set<String> = emptySet(),
    ): Pair<List<Pair<String, Int>>, List<Pair<String, Int>>> {
        var visibleStories = stories.filter { selectedTabId == LibraryTabSelection.ALL_TAB_ID || it.tabId == selectedTabId }
        if (searchQuery.isNotBlank() || selectedTags.isNotEmpty()) {
            val query = searchQuery.trim()
            val narrowed = visibleStories.filter { matchesFilters(it, query, selectedTags) }
            if (narrowed.isNotEmpty()) visibleStories = narrowed
        }
        val sources =
            visibleStories
                .mapNotNull { SourceRegistry.getProvider(it.sourceId, it.sourceUrl)?.name }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .map { it.key to it.value }
        val tags =
            visibleStories
                .flatMap(::classificationFilterValues)
                .filter { it.isNotBlank() }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .map { it.key to it.value }
        return sources to tags
    }

    /** [query] is the already-trimmed search text. */
    private fun matchesFilters(
        story: Story,
        query: String,
        selectedTags: Set<String>,
    ): Boolean {
        val queryMatches =
            query.isBlank() ||
                story.title.contains(query, ignoreCase = true) ||
                story.author.contains(query, ignoreCase = true)
        if (!queryMatches) return false
        if (selectedTags.isEmpty()) return true
        val storySourceName = SourceRegistry.getProvider(story.sourceId, story.sourceUrl)?.name
        return selectedTags.all { tag ->
            if (tag in sourceNames) storySourceName == tag else hasClassification(story, tag)
        }
    }

    /**
     * Typed source classifications remain available through the existing tag-chip surface. Generic
     * tags are retained for legacy stories and provider compatibility; the typed facets fill gaps
     * without adding another library-card row or a second filter UI.
     */
    private fun classificationFilterValues(story: Story): List<String> =
        (
            story.tags.orEmpty() +
                story.sourceMetadata.genres +
                story.sourceMetadata.fandoms +
                story.sourceMetadata.characters +
                listOfNotNull(story.sourceMetadata.sourceType, story.sourceMetadata.sourceCategory)
        ).distinct()

    /** `tag in classificationFilterValues(story)` without building the combined list; keep the two in step. */
    private fun hasClassification(
        story: Story,
        tag: String,
    ): Boolean {
        val metadata = story.sourceMetadata
        return story.tags?.contains(tag) == true ||
            tag in metadata.genres ||
            tag in metadata.fandoms ||
            tag in metadata.characters ||
            tag == metadata.sourceType ||
            tag == metadata.sourceCategory
    }

    private fun parseScore(score: String?): Double {
        if (score.isNullOrBlank()) return 0.0
        return scoreNumber
            .find(score)
            ?.groupValues
            ?.get(1)
            ?.toDoubleOrNull() ?: 0.0
    }

    private fun sortStories(
        stories: List<Story>,
        sortOption: String,
    ): List<Story> =
        when (sortOption) {
            "title" -> stories.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            "dateAdded" -> stories.sortedBy { it.dateAdded ?: 0L }
            "lastUpdated", "updated" -> stories.sortedBy { it.lastUpdated ?: 0L }
            "totalChapters" -> stories.sortedBy { it.totalChapters }
            "score" -> stories.sortedByPrecomputedKey { parseScore(it.score) }
            "patreonMonthly" ->
                stories.sortedByPrecomputedKey { it.patreonStats?.let(PatreonEarningsPlanning::estimate)?.monthlyUsdCents ?: 0L }
            "patreonMembers" ->
                stories.sortedByPrecomputedKey { it.patreonStats?.let(PatreonEarningsPlanning::estimate)?.paidMembers ?: 0 }
            "progress" -> stories.sortedBy { progressRatio(it) }
            // Smart is intentionally equivalent to Last Updated. A successful chapter sync updates
            // this timestamp, which promotes the synced story to the top in the default descending view.
            "default" -> stories.sortedBy { it.lastUpdated ?: 0L }
            else -> stories.sortedBy { it.lastUpdated ?: 0L }
        }

    /**
     * The same order as `sortedBy(key)` (a stable sort comparing keys with [compareValues]), but [key]
     * runs once per story instead of twice per comparison. For keys that parse or estimate.
     */
    private inline fun <K : Comparable<K>> List<Story>.sortedByPrecomputedKey(key: (Story) -> K): List<Story> =
        map { story -> story to key(story) }
            .sortedWith(compareBy { it.second })
            .map { it.first }

    private fun progressRatio(story: Story): Double =
        if (story.totalChapters == 0) {
            0.0
        } else {
            story.downloadedChapters.toDouble() / story.totalChapters.toDouble()
        }
}
