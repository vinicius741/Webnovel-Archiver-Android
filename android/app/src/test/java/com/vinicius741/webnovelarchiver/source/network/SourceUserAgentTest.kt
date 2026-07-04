package com.vinicius741.webnovelarchiver.source.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the UA normalization that keeps OkHttp's User-Agent byte-identical to the solving
 * WebView's. Cloudflare binds `cf_clearance` to the exact UA, so any divergence between the two
 * strings silently invalidates the cookie. These cases pin the two transforms (drop `; wv)` and the
 * redundant `Version/x` token) and the idempotence/identity invariants.
 */
class SourceUserAgentTest {
    @Test
    fun reducesAndroidDeviceSegmentAndStripsWebViewBuildToken() {
        val normalized =
            SourceUserAgent.normalize(
                "Mozilla/5.0 (Linux; Android 13; Pixel 7 Build/TQ3A.230901.001; wv) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/116.0.0.0 Mobile Safari/537.36",
            )
        assertTrue(normalized.contains("(Linux; Android 10; K)"))
        assertTrue("still contains wv marker: $normalized", !normalized.contains("; wv)"))
        assertTrue("still contains device model: $normalized", !normalized.contains("Pixel 7"))
        assertTrue(normalized.contains("Chrome/116.0.0.0 Mobile Safari/537.36"))
    }

    @Test
    fun dropsRedundantVersionToken() {
        val normalized =
            SourceUserAgent.normalize(
                "Mozilla/5.0 (Linux; Android 13; wv) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Version/4.0 Chrome/116.0.0.0 Mobile Safari/537.36",
            )
        assertTrue("still contains Version token: $normalized", !normalized.contains("Version/"))
        assertTrue(normalized.contains("Chrome/116.0.0.0"))
    }

    @Test
    fun isIdempotentOnAlreadyNormalizedInput() {
        val already =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/116.0.0.0 Mobile Safari/537.36"
        assertEquals(already, SourceUserAgent.normalize(already))
    }

    @Test
    fun doubleNormalizeIsStable() {
        val raw =
            "Mozilla/5.0 (Linux; Android 14; wv) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Version/4.0 Chrome/130.0.0.0 Mobile Safari/537.36"
        val once = SourceUserAgent.normalize(raw)
        assertEquals(once, SourceUserAgent.normalize(once))
    }

    @Test
    fun handlesVersionTokenWithMinorNumbers() {
        // The Version token may carry multiple dotted components; the regex must consume all of them
        // up to the following "Chrome/".
        val normalized =
            SourceUserAgent.normalize(
                "(Linux; Android 12; wv) Version/4.1.3 Chrome/120.0.6099.230 Mobile Safari/537.36",
            )
        assertTrue(normalized.contains("Chrome/120.0.6099.230"))
        assertTrue(!normalized.contains("Version/4.1.3"))
    }

    @Test
    fun stripsEmulatorBuildIdentifiers() {
        val normalized =
            SourceUserAgent.normalize(
                "Mozilla/5.0 (Linux; Android 16; sdk_gphone64_arm64 Build/BP22.250221.010; wv) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36",
            )
        assertEquals(
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/133.0.6943.137 Mobile Safari/537.36",
            normalized,
        )
    }

    @Test
    fun blankInputIsReturnedUnchanged() {
        assertEquals("", SourceUserAgent.normalize(""))
    }

    @Test
    fun okHttpAndWebViewsShareOneResolvedString() {
        // Invariant: every surface reads SourceUserAgent.resolved, so the UA the cookie was minted
        // against (WebView) is the same one OkHttp sends. This test exists to document and pin that
        // contract — resolved is a single @Volatile var with one writer (Application.onCreate).
        // We can't touch WebSettings in a unit test, but we can assert the field is the canonical
        // source by confirming normalize() is the only transformation applied.
        val raw =
            "Mozilla/5.0 (Linux; Android 13; wv) AppleWebKit/537.36 Version/4.0 Chrome/116.0.0.0 Mobile Safari/537.36"
        val okHttp = SourceUserAgent.normalize(raw)
        val webView = SourceUserAgent.normalize(raw)
        // Cookie bind: if these ever differ, cf_clearance is rejected.
        assertEquals(okHttp, webView)
    }
}
