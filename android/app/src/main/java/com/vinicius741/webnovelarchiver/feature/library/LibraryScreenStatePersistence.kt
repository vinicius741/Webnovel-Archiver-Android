package com.vinicius741.webnovelarchiver.feature.library

import android.os.Bundle
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenModelStore

/**
 * Persists the Library's view memory ([LibrarySavedState]) across process death, alongside the
 * route stack in MainActivity's saved state. It restores even when the route stack cannot: the root
 * Library uses it too. The Library's model takes it when it is first created.
 */
internal object LibraryScreenStatePersistence {
    private const val QUERY = "library.query"
    private const val TAGS = "library.tags"
    private const val SCROLL_KEYS = "library.scroll_keys"
    private const val SCROLL_VALUES = "library.scroll_values"
    private val modelKey = AppRoute.Library.stableKey

    /** Saves the live model's memory, or the restored memory no model has taken yet. */
    fun save(
        models: ScreenModelStore,
        outState: Bundle,
    ) {
        val state = models.find<LibraryScreenModel>(modelKey)?.savedState() ?: models.peekRestored<LibrarySavedState>(modelKey) ?: return
        outState.putString(QUERY, state.query)
        outState.putStringArrayList(TAGS, ArrayList(state.selectedTags))
        val scrolls = state.tabScroll.entries.sortedBy { it.key }
        outState.putStringArrayList(SCROLL_KEYS, ArrayList(scrolls.map { it.key }))
        outState.putIntArray(SCROLL_VALUES, scrolls.map { it.value }.toIntArray())
    }

    fun restore(
        models: ScreenModelStore,
        saved: Bundle,
    ) {
        val keys: List<String> = saved.getStringArrayList(SCROLL_KEYS) ?: emptyList()
        val values = saved.getIntArray(SCROLL_VALUES) ?: intArrayOf()
        val state =
            LibrarySavedState(
                query = saved.getString(QUERY).orEmpty(),
                selectedTags = saved.getStringArrayList(TAGS).orEmpty().toSet(),
                tabScroll = keys.withIndex().mapNotNull { (index, key) -> values.getOrNull(index)?.let { key to it } }.toMap(),
            )
        models.restore(modelKey, state)
    }
}
