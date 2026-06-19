package com.vinicius741.webnovelarchiver.feature.library

import com.vinicius741.webnovelarchiver.domain.model.DisplayPreferences
import com.vinicius741.webnovelarchiver.domain.model.Tab

/**
 * Pure helpers for persisting and resolving the Library's selected tab across screen navigations
 * and app restarts. The Library UI uses three kinds of selection values at runtime:
 *  - `"__all__"` — the synthetic "All" tab (see [ALL_TAB_ID])
 *  - a real [Tab.id] — a user-created tab
 *  - `null` — the synthetic "Unassigned" tab (stories whose `tabId == null`)
 *
 * [ALL_TAB_ID] is persisted as-is so re-opening the Library lands back on the same tab the user
 * left on. Stored values that no longer match any live tab (e.g. a deleted tab's id) fall back to
 * [ALL_TAB_ID] via [resolve], so a stale preference never renders an empty, un-selectable view.
 */
object LibraryTabSelection {
    /** Persisted sentinel for the synthetic "All" tab. Also the default when no tab is set. */
    const val ALL_TAB_ID = "__all__"

    /** Encodes a runtime selection into its persisted form (the value stored in [DisplayPreferences.libraryTabId]). */
    fun encode(selectedTabId: String?): String = selectedTabId ?: ALL_TAB_ID

    /**
     * Resolves a persisted tab id against the live tabs, returning a runtime selection that is safe
     * to render. Rules:
     *  - blank/`null` → [ALL_TAB_ID] (first launch / never set)
     *  - [ALL_TAB_ID] → [ALL_TAB_ID]
     *  - a real tab id that exists → that id
     *  - a real tab id that no longer exists (deleted) → [ALL_TAB_ID]
     *  - `"unassigned"` persisted value → `null` (the "Unassigned" tab; only valid when stories are
     *    actually unassigned, otherwise [ALL_TAB_ID])
     *
     * Note: `"unassigned"` is never written by the app today (the Unassigned tab uses runtime `null`),
     * but it is accepted on read for forward/backward safety.
     */
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
