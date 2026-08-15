package com.vinicius741.webnovelarchiver.source.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceReliabilityCoordinatorTest {
    @Test
    fun builtInPolicyGapIsSharedAcrossWwwAndBareHost() =
        runBlocking {
            var now = 1_000L
            val sleeps = mutableListOf<Long>()
            val coordinator =
                SourceReliabilityCoordinator(
                    nowMillis = { now },
                    sleep = { millis ->
                        sleeps += millis
                        now += millis
                    },
                )
            val policy = SourceNetworkPolicy(minimumRequestGapMillis = 3_000L)

            coordinator.awaitPermission("https://www.scribblehub.com/a", "www.scribblehub.com", policy)
            coordinator.awaitPermission("https://scribblehub.com/b", "scribblehub.com", policy)

            assertEquals(listOf(3_000L), sleeps)
            assertEquals(2L, coordinator.snapshots().single().requestCount)
        }

    @Test
    fun providerHostAliasesShareOneSourceBudget() =
        runBlocking {
            var now = 1_000L
            val sleeps = mutableListOf<Long>()
            val coordinator =
                SourceReliabilityCoordinator(
                    nowMillis = { now },
                    sleep = { millis ->
                        sleeps += millis
                        now += millis
                    },
                )
            val policy = SourceNetworkPolicy(minimumRequestGapMillis = 1_500L)

            coordinator.awaitPermission(
                "https://forums.spacebattles.com/threads/a",
                "forums.spacebattles.com",
                policy,
            )
            coordinator.awaitPermission(
                "https://forum.spacebattles.com/threads/b",
                "forum.spacebattles.com",
                policy,
            )

            assertEquals(listOf(1_500L), sleeps)
            assertEquals(2L, coordinator.snapshots().single().requestCount)
        }

    @Test
    fun rollingWindowStopsBurstEvenWithoutFixedGap() =
        runBlocking {
            var now = 0L
            val sleeps = mutableListOf<Long>()
            val coordinator =
                SourceReliabilityCoordinator(
                    nowMillis = { now },
                    sleep = { millis ->
                        sleeps += millis
                        now += millis
                    },
                )
            val policy = SourceNetworkPolicy(requestWindowMillis = 1_000L, maximumRequestsPerWindow = 2)

            repeat(3) { coordinator.awaitPermission("https://example.test/$it", "example.test", policy) }

            assertEquals(listOf(1_000L), sleeps)
        }

    @Test
    fun rateLimitOpensCooldownAndRaisesAdaptiveFloor() =
        runBlocking {
            var now = 10_000L
            val sleeps = mutableListOf<Long>()
            val coordinator =
                SourceReliabilityCoordinator(
                    nowMillis = { now },
                    sleep = { millis ->
                        sleeps += millis
                        now += millis
                    },
                )
            val policy = SourceNetworkPolicy(minimumRequestGapMillis = 1_500L)

            assertEquals(20_000L, coordinator.recordRateLimit("example.test", policy, 20_000L))
            coordinator.onNetworkChanged()
            coordinator.awaitPermission("https://example.test/a", "example.test", policy)

            assertEquals(listOf(20_000L), sleeps)
            assertTrue(coordinator.snapshots().single().effectiveMinimumGapMillis >= 5_000L)
        }

    @Test
    fun manualCircuitFailsFastUntilCleared() =
        runBlocking {
            val coordinator = SourceReliabilityCoordinator()
            coordinator.recordChallengeDetected("example.test")
            coordinator.requireManualVerification("example.test")

            val error =
                runCatching {
                    coordinator.awaitPermission("https://example.test/a", "example.test", SourceNetworkPolicy())
                }.exceptionOrNull()
            assertTrue(error is SourceAccessBlockedException)
            assertTrue(coordinator.snapshots().single().manualVerificationRequired)

            coordinator.clearAccessBlock("example.test", keepBrowserTransport = false)
            assertFalse(coordinator.snapshots().single().manualVerificationRequired)
        }

    @Test
    fun manualCircuitPredicateMatchesBothHostAndProviderIdKeys() {
        val coordinator = SourceReliabilityCoordinator()
        coordinator.recordChallengeDetected("www.scribblehub.com")
        coordinator.requireManualVerification("scribblehub.com")

        assertTrue(coordinator.isManualVerificationRequired("scribblehub.com"))
        assertTrue(coordinator.isManualVerificationRequired("www.scribblehub.com"))
        assertTrue(coordinator.isManualVerificationRequired("scribble_hub"))
        assertFalse(coordinator.isManualVerificationRequired("royalroad.com"))

        coordinator.clearAccessBlock("www.scribblehub.com", keepBrowserTransport = true)
        assertFalse(coordinator.isManualVerificationRequired("scribble_hub"))
    }

    @Test
    fun stateTransitionsInvokeThePersistenceHook() {
        val transitions = mutableListOf<String>()
        val coordinator =
            SourceReliabilityCoordinator(onStateChanged = { transitions += "changed" })

        coordinator.recordChallengeDetected("example.test")
        coordinator.requireManualVerification("example.test")
        coordinator.clearAccessBlock("example.test")

        assertEquals(3, transitions.size)
    }

    @Test
    fun manualCircuitAndStickyWindowSurviveRestoreIntoAFreshProcess() {
        val previous = SourceReliabilityCoordinator()
        previous.recordChallengeDetected("www.scribblehub.com")
        previous.requireManualVerification("scribblehub.com")

        val persisted = previous.persistableStates().single()

        val restored = SourceReliabilityCoordinator()
        restored.restore(listOf(persisted))

        assertTrue(restored.isManualVerificationRequired("scribble_hub"))
        assertTrue(restored.browserTransportActive("scribblehub.com"))
        assertEquals(persisted.challengeCount, restored.snapshots().single().challengeCount)
    }
}
