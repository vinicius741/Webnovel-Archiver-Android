package com.vinicius741.webnovelarchiver.navigation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenModelStoreTest {
    private class Recording : ScreenModel(CoroutineScope(Job())) {
        var cleared = false

        override fun onCleared() {
            cleared = true
        }
    }

    private class Other : ScreenModel(CoroutineScope(Job()))

    private val store = ScreenModelStore()

    @Test
    fun sameKeyReturnsTheSameModelAcrossRenders() {
        val first = store.get("details:s1") { Recording() }
        val second = store.get("details:s1") { Recording() }

        assertSame(first, second)
    }

    @Test
    fun modelsLeavingTheHistoryAreClearedAndTheirScopeCancelled() {
        val kept = store.get("library") { Recording() }
        val dropped = store.get("details:s1") { Recording() }

        store.retainOnly(setOf("library"))

        assertTrue(dropped.cleared)
        assertFalse(dropped.scope.isActive)
        assertFalse(kept.cleared)
        assertEquals(1, store.size)
        assertNotSame(dropped, store.get("details:s1") { Recording() })
    }

    @Test
    fun aModelOfAnotherTypeUnderTheSameKeyIsReplaced() {
        val old = store.get("k") { Recording() }
        store.get("k") { Other() }

        assertTrue(old.cleared)
    }

    @Test
    fun clearEndsEveryModel() {
        val a = store.get("a") { Recording() }
        val b = store.get("b") { Recording() }

        store.clear()

        assertTrue(a.cleared && b.cleared)
        assertEquals(0, store.size)
    }
}
