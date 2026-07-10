package com.vinicius741.webnovelarchiver.source.network

import okhttp3.Headers

class SourceAccessBlockedException(
    val blockedUrl: String,
    message: String = "Cloudflare is blocking automated access. Open the source in the browser, pass the check, then retry.",
) : NetworkException(message)

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
     * Strong Cloudflare challenge HTML markers. These are structural interstitial fragments that
     * effectively never appear in chapter prose. NOTE: the older "enable javascript and cookies to
     * continue" phrase is deliberately omitted — it is plausible in story prose and a strong marker
     * always accompanies it on a real interstitial, so it adds risk without aiding detection.
     */
    fun isChallengeHtml(body: String): Boolean {
        val lower = body.take(BODY_SCAN_LIMIT).lowercase()
        return lower.contains("cf_chl") ||
            lower.contains("cf-mitigated") ||
            lower.contains("challenge-platform") ||
            lower.contains("<title>just a moment...</title>")
    }

    private const val BODY_SCAN_LIMIT = 12_000
}
