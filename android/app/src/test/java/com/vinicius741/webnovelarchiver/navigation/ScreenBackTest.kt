package com.vinicius741.webnovelarchiver.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenBackTest {
    @Test
    fun `back clears in-screen state before navigating`() {
        val calls = mutableListOf<String>()
        var searching = true
        val back =
            ScreenBack(
                intercept =
                    BackIntercept(isActive = { searching }, clear = {
                        calls += "clear"
                        searching = false
                    }),
                navigateUp = { calls += "up" },
            )

        assertTrue(back.handle())
        assertTrue(back.handle())
        assertEquals(listOf("clear", "up"), calls)
    }

    @Test
    fun `a modal close wins over everything else`() {
        val calls = mutableListOf<String>()
        val back =
            ScreenBack(
                close = { calls += "close" },
                intercept = BackIntercept(isActive = { true }, clear = { calls += "clear" }),
                navigateUp = { calls += "up" },
            )

        assertTrue(back.handle())
        assertEquals(listOf("close"), calls)
    }

    @Test
    fun `the root leaves back to android unless it has state to clear`() {
        var filtered = false
        val root = ScreenBack(intercept = BackIntercept(isActive = { filtered }, clear = { filtered = false }))

        assertFalse(root.handlesBack)
        assertFalse(root.handle())

        filtered = true
        assertTrue(root.handlesBack)
        assertTrue(root.handle())
        assertFalse(root.handlesBack)
    }
}
