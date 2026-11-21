package com.vinicius741.webnovelarchiver.source.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareRenderOutcomePlanningTest {
    @Test
    fun unsolvedChallengesAndRendererDeathsNeedManualVerification() {
        val failures =
            listOf(
                CloudflareRenderFailure.ChallengeActive,
                CloudflareRenderFailure.RenderProcessGone,
                CloudflareRenderFailure.UnsupportedMethod,
            )

        failures.forEach { failure ->
            val outcome = CloudflareRenderOutcomePlanning.outcome(failure)
            assertTrue("expected manual verification for $failure", outcome is CloudflareRenderOutcome.NeedsManualVerification)
        }
    }

    @Test
    fun aPageThatNeverArrivedWithoutAChallengeIsAStallNotABlock() {
        val failures =
            listOf(
                CloudflareRenderFailure.StaleDocumentPersisted,
                CloudflareRenderFailure.NavigationNeverCommitted,
                CloudflareRenderFailure.NeverSettled,
                CloudflareRenderFailure.TimedOut,
            )

        failures.forEach { failure ->
            assertEquals(CloudflareRenderOutcome.Stalled(failure), CloudflareRenderOutcomePlanning.outcome(failure))
        }
        assertEquals(
            CloudflareRenderOutcome.Stalled(CloudflareRenderFailure.TimedOut),
            CloudflareRenderOutcomePlanning.outcome(null),
        )
    }

    @Test
    fun aTimeoutIsAChallengeOnlyWhenTheLastAnsweredPollShowedOne() {
        assertEquals(
            CloudflareRenderFailure.ChallengeActive,
            CloudflareRenderPollPlanning.failureOnTimeout(lastPollSawChallenge = true),
        )
        assertEquals(
            CloudflareRenderFailure.TimedOut,
            CloudflareRenderPollPlanning.failureOnTimeout(lastPollSawChallenge = false),
        )
    }

    @Test
    fun unexpectedPageContentGoesToTheParserNotTheCircuit() {
        val page = CloudflareRenderedPage("<html></html>", "https://example.test/chapter/1")

        val outcome = CloudflareRenderOutcomePlanning.outcome(CloudflareRenderFailure.PageContentUnexpected(page))

        assertEquals(CloudflareRenderOutcome.PageContentUnexpected(page), outcome)
    }

    @Test
    fun mainFrameHttpErrorsSurfaceTheirStatus() {
        assertEquals(
            CloudflareRenderOutcome.OriginHttpError(404),
            CloudflareRenderOutcomePlanning.outcome(CloudflareRenderFailure.MainFrameHttpError(404)),
        )
    }

    @Test
    fun transportLevelRenderFailuresAreRetryable() {
        assertEquals(
            CloudflareRenderOutcome.TransportError,
            CloudflareRenderOutcomePlanning.outcome(CloudflareRenderFailure.MainFrameTransportError),
        )
    }

    @Test
    fun onlyAReadableCurrentDocumentChangesWhetherAChallengeIsShowing() {
        fun state(
            html: String,
            stale: Boolean = false,
        ) = CloudflarePageState("https://example.test/chapter/1", "complete", html, stale)

        // The challenge reloading itself returns an empty document for a moment.
        assertTrue(CloudflareRenderPollPlanning.challengeShowing(true, state(""), isChallenge = false))
        assertTrue(CloudflareRenderPollPlanning.challengeShowing(true, state("<html>old</html>", stale = true), isChallenge = false))
        assertTrue(!CloudflareRenderPollPlanning.challengeShowing(true, state("<html>chapter</html>"), isChallenge = false))
        assertTrue(CloudflareRenderPollPlanning.challengeShowing(false, state("<html>challenge</html>"), isChallenge = true))
        assertTrue(!CloudflareRenderPollPlanning.challengeShowing(false, state(""), isChallenge = false))
    }
}
