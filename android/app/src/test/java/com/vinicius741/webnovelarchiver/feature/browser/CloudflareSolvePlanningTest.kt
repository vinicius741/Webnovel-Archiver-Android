package com.vinicius741.webnovelarchiver.feature.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudflareSolvePlanningTest {
    @Test
    fun differentPageCannotAutomaticallyReleaseRetry() {
        assertEquals(
            CloudflareSolvePageState.DIFFERENT_PAGE,
            CloudflareSolvePlanning.pageState(
                isChallenge = false,
                isSettled = true,
                isRequestedResource = false,
                isExpectedPage = true,
            ),
        )
    }

    @Test
    fun requestedPageCompletesWithoutCheckingForCookie() {
        assertEquals(
            CloudflareSolvePageState.VERIFIED,
            CloudflareSolvePlanning.pageState(
                isChallenge = false,
                isSettled = true,
                isRequestedResource = true,
                isExpectedPage = true,
            ),
        )
    }

    @Test
    fun activeChallengeDoesNotAutoCompleteEvenWithClearance() {
        assertEquals(
            CloudflareSolvePageState.CHALLENGE_ACTIVE,
            CloudflareSolvePlanning.pageState(
                isChallenge = true,
                isSettled = true,
                isRequestedResource = true,
                isExpectedPage = true,
            ),
        )
    }

    @Test
    fun requestedPageWithoutExpectedContentDoesNotComplete() {
        assertEquals(
            CloudflareSolvePageState.DIFFERENT_PAGE,
            CloudflareSolvePlanning.pageState(
                isChallenge = false,
                isSettled = true,
                isRequestedResource = true,
                isExpectedPage = false,
            ),
        )
    }
}
