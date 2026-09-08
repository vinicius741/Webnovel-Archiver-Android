package com.vinicius741.webnovelarchiver.feature.library

import android.os.Bundle
import com.vinicius741.webnovelarchiver.navigation.LibraryScreenState

// Persists the Library's view memory across process death, alongside the route stack in
// MainActivity's saved state.

internal object LibraryScreenStatePersistence {
    private const val QUERY = "library.query"
    private const val TAGS = "library.tags"
    private const val FILTERS_EXPANDED = "library.filters_expanded"
    private const val SCROLL_KEYS = "library.scroll_keys"
    private const val SCROLL_VALUES = "library.scroll_values"

    fun save(
        state: LibraryScreenState,
        outState: Bundle,
    ) {
        outState.putString(QUERY, state.query)
        outState.putStringArrayList(TAGS, ArrayList(state.selectedTags))
        outState.putBoolean(FILTERS_EXPANDED, state.filtersExpanded)
        val scrolls = state.tabScrollPositions.entries.sortedBy { it.key }
        outState.putStringArrayList(SCROLL_KEYS, ArrayList(scrolls.map { it.key }))
        outState.putIntArray(SCROLL_VALUES, scrolls.map { it.value }.toIntArray())
    }

    fun restore(
        state: LibraryScreenState,
        saved: Bundle,
    ) {
        state.query = saved.getString(QUERY).orEmpty()
        state.selectedTags = saved.getStringArrayList(TAGS).orEmpty().toSet()
        state.filtersExpanded = saved.getBoolean(FILTERS_EXPANDED)
        val keys: List<String> = saved.getStringArrayList(SCROLL_KEYS) ?: emptyList()
        val values = saved.getIntArray(SCROLL_VALUES) ?: intArrayOf()
        keys.forEachIndexed { index, key ->
            values.getOrNull(index)?.let { state.tabScrollPositions[key] = it }
        }
    }
}
