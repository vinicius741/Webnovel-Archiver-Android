package com.vinicius741.webnovelarchiver.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavigatorTest {
    @Test
    fun `bringing an earlier screen to the top keeps the screens it was below`() {
        val navigator = AppNavigator()
        navigator.navigate(AppRoute.Details("story"))
        navigator.navigate(AppRoute.Reader("story", "one"))
        navigator.navigate(AppRoute.TtsSettings)
        navigator.navigate(AppRoute.Player)
        // Now Playing's Read along reopens the chapter that is already in the history.
        navigator.bringToTop(AppRoute.Reader("story", "one"))

        assertEquals(AppRoute.Reader("story", "one"), navigator.current)
        assertEquals(AppRoute.Player, navigator.back())
        assertEquals(AppRoute.TtsSettings, navigator.back())
        assertEquals(AppRoute.Details("story"), navigator.back())
    }

    @Test
    fun `bringing a screen to the top can also drop entries it supersedes`() {
        val navigator = AppNavigator()
        navigator.navigate(AppRoute.Details("story"))
        navigator.navigate(AppRoute.Reader("story", "one"))
        navigator.navigate(AppRoute.Player)
        navigator.bringToTop(AppRoute.Reader("story", "two")) { it is AppRoute.Reader && it.storyId == "story" }

        assertEquals(AppRoute.Reader("story", "two"), navigator.current)
        assertEquals(AppRoute.Player, navigator.back())
        assertEquals(AppRoute.Details("story"), navigator.back())
    }

    @Test
    fun `bringing a screen to the top is a no-op for the newest entry and a push for new ones`() {
        val navigator = AppNavigator()
        navigator.navigate(AppRoute.Player)
        navigator.bringToTop(AppRoute.Reader("story", "one"))
        navigator.bringToTop(AppRoute.Reader("story", "one"))

        assertEquals(listOf("library", "player", AppRouteCodec.encode(AppRoute.Reader("story", "one"))), navigator.encodedStack())
    }

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
        navigator.navigate(AppRoute.Queue)
        navigator.navigate(AppRoute.Reader("story", "chapter"))

        assertEquals(AppRoute.Queue, navigator.back())
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
        assertEquals(AppRoute.Settings, AppRoute.CleanupRules.parent)
        assertEquals(AppRoute.Library, AppRoute.Player.parent)
    }
}
