package com.vinicius741.webnovelarchiver.feature.library

import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.navigation.runUiOperation
import com.vinicius741.webnovelarchiver.ui.layout.resolveScreenLayout
import com.vinicius741.webnovelarchiver.ui.screenMetrics

// The Library's saved display preferences: selected tab, sort, and list/cover layout.

/** Persists the selected tab. Transactional read-modify-write on the latest value; a failed
 *  save is reported instead of silently dropped. */
internal fun ScreenHost.persistLibraryTab(id: String?) {
    val encoded = LibraryTabSelection.encode(id)
    runUiOperation("save library tab") {
        repository.updateDisplayPreferences { latest ->
            if (latest.libraryTabId == encoded) latest else latest.copy(libraryTabId = encoded)
        }
    }
}

/** Persists the sort choice; same transactional discipline as [persistLibraryTab]. */
internal fun ScreenHost.persistLibrarySort(
    option: String,
    ascending: Boolean,
) {
    runUiOperation("save library sort") {
        repository.updateDisplayPreferences { latest ->
            if (latest.librarySortOption == option && latest.librarySortAscending == ascending) {
                latest
            } else {
                latest.copy(librarySortOption = option, librarySortAscending = ascending)
            }
        }
    }
}

/** The saved layout's columns and width cap for the live window. */
internal fun ScreenHost.currentLibraryGrid(): LibraryGridSpec {
    val metrics = screenMetrics()
    val viewMode = LibraryViewMode.fromStored(repository.getDisplayPreferences().libraryViewMode)
    return LibraryViewModePlanning.gridSpec(viewMode, resolveScreenLayout(metrics).numColumns, metrics.widthDp)
}

/**
 * Saves the new layout, then re-renders. Scroll offsets are pixel distances in the old layout's
 * geometry, so every tab starts at the top of the new one.
 */
internal fun ScreenHost.switchLibraryViewMode(next: LibraryViewMode) {
    runUiOperation("save library view") {
        repository.updateDisplayPreferences { latest -> latest.copy(libraryViewMode = next.stored) }
        libraryScreenState.tabScrollPositions.clear()
        showLibrary()
    }
}
