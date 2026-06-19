package com.vinicius741.webnovelarchiver.feature.library

import com.vinicius741.webnovelarchiver.domain.model.Tab
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryTabSelectionTest {
    private val tabs =
        listOf(
            Tab(id = "reading", name = "Reading", order = 0),
            Tab(id = "done", name = "Done", order = 1),
        )

    @Test
    fun resolveBlankOrNullFallsBackToAllTab() {
        assertEquals(LibraryTabSelection.ALL_TAB_ID, LibraryTabSelection.resolve(null, tabs, hasUnassignedStories = true))
        assertEquals(LibraryTabSelection.ALL_TAB_ID, LibraryTabSelection.resolve("", tabs, hasUnassignedStories = true))
        assertEquals(LibraryTabSelection.ALL_TAB_ID, LibraryTabSelection.resolve("   ", tabs, hasUnassignedStories = true))
    }

    @Test
    fun resolveAllStaysAll() {
        assertEquals(
            LibraryTabSelection.ALL_TAB_ID,
            LibraryTabSelection.resolve(LibraryTabSelection.ALL_TAB_ID, tabs, hasUnassignedStories = false),
        )
    }

    @Test
    fun resolveExistingTabIdReturnsIt() {
        assertEquals("reading", LibraryTabSelection.resolve("reading", tabs, hasUnassignedStories = false))
        assertEquals("done", LibraryTabSelection.resolve("done", tabs, hasUnassignedStories = false))
    }

    @Test
    fun resolveDeletedTabIdFallsBackToAll() {
        assertEquals(
            LibraryTabSelection.ALL_TAB_ID,
            LibraryTabSelection.resolve("deleted-tab", tabs, hasUnassignedStories = false),
        )
    }

    @Test
    fun encodeNullProducesAllTabId() {
        assertEquals(LibraryTabSelection.ALL_TAB_ID, LibraryTabSelection.encode(null))
    }

    @Test
    fun encodeRoundTripsExistingTabId() {
        assertEquals("reading", LibraryTabSelection.encode("reading"))
    }

    @Test
    fun encodeRoundTripsThroughResolve() {
        val encoded = LibraryTabSelection.encode("done")
        assertEquals("done", LibraryTabSelection.resolve(encoded, tabs, hasUnassignedStories = false))
    }
}
