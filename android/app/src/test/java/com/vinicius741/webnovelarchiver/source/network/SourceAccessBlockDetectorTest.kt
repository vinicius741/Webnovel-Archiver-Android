package com.vinicius741.webnovelarchiver.source.network

import okhttp3.Headers.Companion.headersOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards against false positives that misclassify legitimate chapter prose as a Cloudflare
 * challenge, and against the dead boolean clause that previously made the `server: cloudflare`
 * guard unreachable.
 */
class SourceAccessBlockDetectorTest {
    @Test
    fun cfMitigatedChallengeHeaderIsAuthoritativeRegardlessOfBody() {
        assertTrue(
            SourceAccessBlockDetector.isChallengeResponse(
                headersOf("cf-mitigated", "challenge"),
                "<html>totally normal chapter prose</html>",
            ),
        )
        // Case-insensitive.
        assertTrue(
            SourceAccessBlockDetector.isChallengeResponse(
                headersOf("cf-mitigated", "Challenge"),
                "",
            ),
        )
    }

    @Test
    fun cloudflareServerWithChallengeHtmlIsBlocked() {
        assertTrue(
            SourceAccessBlockDetector.isChallengeResponse(
                headersOf("server", "cloudflare"),
                "<html><title>Just a moment...</title></html>",
            ),
        )
    }

    @Test
    fun cloudflareServerWithNormalBodyIsNotBlocked() {
        // A Cloudflare-proxied origin serving legitimate content must not be flagged just because
        // it is behind Cloudflare.
        assertFalse(
            SourceAccessBlockDetector.isChallengeResponse(
                headersOf("server", "cloudflare"),
                "<html>normal chapter content</html>",
            ),
        )
    }

    @Test
    fun contentPhraseAloneDoesNotTriggerBlockEvenOnCloudflareProxiedResponse() {
        // The content-prone prose phrase must NEVER be a challenge signal on its own — even on a
        // Cloudflare-proxied response — because it plausibly appears in chapter prose and a false
        // positive would send a legitimate chapter down the non-retryable source-block path.
        assertFalse(
            SourceAccessBlockDetector.isChallengeResponse(
                headersOf("server", "cloudflare"),
                "<p>Please enable javascript and cookies to continue reading this site.</p>",
            ),
        )
        assertFalse(
            SourceAccessBlockDetector.isChallengeHtml(
                "<p>enable javascript and cookies to continue</p>",
            ),
        )
    }

    @Test
    fun challengeMarkerAloneWithNoCloudflareSignalIsNotBlocked() {
        // No Cloudflare header → never treat as a challenge, regardless of body. This is the key
        // fix: body markers must not fire for arbitrary servers.
        assertFalse(
            SourceAccessBlockDetector.isChallengeResponse(
                headersOf(),
                "<html><title>Just a moment...</title></html>",
            ),
        )
        assertFalse(
            SourceAccessBlockDetector.isChallengeResponse(
                headersOf("server", "nginx"),
                "<html>enable javascript and cookies to continue</html>",
            ),
        )
    }

    @Test
    fun cloudflareServerWithStrongMarkerTriggersBlock() {
        // The content prose phrase is no longer a marker at all; a strong structural marker is
        // required. Verify each strong marker still fires when served by Cloudflare.
        assertTrue(
            SourceAccessBlockDetector.isChallengeResponse(
                headersOf("server", "cloudflare"),
                "<script src='/cdn-cgi/challenge-platform/h/g/orchestrate/jsch/v1'></script>",
            ),
        )
        assertTrue(
            SourceAccessBlockDetector.isChallengeResponse(
                headersOf("server", "cloudflare"),
                "<html><head><title>Just a moment...</title></head></html>",
            ),
        )
    }
}
