package com.vinicius741.webnovelarchiver.core

object LibraryQuery {
    fun filterAndSort(
        stories: List<Story>,
        searchQuery: String,
        selectedTabId: String?,
        selectedTags: Set<String>,
        sortOption: String,
        sortAscending: Boolean,
    ): List<Story> {
        val sourceNames = SourceRegistry.all().map { it.name }.toSet()
        val query = searchQuery.trim()
        val filtered = stories
            .filter { selectedTabId == "__all__" || it.tabId == selectedTabId }
            .filter { story ->
                query.isBlank() ||
                    story.title.contains(query, ignoreCase = true) ||
                    story.author.contains(query, ignoreCase = true)
            }
            .filter { story ->
                if (selectedTags.isEmpty()) return@filter true
                val storySourceName = SourceRegistry.getProvider(story.sourceUrl)?.name
                selectedTags.all { tag ->
                    if (tag in sourceNames) storySourceName == tag else story.tags.orEmpty().contains(tag)
                }
            }

        val sorted = when (sortOption) {
            "title" -> filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            "dateAdded" -> filtered.sortedBy { it.dateAdded ?: 0L }
            "lastUpdated", "updated" -> filtered.sortedBy { it.lastUpdated ?: 0L }
            "totalChapters" -> filtered.sortedBy { it.totalChapters }
            "score" -> filtered.sortedBy { parseScore(it.score) }
            "progress" -> filtered.sortedBy { story ->
                if (story.totalChapters == 0) 0.0 else story.downloadedChapters.toDouble() / story.totalChapters.toDouble()
            }
            "default" -> filtered.sortedBy { maxOf(it.lastUpdated ?: 0L, it.dateAdded ?: 0L) }
            else -> filtered.sortedBy { maxOf(it.lastUpdated ?: 0L, it.dateAdded ?: 0L) }
        }
        return if (sortAscending) sorted else sorted.asReversed()
    }

    fun availableFilterLabels(stories: List<Story>, selectedTabId: String?): List<String> =
        availableFilterLabelsWithCounts(stories, selectedTabId).map { it.first }

    fun availableFilterLabelsWithCounts(stories: List<Story>, selectedTabId: String?): List<Pair<String, Int>> {
        val visibleStories = stories.filter { selectedTabId == "__all__" || it.tabId == selectedTabId }
        val sources = visibleStories
            .mapNotNull { SourceRegistry.getProvider(it.sourceUrl)?.name }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key to it.value }
        val tags = visibleStories
            .flatMap { it.tags.orEmpty() }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key to it.value }
        return sources + tags
    }

    private fun parseScore(score: String?): Double {
        if (score.isNullOrBlank()) return 0.0
        return Regex("(\\d+\\.?\\d*)").find(score)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
    }
}
