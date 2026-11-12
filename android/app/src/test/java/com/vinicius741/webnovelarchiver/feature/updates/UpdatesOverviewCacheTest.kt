package com.vinicius741.webnovelarchiver.feature.updates

import com.vinicius741.webnovelarchiver.domain.model.Story
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesOverviewCacheTest {
    private val a = Story(id = "a", title = "A")
    private val b = Story(id = "b", title = "B")
    private val now = 1_000_000L

    @Test
    fun `a queue-only publish keeps the plan because every story object is the same`() {
        val cache = UpdatesOverviewCache()
        val planned = cache.plan(listOf(a, b), now)

        assertFalse(cache.needsPlan(listOf(a, b), now + 1_000))
        assertSame(planned, cache.overview(listOf(a, b), now + 1_000))
    }

    @Test
    fun `a changed or restored story needs a new plan even when it is equal`() {
        val cache = UpdatesOverviewCache()
        cache.plan(listOf(a, b), now)

        assertTrue(cache.needsPlan(listOf(a, b.copy()), now))
        assertTrue(cache.needsPlan(listOf(b, a), now))
        assertTrue(cache.needsPlan(listOf(a), now))
    }

    @Test
    fun `a plan expires as the clock moves its forecasts`() {
        val cache = UpdatesOverviewCache()
        cache.plan(listOf(a), now)

        assertFalse(cache.needsPlan(listOf(a), now + UpdatesOverviewCache.MAX_AGE_MS))
        assertTrue(cache.needsPlan(listOf(a), now + UpdatesOverviewCache.MAX_AGE_MS + 1))
        // A clock that went backwards is not trusted either.
        assertTrue(cache.needsPlan(listOf(a), now - 1))
    }

    @Test
    fun `nothing planned yet needs a plan`() {
        assertTrue(UpdatesOverviewCache().needsPlan(emptyList(), now))
    }
}
