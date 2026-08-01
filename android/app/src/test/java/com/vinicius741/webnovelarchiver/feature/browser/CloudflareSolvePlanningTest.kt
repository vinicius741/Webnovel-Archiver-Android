package com.vinicius741.webnovelarchiver.feature.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareSolvePlanningTest {
    @Test
    fun loadedPageWithoutClearanceRemainsAvailableForManualConfirmation() {
        assertEquals(
            CloudflareSolvePageState.READY_WITHOUT_CLEARANCE,
            CloudflareSolvePlanning.pageState(
                hasClearance = false,
                isChallenge = false,
                hasPageContent = true,
            ),
        )
        assertTrue(CloudflareSolvePlanning.requiresConfirmation(hasClearance = false))
    }

    @Test
    fun clearanceOnANonChallengePageCompletesNormally() {
        assertEquals(
            CloudflareSolvePageState.VERIFIED,
            CloudflareSolvePlanning.pageState(
                hasClearance = true,
                isChallenge = false,
                hasPageContent = true,
            ),
        )
        assertFalse(CloudflareSolvePlanning.requiresConfirmation(hasClearance = true))
    }

    @Test
    fun activeChallengeDoesNotAutoCompleteEvenWithClearance() {
        assertEquals(
            CloudflareSolvePageState.CHALLENGE_ACTIVE,
            CloudflareSolvePlanning.pageState(
                hasClearance = true,
                isChallenge = true,
                hasPageContent = true,
            ),
        )
    }
}
