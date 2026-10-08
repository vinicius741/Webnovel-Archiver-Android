package com.vinicius741.webnovelarchiver.feature.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySelectionPlanningTest {
    @Test
    fun `toggle adds then removes an id`() {
        val once = LibrarySelectionPlanning.toggle(emptySet(), "a")
        assertEquals(setOf("a"), once)
        assertEquals(emptySet<String>(), LibrarySelectionPlanning.toggle(once, "a"))
    }

    @Test
    fun `toggleAll selects the visible novels while keeping hidden selections`() {
        val result = LibrarySelectionPlanning.toggleAll(setOf("hidden", "a"), listOf("a", "b", "c"))
        assertEquals(setOf("hidden", "a", "b", "c"), result)
    }

    @Test
    fun `toggleAll clears only the visible novels when all are selected`() {
        val result = LibrarySelectionPlanning.toggleAll(setOf("hidden", "a", "b"), listOf("a", "b"))
        assertEquals(setOf("hidden"), result)
    }

    @Test
    fun `toggleAll with nothing visible changes nothing`() {
        assertEquals(setOf("a"), LibrarySelectionPlanning.toggleAll(setOf("a"), emptyList()))
    }

    @Test
    fun `prune drops ids missing from the library`() {
        assertEquals(setOf("a"), LibrarySelectionPlanning.prune(setOf("a", "gone"), listOf("a", "b")))
    }

    @Test
    fun `copy pluralizes`() {
        assertEquals("1 selected", LibrarySelectionPlanning.title(1))
        assertEquals("Delete this novel?", LibrarySelectionPlanning.deletePrompt(1))
        assertEquals("Delete 3 novels?", LibrarySelectionPlanning.deletePrompt(3))
    }

    @Test
    fun `screen header swaps to the selection count`() {
        assertEquals("Library", LibrarySelectionPlanning.screenTitle(0))
        assertEquals("2 selected", LibrarySelectionPlanning.screenTitle(2))
        assertEquals("1 novel", LibrarySelectionPlanning.screenSubtitle(1, 0))
        assertEquals("5 novels", LibrarySelectionPlanning.screenSubtitle(5, 0))
        assertEquals(null, LibrarySelectionPlanning.screenSubtitle(5, 2))
        assertEquals(null, LibrarySelectionPlanning.screenSubtitle(0, 0))
    }
}
