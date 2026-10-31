package com.vinicius741.webnovelarchiver.feature.library

import com.vinicius741.webnovelarchiver.ui.layout.ScreenLayoutPlanning
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryViewModePlanningTest {
    @Test
    fun `unknown stored values fall back to the list`() {
        assertEquals(LibraryViewMode.LIST, LibraryViewMode.fromStored(null))
        assertEquals(LibraryViewMode.LIST, LibraryViewMode.fromStored("grid"))
        assertEquals(LibraryViewMode.COVERS, LibraryViewMode.fromStored("covers"))
        assertEquals(LibraryViewMode.LIST, LibraryViewMode.COVERS.toggled)
    }

    @Test
    fun `list keeps the size-class columns and width cap`() {
        val spec = LibraryViewModePlanning.gridSpec(LibraryViewMode.LIST, listColumns = 2, windowWidthDp = 750)
        assertEquals(LibraryGridSpec(LibraryViewMode.LIST, 2, ScreenLayoutPlanning.LIBRARY_MAX_WIDTH_2_COL), spec)
    }

    @Test
    fun `covers show two columns on a phone and more on the inner display`() {
        assertEquals(2, LibraryViewModePlanning.gridSpec(LibraryViewMode.COVERS, 1, windowWidthDp = 411).columns)
        assertEquals(4, LibraryViewModePlanning.gridSpec(LibraryViewMode.COVERS, 2, windowWidthDp = 750).columns)
    }

    @Test
    fun `covers never drop below two columns on a narrow window`() {
        assertEquals(2, LibraryViewModePlanning.gridSpec(LibraryViewMode.COVERS, 1, windowWidthDp = 280).columns)
    }

    @Test
    fun `covers stop adding columns at the widest content cap`() {
        val spec = LibraryViewModePlanning.gridSpec(LibraryViewMode.COVERS, 3, windowWidthDp = 4000)
        assertEquals(ScreenLayoutPlanning.LIBRARY_MAX_WIDTH_3_COL, spec.maxContentWidthDp)
        assertEquals(ScreenLayoutPlanning.LIBRARY_MAX_WIDTH_3_COL / 166, spec.columns)
    }
}
