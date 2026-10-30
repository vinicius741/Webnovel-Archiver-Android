package com.vinicius741.webnovelarchiver.navigation

import android.os.Bundle
import android.os.Parcelable
import androidx.core.os.BundleCompat
import androidx.recyclerview.widget.RecyclerView

/**
 * View state remembered per history entry, keyed by [AppRoute.stableKey], so Back returns to a
 * screen as the user left it. Entries are dropped once their route leaves the history
 * ([retainOnly]), so opening a screen anew starts at the top.
 */
class RouteViewMemory {
    /** Body offsets of `scrollable` screens. */
    val scrollPositions: MutableMap<String, Int> = mutableMapOf()

    /** Layout-manager state of each screen's main list (the Details chapters, Updates, Downloads). */
    val listStates: MutableMap<String, Parcelable> = mutableMapOf()

    /** In-screen search text, e.g. the Details chapter search. */
    val queries: MutableMap<String, String> = mutableMapOf()

    /** The rendered screen's main list, captured into [listStates] when the screen is replaced. */
    var trackedList: RecyclerView? = null

    fun retainOnly(keys: Set<String>) {
        scrollPositions.keys.retainAll(keys)
        listStates.keys.retainAll(keys)
        queries.keys.retainAll(keys)
    }

    fun save(state: Bundle) {
        val scrolls = scrollPositions.entries.sortedBy { it.key }
        state.putStringArrayList(STATE_SCROLL_KEYS, ArrayList(scrolls.map { it.key }))
        state.putIntArray(STATE_SCROLL_VALUES, scrolls.map { it.value }.toIntArray())
        state.putBundle(STATE_LIST_STATES, Bundle().apply { listStates.forEach { (key, value) -> putParcelable(key, value) } })
        state.putBundle(STATE_QUERIES, Bundle().apply { queries.forEach { (key, value) -> putString(key, value) } })
    }

    fun restore(state: Bundle) {
        val keys = state.getStringArrayList(STATE_SCROLL_KEYS).orEmpty()
        val values = state.getIntArray(STATE_SCROLL_VALUES) ?: intArrayOf()
        keys.forEachIndexed { index, key -> values.getOrNull(index)?.let { scrollPositions[key] = it } }
        state.getBundle(STATE_LIST_STATES)?.let { lists ->
            lists.classLoader = RecyclerView::class.java.classLoader
            lists.keySet().forEach { key ->
                BundleCompat.getParcelable(lists, key, Parcelable::class.java)?.let { listStates[key] = it }
            }
        }
        state.getBundle(STATE_QUERIES)?.let { saved ->
            saved.keySet().forEach { key -> saved.getString(key)?.let { queries[key] = it } }
        }
    }

    private companion object {
        const val STATE_SCROLL_KEYS = "navigation.scroll_keys"
        const val STATE_SCROLL_VALUES = "navigation.scroll_values"
        const val STATE_LIST_STATES = "navigation.list_states"
        const val STATE_QUERIES = "navigation.queries"
    }
}
