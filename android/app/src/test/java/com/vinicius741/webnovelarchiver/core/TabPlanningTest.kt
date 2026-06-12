package com.vinicius741.webnovelarchiver.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TabPlanningTest {
    @Test
    fun createTrimsNameAndAppendsWithNormalizedOrder() {
        val result = TabPlanning.create(
            listOf(Tab(id = "b", name = "Second", order = 5), Tab(id = "a", name = "First", order = 1)),
            "  Reading  ",
            "c",
            123L,
        )

        assertEquals(listOf("a", "b", "c"), result.map { it.id })
        assertEquals(listOf(0, 1, 2), result.map { it.order })
        assertEquals("Reading", result.last().name)
        assertEquals(123L, result.last().createdAt)
    }

    @Test
    fun createRejectsBlankNames() {
        val result = TabPlanning.create(listOf(Tab(id = "a", name = "First", order = 0)), "   ", "b", 123L)

        assertEquals(listOf("a"), result.map { it.id })
    }

    @Test
    fun renameTrimsNameAndRejectsBlankNames() {
        val tabs = listOf(Tab(id = "a", name = "First", order = 0), Tab(id = "b", name = "Second", order = 1))

        val renamed = TabPlanning.rename(tabs, "b", "  Queue  ")
        val blank = TabPlanning.rename(tabs, "b", "   ")

        assertEquals("Queue", renamed.single { it.id == "b" }.name)
        assertEquals("Second", blank.single { it.id == "b" }.name)
    }

    @Test
    fun deleteRemovesTabAndReordersRemainingTabs() {
        val result = TabPlanning.delete(
            listOf(Tab(id = "a", name = "First", order = 0), Tab(id = "b", name = "Second", order = 1), Tab(id = "c", name = "Third", order = 2)),
            "b",
        )

        assertEquals(listOf("a", "c"), result.map { it.id })
        assertEquals(listOf(0, 1), result.map { it.order })
    }

    @Test
    fun moveRepositionsTabAndNormalizesOrder() {
        val result = TabPlanning.move(
            listOf(Tab(id = "a", name = "First", order = 0), Tab(id = "b", name = "Second", order = 1), Tab(id = "c", name = "Third", order = 2)),
            2,
            0,
        )

        assertEquals(listOf("c", "a", "b"), result.map { it.id })
        assertEquals(listOf(0, 1, 2), result.map { it.order })
    }
}
