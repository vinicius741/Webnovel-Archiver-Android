package com.vinicius741.webnovelarchiver.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavigatorTest {
    @Test
    fun `reopening a screen in the history returns to it instead of stacking a copy`() {
        val navigator = AppNavigator()
        navigator.navigate(AppRoute.Details("story"))
        navigator.navigate(AppRoute.PatreonAccount("story"))
        // "Check now" on the Patreon screen opens the novel it came from.
        navigator.navigate(AppRoute.Details("story"))

        assertEquals(AppRoute.Details("story"), navigator.current)
        assertEquals(AppRoute.Library, navigator.back())
        assertFalse(navigator.canGoBack)
    }

    @Test
    fun `opening the library from anywhere clears the history above it`() {
        val navigator = AppNavigator()
        navigator.navigate(AppRoute.Settings)
        navigator.navigate(AppRoute.DataBackup)
        navigator.navigate(AppRoute.Library)

        assertEquals(listOf("library"), navigator.encodedStack())
        assertFalse(navigator.canGoBack)
    }

    @Test
    fun `reopening keeps the newest arguments of the matching entry`() {
        val navigator = AppNavigator()
        navigator.navigate(AppRoute.Details("story"))
        navigator.navigate(AppRoute.ChapterSelection("story", setOf("one")))
        navigator.navigate(AppRoute.LegacyEpubs("story"))
        navigator.navigate(AppRoute.ChapterSelection("story", setOf("two")))

        assertEquals(AppRoute.ChapterSelection("story", setOf("two")), navigator.current)
        assertEquals(AppRoute.Details("story"), navigator.back())
    }

    @Test
    fun `the working screen is replaced by whatever follows it`() {
        val navigator = AppNavigator()
        navigator.navigate(AppRoute.Working)
        navigator.navigate(AppRoute.Details("imported"))

        assertEquals(AppRoute.Details("imported"), navigator.current)
        assertEquals(AppRoute.Library, navigator.back())
    }

    @Test
    fun `a finished form can be replaced by the item it created`() {
        val navigator = AppNavigator()
        navigator.navigate(AppRoute.AddStory)
        navigator.replace(AppRoute.Details("new"))

        assertEquals(AppRoute.Details("new"), navigator.current)
        assertEquals(AppRoute.Library, navigator.back())
    }

    @Test
    fun `back from history keeps the path the user took`() {
        val navigator = AppNavigator()
        navigator.navigate(AppRoute.Updates)
        navigator.navigate(AppRoute.Reader("story", "chapter"))

        assertEquals(AppRoute.Updates, navigator.back())
    }

    @Test
    fun `deep entries open on top of their parents`() {
        val navigator = AppNavigator()
        navigator.resetTo(AppRoute.Reader("story", "chapter"))

        assertEquals(AppRoute.Reader("story", "chapter"), navigator.current)
        assertEquals(AppRoute.Details("story"), navigator.back())
        assertEquals(AppRoute.Library, navigator.back())
        assertNull(navigator.back())
    }

    @Test
    fun `back without history walks up the parent chain to the library`() {
        val navigator = AppNavigator(AppRoute.Notifications)

        assertTrue(navigator.canGoBack)
        assertEquals(AppRoute.Settings, navigator.back())
        assertEquals(AppRoute.Library, navigator.back())
        assertFalse(navigator.canGoBack)
        assertNull(navigator.back())
    }

    @Test
    fun `restoring a stack saved by an older build drops its duplicates`() {
        val navigator = AppNavigator()
        val legacy =
            listOf(
                AppRoute.Library,
                AppRoute.Details("story"),
                AppRoute.PatreonAccount("story"),
                AppRoute.Details("story"),
            ).map(AppRouteCodec::encode)

        assertTrue(navigator.restore(legacy))
        assertEquals(AppRoute.Details("story"), navigator.current)
        assertEquals(AppRoute.Library, navigator.back())
    }

    @Test
    fun `stable keys list exactly the history entries`() {
        val navigator = AppNavigator()
        navigator.navigate(AppRoute.Details("story"))
        navigator.navigate(AppRoute.Reader("story", "chapter"))
        navigator.back()

        assertEquals(setOf(AppRoute.Library.stableKey, AppRoute.Details("story").stableKey), navigator.stableKeys)
    }

    @Test
    fun `every screen has a parent that leads to the library`() {
        assertNull(AppRoute.Library.parent)
        assertEquals(AppRoute.Details("story"), AppRoute.Trends("story", "score").parent)
        assertEquals(AppRoute.Details("story"), AppRoute.PatreonAccount("story").parent)
        assertEquals(AppRoute.DataBackup, AppRoute.PatreonAccount().parent)
        assertEquals(AppRoute.Updates, AppRoute.UpdateFollowSelection.parent)
        assertEquals(AppRoute.Settings, AppRoute.CleanupRules.parent)
        assertEquals(AppRoute.Library, AppRoute.Player.parent)
    }
}
