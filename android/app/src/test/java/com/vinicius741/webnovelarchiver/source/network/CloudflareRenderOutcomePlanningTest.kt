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
                CloudflareRenderFailure.StaleDocumentPersisted,
                CloudflareRenderFailure.NavigationNeverCommitted,
                CloudflareRenderFailure.NeverSettled,
                CloudflareRenderFailure.RenderProcessGone,
                CloudflareRenderFailure.UnsupportedMethod,
                CloudflareRenderFailure.TimedOut,
                null,
            )

        failures.forEach { failure ->
            val outcome = CloudflareRenderOutcomePlanning.outcome(failure)
            assertTrue("expected manual verification for $failure", outcome is CloudflareRenderOutcome.NeedsManualVerification)
        }
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
}
