package com.vinicius741.webnovelarchiver.feature.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryFiltersPlanningTest {
    @Test
    fun normalizeSortOptionMapsLegacyUpdatedAlias() {
        assertEquals("lastUpdated", LibraryFiltersPlanning.normalizeSortOption("updated"))
    }

    @Test
    fun normalizeSortOptionPassesThroughCanonicalKeys() {
        assertEquals("title", LibraryFiltersPlanning.normalizeSortOption("title"))
        assertEquals("lastUpdated", LibraryFiltersPlanning.normalizeSortOption("lastUpdated"))
        assertEquals("score", LibraryFiltersPlanning.normalizeSortOption("score"))
    }

    @Test
    fun defaultDirectionForTitleIsAscending() {
        assertEquals(true, LibraryFiltersPlanning.defaultDirectionFor("title"))
    }

    @Test
    fun defaultDirectionForNonTitleIsDescending() {
        assertEquals(false, LibraryFiltersPlanning.defaultDirectionFor("lastUpdated"))
        assertEquals(false, LibraryFiltersPlanning.defaultDirectionFor("score"))
    }

    @Test
    fun sortOptionLabelShowsShortHumanLabelForEachKey() {
        assertEquals("Title", LibraryFiltersPlanning.sortOptionLabel("title"))
        assertEquals("Updated", LibraryFiltersPlanning.sortOptionLabel("lastUpdated"))
        assertEquals("Added", LibraryFiltersPlanning.sortOptionLabel("dateAdded"))
        assertEquals("Chapters", LibraryFiltersPlanning.sortOptionLabel("totalChapters"))
        assertEquals("Score", LibraryFiltersPlanning.sortOptionLabel("score"))
        assertEquals("Patrons", LibraryFiltersPlanning.sortOptionLabel("patreonMembers"))
        assertEquals("Default", LibraryFiltersPlanning.sortOptionLabel("default"))
    }

    @Test
    fun sortOptionLabelNormalizesLegacyAliasBeforeLabeling() {
        assertEquals("Updated", LibraryFiltersPlanning.sortOptionLabel("updated"))
    }
}
