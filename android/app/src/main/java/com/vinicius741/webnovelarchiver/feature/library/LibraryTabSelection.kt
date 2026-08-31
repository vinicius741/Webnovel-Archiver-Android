package com.vinicius741.webnovelarchiver.feature.library

import com.vinicius741.webnovelarchiver.domain.model.DisplayPreferences
import com.vinicius741.webnovelarchiver.domain.model.Tab

/**
 * Pure helpers for persisting/resolving the Library's selected tab across navigations and
 * restarts. Runtime values: `"__all__"` (synthetic All tab), a real [Tab.id], or `null`
 * (synthetic Unassigned tab). [ALL_TAB_ID] persists as-is so the Library reopens on the same tab;
 * stored values matching no live tab (deleted tab) fall back to [ALL_TAB_ID] via [resolve], so a
 * stale preference never renders an empty, un-selectable view.
 */
object LibraryTabSelection {
    /** Persisted sentinel for the synthetic "All" tab. Also the default when no tab is set. */
    const val ALL_TAB_ID = "__all__"

    /** Encodes a runtime selection into its persisted form ([DisplayPreferences.libraryTabId]). */
    fun encode(selectedTabId: String?): String = selectedTabId ?: ALL_TAB_ID

    /** Resolves a persisted id against the live tabs: blank/null and [ALL_TAB_ID] → [ALL_TAB_ID];
     *  an existing tab id → itself; a deleted tab id → [ALL_TAB_ID]; `"unassigned"` → `null` (only
     *  when unassigned stories exist, else [ALL_TAB_ID]). "unassigned" is never written today but
     *  is accepted on read. */
    fun resolve(
        stored: String?,
        tabs: List<Tab>,
        hasUnassignedStories: Boolean,
    ): String? {
        if (stored.isNullOrBlank()) return ALL_TAB_ID
        if (stored == ALL_TAB_ID) return ALL_TAB_ID
        if (stored == UNASSIGNED_TAB_ID) return if (hasUnassignedStories) null else ALL_TAB_ID
        return tabs.firstOrNull { it.id == stored }?.id ?: ALL_TAB_ID
    }

    private const val UNASSIGNED_TAB_ID = "unassigned"
}
