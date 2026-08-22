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
        // The interstitial's inline config object is a strong marker on its own.
        assertTrue(
            SourceAccessBlockDetector.isChallengeResponse(
                headersOf("server", "cloudflare"),
                "<script>window._cf_chl_opt={CVId:'2',cType:'managed',cRay:'8f4'}</script>",
            ),
        )
    }

    @Test
    fun challengeRedirectTokenInsideContentUrlIsNotABlock() {
        // Regression: an author pasted a link to their fiction on another Cloudflare-proxied site,
        // and that link carried a __cf_chl_rt_tk query token. The bare cf_chl substring matched it
        // in the served page's head, so a 200 fiction page was classified as an active challenge,
        // opening the manual-verification circuit and blocking the whole source. The token is a
        // URL fragment, not an interstitial structure, and must not fire the detector.
        val descriptionLink =
            "<a href=\"https://www.scribblehub.com/series/2313883/hodoku/" +
                "?__cf_chl_rt_tk=uXwpdTg08UsRQ_l5impa5tIiyd7At9yVByLqDWDzyrA-1786094895-1.0.1.1\">" +
                "ScribbleHub</a>"
        assertFalse(SourceAccessBlockDetector.isChallengeHtml(descriptionLink))
        assertFalse(
            SourceAccessBlockDetector.isChallengeResponse(
                headersOf("server", "cloudflare"),
                "<html><head><title>Hodoku: My Ordinary Life As a Kunoichi | Royal Road</title></head>" +
                    "<body>$descriptionLink</body></html>",
            ),
        )
    }
}
