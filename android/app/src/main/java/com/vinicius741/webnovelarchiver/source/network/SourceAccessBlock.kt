package com.vinicius741.webnovelarchiver.source.network

import okhttp3.Headers
import org.jsoup.Jsoup
import java.io.IOException

// IOException so it survives OkHttp's async boundary unwrapped: enqueue wraps non-IO throwables in
// a generic IOException AND rethrows the original on the dispatcher thread, which would crash the
// process and defeat the typed catch sites (executeAttempt, download/sync failure planning).
class SourceAccessBlockedException(
    val blockedUrl: String,
    val manualVerificationRequired: Boolean = true,
    message: String = "Cloudflare is blocking automated access. Open the source in the browser, pass the check, then retry.",
) : IOException(message)

object SourceAccessBlockDetector {
    /**
     * A response is treated as a Cloudflare challenge only when a Cloudflare signal is present.
     * Body markers alone are intentionally NOT enough — chapter prose or author notes can carry
     * phrases that resemble challenge text, and the resulting `SourceAccessBlockedException` is
     * non-retryable, so a false positive would permanently block a legitimate chapter.
     *
     *  - `cf-mitigated: challenge` is authoritative on its own.
     *  - Otherwise a Cloudflare `server` header must be corroborated by [isChallengeHtml].
     */
    fun isChallengeResponse(
        headers: Headers,
        body: String,
    ): Boolean {
        if (headers["cf-mitigated"].equals("challenge", ignoreCase = true)) return true
        if (!headers["server"].equals("cloudflare", ignoreCase = true)) return false
        return isChallengeHtml(body)
    }

    /**
     * Inspect interstitial structure, not words in chapter prose. Cloudflare also injects
     * challenge-platform scripts into successfully served pages for JavaScript Detections;
     * only challenge orchestration or the interstitial config identifies a blocking script.
     *
     * Royal Road also fronts pages with an Anubis proof-of-work interstitial, served as a 200
     * through Cloudflare. Its challenge payload / bootstrap script elements identify it; the
     * WebView render solves it like any other JavaScript challenge.
     */
    fun isChallengeHtml(body: String): Boolean {
        val document = Jsoup.parse(body.take(BODY_SCAN_LIMIT))
        if (document.title().trim().equals("Just a moment...", ignoreCase = true)) return true
        if (document.selectFirst(ANUBIS_CHALLENGE_SELECTOR) != null) return true
        return document.select("script").any { script ->
            val source = script.attr("src")
            val code = script.data()
            CHALLENGE_CONFIG.containsMatchIn(code) ||
                CHALLENGE_ORCHESTRATION.containsMatchIn(source) ||
                CHALLENGE_ORCHESTRATION.containsMatchIn(code)
        }
    }

    private val CHALLENGE_CONFIG = Regex("""\b(?:window\s*\.\s*)?_cf_chl_opt\s*=""", RegexOption.IGNORE_CASE)
    private val CHALLENGE_ORCHESTRATION =
        Regex("""/cdn-cgi/challenge-platform/[^\s'"<>]*/orchestrate/""", RegexOption.IGNORE_CASE)
    private const val ANUBIS_CHALLENGE_SELECTOR = "script#anubis_challenge, script#anubis-main"
    private const val BODY_SCAN_LIMIT = 12_000
}
