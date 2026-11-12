package com.vinicius741.webnovelarchiver.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellNavigationPlanningTest {
    @Test
    fun `a novel opened from Updates stays under Updates`() {
        val history = listOf(AppRoute.Library, AppRoute.Updates, AppRoute.Details("s"))
        assertEquals(ShellDestination.UPDATES, ShellNavigationPlanning.destinationOf(history))
    }

    @Test
    fun `a settings page opened straight away falls back to its parent destination`() {
        assertEquals(ShellDestination.SETTINGS, ShellNavigationPlanning.destinationOf(listOf(AppRoute.TtsSettings)))
        assertEquals(ShellDestination.LIBRARY, ShellNavigationPlanning.destinationOf(listOf(AppRoute.Details("s"))))
    }

    @Test
    fun `the newest destination in the history wins`() {
        val history = listOf(AppRoute.Library, AppRoute.Queue, AppRoute.Details("s"), AppRoute.Settings, AppRoute.TtsSettings)
        assertEquals(ShellDestination.SETTINGS, ShellNavigationPlanning.destinationOf(history))
    }

    @Test
    fun `full-screen flows hide the navigation and the reader and player hide the mini player`() {
        assertFalse(ShellNavigationPlanning.showsNavigation(AppRoute.Reader("s", "c")))
        assertFalse(ShellNavigationPlanning.showsNavigation(AppRoute.Player))
        assertFalse(ShellNavigationPlanning.showsNavigation(AppRoute.ChapterSelection("s")))
        assertTrue(ShellNavigationPlanning.showsNavigation(AppRoute.Details("s")))
        assertFalse(ShellNavigationPlanning.showsMiniPlayer(AppRoute.Reader("s", "c")))
        assertTrue(ShellNavigationPlanning.showsMiniPlayer(AppRoute.Details("s")))
        assertFalse(ShellNavigationPlanning.showsMiniPlayer(AppRoute.Narration))
        assertTrue(ShellNavigationPlanning.isDestinationRoot(AppRoute.Queue))
        assertFalse(ShellNavigationPlanning.isDestinationRoot(AppRoute.TtsSettings))
    }

    @Test
    fun `a destination tap starts over the Library root`() {
        assertEquals(listOf(AppRoute.Library), ShellNavigationPlanning.historyFor(ShellDestination.LIBRARY))
        assertEquals(listOf(AppRoute.Library, AppRoute.Updates), ShellNavigationPlanning.historyFor(ShellDestination.UPDATES))
    }

    @Test
    fun `the Updates route round-trips through the codec`() {
        assertEquals(AppRoute.Updates, AppRouteCodec.decode(AppRouteCodec.encode(AppRoute.Updates)))
    }
}
