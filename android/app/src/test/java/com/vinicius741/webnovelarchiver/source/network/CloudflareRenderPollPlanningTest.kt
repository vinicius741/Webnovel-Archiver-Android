package com.vinicius741.webnovelarchiver.source.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareRenderPollPlanningTest {
    @Test
    fun acceptsSettledExpectedDocument() {
        assertEquals(
            CloudflareRenderPollDecision.ACCEPT_PAGE,
            CloudflareRenderPollPlanning.decide(
                isStaleDocument = false,
                documentUrl = "https://www.scribblehub.com/read/story/chapter/1/",
                readyState = "complete",
                isChallenge = false,
                isRequestedResource = true,
                isExpected = { true },
            ),
        )
        assertEquals(
            CloudflareRenderPollDecision.ACCEPT_PAGE,
            CloudflareRenderPollPlanning.decide(
                isStaleDocument = false,
                documentUrl = "https://www.scribblehub.com/read/story/chapter/1/",
                readyState = "interactive",
                isChallenge = false,
                isRequestedResource = true,
                isExpected = { true },
            ),
        )
    }

    @Test
    fun keepsPollingWhileChallengeIsStillActive() {
        var evaluated = false
        assertEquals(
            CloudflareRenderPollDecision.KEEP_POLLING,
            CloudflareRenderPollPlanning.decide(
                isStaleDocument = false,
                documentUrl = "https://www.scribblehub.com/read/story/chapter/1/",
                readyState = "complete",
                isChallenge = true,
                isRequestedResource = true,
                isExpected = {
                    evaluated = true
                    true
                },
            ),
        )
        assertFalse(evaluated)
    }

    @Test
    fun keepsPollingWhileDocumentIsStillLoading() {
        var evaluated = false
        assertEquals(
            CloudflareRenderPollDecision.KEEP_POLLING,
            CloudflareRenderPollPlanning.decide(
                isStaleDocument = false,
                documentUrl = "https://www.scribblehub.com/read/story/chapter/1/",
                readyState = "loading",
                isChallenge = false,
                isRequestedResource = true,
                isExpected = {
                    evaluated = true
                    true
                },
            ),
        )
        assertFalse(evaluated)
    }

    @Test
    fun keepsPollingBeforeNavigationCommits() {
        assertEquals(
            CloudflareRenderPollDecision.KEEP_POLLING,
            CloudflareRenderPollPlanning.decide(
                isStaleDocument = false,
                documentUrl = "about:blank",
                readyState = "complete",
                isChallenge = false,
                isRequestedResource = false,
                isExpected = { true },
            ),
        )
        assertEquals(
            CloudflareRenderPollDecision.KEEP_POLLING,
            CloudflareRenderPollPlanning.decide(
                isStaleDocument = false,
                documentUrl = "",
                readyState = "complete",
                isChallenge = false,
                isRequestedResource = false,
                isExpected = { true },
            ),
        )
    }

    @Test
    fun keepsPollingSettledPreviousResourceUntilNavigationCommits() {
        var evaluated = false
        assertEquals(
            CloudflareRenderPollDecision.KEEP_POLLING,
            CloudflareRenderPollPlanning.decide(
                isStaleDocument = false,
                documentUrl = "https://www.scribblehub.com/read/story/chapter/1/",
                readyState = "complete",
                isChallenge = false,
                isRequestedResource = false,
                isExpected = {
                    evaluated = true
                    true
                },
            ),
        )
        assertFalse(evaluated)
    }

    @Test
    fun keepsPollingWhileStaleMarkerIsPresent() {
        // A retry of the same URL sees the previous request's settled page, which would otherwise
        // pass every gate; the stale marker must defer to the fresh navigation instead.
        var evaluated = false
        assertEquals(
            CloudflareRenderPollDecision.KEEP_POLLING,
            CloudflareRenderPollPlanning.decide(
                isStaleDocument = true,
                documentUrl = "https://www.scribblehub.com/read/story/chapter/1/",
                readyState = "complete",
                isChallenge = false,
                isRequestedResource = true,
                isExpected = {
                    evaluated = true
                    true
                },
            ),
        )
        assertFalse(evaluated)
    }

    @Test
    fun rejectsSettledUnexpectedDocumentAfterRequestedResourceCommits() {
        assertEquals(
            CloudflareRenderPollDecision.REJECT_PAGE,
            CloudflareRenderPollPlanning.decide(
                isStaleDocument = false,
                documentUrl = "https://www.scribblehub.com/read/story/chapter/1/",
                readyState = "complete",
                isChallenge = false,
                isRequestedResource = true,
                isExpected = { false },
            ),
        )
    }

    @Test
    fun waitsForExpectedContentDuringSettlingPeriod() {
        assertEquals(
            CloudflareRenderPollDecision.KEEP_POLLING,
            CloudflareRenderPollPlanning.decide(
                isStaleDocument = false,
                documentUrl = "https://www.scribblehub.com/read/story/chapter/1/",
                readyState = "interactive",
                isChallenge = false,
                isRequestedResource = true,
                contentWaitExpired = false,
                isExpected = { false },
            ),
        )
    }

    @Test
    fun contentWaitStartsOnlyAfterRequestedDocumentIsInteractive() {
        val page = CloudflarePageState("https://www.scribblehub.com/chapter/1", "loading", "<html></html>")
        assertFalse(CloudflareRenderPollPlanning.shouldStartContentWait(page, false, true, 0L))
        assertTrue(CloudflareRenderPollPlanning.shouldStartContentWait(page.copy(readyState = "interactive"), false, true, 0L))
        assertFalse(CloudflareRenderPollPlanning.shouldStartContentWait(page.copy(readyState = "complete"), true, true, 0L))
    }

    @Test
    fun completePageWithoutExpectedContentFailsOnePage() {
        val page = CloudflarePageState("https://www.scribblehub.com/chapter/1", "complete", "<html></html>")
        assertTrue(
            CloudflareRenderPollPlanning.failureAfterPolls(page, page.documentUrl, false, true, contentWaitExpired = true) is
                CloudflareRenderFailure.PageContentUnexpected,
        )
    }

    @Test
    fun completePageInsideSettlingGraceFailsAsNeverSettled() {
        val page = CloudflarePageState("https://www.scribblehub.com/chapter/1", "complete", "<html></html>")
        assertTrue(
            CloudflareRenderPollPlanning.failureAfterPolls(page, page.documentUrl, false, true, contentWaitExpired = false) is
                CloudflareRenderFailure.NeverSettled,
        )
    }
}
