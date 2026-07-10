package com.vinicius741.webnovelarchiver.app

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoryStartupTest {
    @Test
    fun initializationRunsOnceAndReleasesAwaiters() =
        runTest {
            var runs = 0
            val startup = RepositoryStartup { runs += 1 }

            val first = startup.start(backgroundScope)
            val second = startup.start(backgroundScope)
            startup.awaitReady()

            assertSame(first, second)
            assertEquals(1, runs)
            assertEquals(RepositoryReadiness.Ready, startup.readiness.value)
        }

    @Test
    fun initializationFailureIsExposedToAwaiters() =
        runTest {
            val failure = IllegalStateException("broken storage")
            val startup = RepositoryStartup { throw failure }
            startup.start(backgroundScope)

            val observed = async { runCatching { startup.awaitReady() }.exceptionOrNull() }.await()

            assertSame(failure, observed)
            assertTrue(startup.readiness.value is RepositoryReadiness.Failed)
        }
}
