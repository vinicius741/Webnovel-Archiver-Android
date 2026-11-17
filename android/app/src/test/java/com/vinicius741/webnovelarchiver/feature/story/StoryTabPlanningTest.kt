package com.vinicius741.webnovelarchiver.feature.story

import com.vinicius741.webnovelarchiver.navigation.AppRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryTabPlanningTest {
    @Test
    fun `every tab round trips through its route`() {
        StoryTab.entries.forEach { tab ->
            val route = StoryTabPlanning.routeFor(tab, "s1")
            assertEquals(tab, StoryTabPlanning.tabOf(route))
            assertEquals("s1", StoryTabPlanning.storyIdOf(route))
        }
    }

    @Test
    fun `routes with a focus or chapter still name their tab`() {
        assertEquals(StoryTab.TRENDS, StoryTabPlanning.tabOf(AppRoute.Trends("s1", "score")))
        assertEquals(StoryTab.CHAPTERS, StoryTabPlanning.tabOf(AppRoute.Details("s1", chapters = true)))
        assertNull(StoryTabPlanning.tabOf(AppRoute.Reader("s1", "c4")))
        assertNull(StoryTabPlanning.storyIdOf(AppRoute.Library))
    }

    @Test
    fun `swiping walks the strip order and stops at either end`() {
        assertEquals(StoryTab.CHAPTERS, StoryTabPlanning.neighbor(StoryTab.OVERVIEW, 1))
        assertEquals(StoryTab.TRENDS, StoryTabPlanning.neighbor(StoryTab.AI, -1))
        assertNull(StoryTabPlanning.neighbor(StoryTab.OVERVIEW, -1))
        assertNull(StoryTabPlanning.neighbor(StoryTab.AI, 1))
    }

    @Test
    fun `overview becomes about beside the hero pane`() {
        assertEquals("Overview", StoryTabPlanning.label(StoryTab.OVERVIEW, twoPane = false))
        assertEquals("About", StoryTabPlanning.label(StoryTab.OVERVIEW, twoPane = true))
        assertEquals("Trends", StoryTabPlanning.label(StoryTab.TRENDS, twoPane = true))
        assertTrue(StoryTabPlanning.showsHero(StoryTab.AI, twoPane = true))
        assertFalse(StoryTabPlanning.showsHero(StoryTab.AI, twoPane = false))
        assertTrue(StoryTabPlanning.showsHero(StoryTab.OVERVIEW, twoPane = false))
    }

    @Test
    fun `only the chapters tab shows a count`() {
        assertEquals("210", StoryTabPlanning.count(StoryTab.CHAPTERS, 210))
        assertNull(StoryTabPlanning.count(StoryTab.CHAPTERS, 0))
        assertNull(StoryTabPlanning.count(StoryTab.TRENDS, 210))
        assertEquals("2,450", StoryTabPlanning.compactCount(2450))
        assertEquals("12.3K", StoryTabPlanning.compactCount(12_345))
        assertEquals("20K", StoryTabPlanning.compactCount(20_000))
    }
}
