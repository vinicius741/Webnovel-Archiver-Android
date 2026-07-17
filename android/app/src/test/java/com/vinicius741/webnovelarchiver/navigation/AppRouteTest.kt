package com.vinicius741.webnovelarchiver.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRouteTest {
    @Test
    fun `route codec round trips ids and set arguments`() {
        val routes =
            listOf(
                AppRoute.Library,
                AppRoute.Details("story:/ ünicode"),
                AppRoute.Reader("story:1", "chapter/2"),
                AppRoute.ChapterSelection("story", setOf("chapter:2", "chapter 1")),
                AppRoute.LibrarySelection(setOf("b", "a")),
                AppRoute.Notifications,
                AppRoute.CleanupRules,
            )

        routes.forEach { route -> assertEquals(route, AppRouteCodec.decode(AppRouteCodec.encode(route))) }
    }

    @Test
    fun `trends route round trips with and without focus`() {
        // No focus: single storyId argument; focus decodes back to null.
        val noFocus = AppRoute.Trends("story:/1")
        assertEquals(noFocus, AppRouteCodec.decode(AppRouteCodec.encode(noFocus)))

        val withFocus = AppRoute.Trends("story:/1", "patreon_usd")
        assertEquals(withFocus, AppRouteCodec.decode(AppRouteCodec.encode(withFocus)))

        // Focus and no-focus variants of the same story must have distinct stable keys so view state
        // (e.g. scroll-to-chart) is restored for the right variant after process death.
        assertNotEquals(noFocus.stableKey, withFocus.stableKey)
    }

    @Test
    fun `codec rejects corrupt or unknown routes`() {
        assertNull(AppRouteCodec.decode("reader:zz:00"))
        assertNull(AppRouteCodec.decode("reader:00"))
        assertNull(AppRouteCodec.decode("future_route"))
    }

    @Test
    fun `stable keys ignore display copy and transient selections`() {
        assertEquals(
            AppRoute.ChapterSelection("story", setOf("one")).stableKey,
            AppRoute.ChapterSelection("story", setOf("two")).stableKey,
        )
        assertEquals(AppRoute.LibrarySelection(setOf("one")).stableKey, AppRoute.LibrarySelection(setOf("two")).stableKey)
    }

    @Test
    fun `navigator owns forward back and same-destination replacement`() {
        val navigator = AppNavigator()
        assertFalse(navigator.canGoBack)
        assertNull(navigator.back())

        navigator.navigate(AppRoute.Details("story"))
        navigator.navigate(AppRoute.Reader("story", "one"))
        navigator.navigate(AppRoute.Reader("story", "two"))

        assertTrue(navigator.canGoBack)
        assertEquals(AppRoute.Reader("story", "two"), navigator.current)
        assertEquals(AppRoute.Details("story"), navigator.back())
        assertEquals(AppRoute.Library, navigator.back())
        assertFalse(navigator.canGoBack)
    }

    @Test
    fun `encoded stack restores route arguments and rejects partial corruption`() {
        val source = AppNavigator()
        source.navigate(AppRoute.Details("story"))
        source.navigate(AppRoute.Reader("story", "chapter"))

        val restored = AppNavigator(AppRoute.Settings)
        assertTrue(restored.restore(source.encodedStack()))
        assertEquals(AppRoute.Reader("story", "chapter"), restored.current)
        assertEquals(AppRoute.Details("story"), restored.back())

        assertFalse(restored.restore(listOf("library", "not-a-route")))
        assertEquals(AppRoute.Details("story"), restored.current)

        restored.reset()
        assertEquals(AppRoute.Library, restored.current)
        assertFalse(restored.canGoBack)
    }
}
