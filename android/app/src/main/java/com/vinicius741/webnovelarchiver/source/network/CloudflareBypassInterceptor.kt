package com.vinicius741.webnovelarchiver.source.network

import android.content.Context
import okhttp3.Interceptor
import okhttp3.Response
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * OkHttp [Interceptor] that solves Cloudflare challenges in a background WebView before the response
 * reaches [NetworkClient.executeWithRetries].
 *
 * Flow: proceed with the request → if [SourceAccessBlockDetector.isChallengeResponse] recognizes a
 * real CF challenge (403/503 + Cloudflare server header + challenge DOM, or `cf-mitigated:
 * challenge`), acquire this host's per-host lock so concurrent requests to the same host wait instead
 * of each spawning their own WebView, run [CloudflareWebViewSolver.solve], and on success retry
 * `chain.proceed(request)` so the now-cookie-bearing request goes out. On failure the original
 * challenge response is returned unchanged and [NetworkClient] surfaces it as
 * [SourceAccessBlockedException] exactly as before — at which point the UI layer offers the visible
 * [com.vinicius741.webnovelarchiver.feature.browser.CloudflareSolveActivity] fallback.
 *
 * Guardrails (per the ScribbleHub-options doc's cautions about hidden WebViews):
 *  - One solve attempt per host at a time; concurrent callers block on [HostGate.lock] and benefit
 *    from the in-flight solve rather than multiplying WebViews.
 *  - 20s hard timeout inside the solver; this interceptor never blocks indefinitely.
 *  - Detection is delegated to the existing conservative [SourceAccessBlockDetector], so the solver
 *    never fires on normal Cloudflare-proxied content or on a geo-block (which no cookie can fix).
 */
class CloudflareBypassInterceptor(
    private val context: Context,
) : Interceptor {
    /** Per-host serialization so concurrent blocked requests share one WebView solve. */
    private data class HostGate(
        val lock: ReentrantLock = ReentrantLock(),
    )

    private val hostGates = ConcurrentHashMap<String, HostGate>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (!isChallengeResponse(response)) return response

        val host = request.url.host
        val gate = hostGates.getOrPut(host) { HostGate() }
        if (!gate.lock.tryLock()) {
            // Another request for this host is already solving. Wait for it, then retry once: if the
            // in-flight solve earned a clearance, this retry will carry it (the cookie jar is shared).
            gate.lock.lockInterruptibly()
            try {
                return retry(chain, response)
            } finally {
                gate.lock.unlock()
            }
        }
        try {
            val solved =
                CloudflareWebViewSolver.solve(
                    context = context,
                    url = request.url.toString(),
                    userAgent = SourceUserAgent.resolved,
                    headers = safeWebViewHeaders(request.headers),
                )
            if (solved) return retry(chain, response)
        } finally {
            gate.lock.unlock()
        }
        // Solve failed (timeout or interactive challenge): surface the original challenge response
        // so NetworkClient throws SourceAccessBlockedException and the UI offers the manual fallback.
        return response
    }

    private fun retry(
        chain: Interceptor.Chain,
        exhausted: Response,
    ): Response {
        exhausted.close()
        return chain.proceed(chain.request())
    }

    /**
     * Conservative challenge detection delegating to the existing [SourceAccessBlockDetector].
     * Uses [Response.peekBody] so the body remains readable downstream if the solve fails and the
     * response is returned to [NetworkClient] (which reads the body for its own challenge check).
     */
    private fun isChallengeResponse(response: Response): Boolean {
        if (response.code !in CHALLENGE_CODES) return false
        val bodyString =
            runCatching { response.peekBody(BODY_PEEK_BYTES).string() }.getOrNull().orEmpty()
        return SourceAccessBlockDetector.isChallengeResponse(response.headers, bodyString)
    }

    private fun safeWebViewHeaders(headers: okhttp3.Headers): Map<String, String> =
        headers
            .filter { (name, value) -> isRequestHeaderSafe(name, value) }
            .groupBy(keySelector = { (name, _) -> name }) { (_, value) -> value }
            .mapValues { it.value.firstOrNull().orEmpty() }

    private fun isRequestHeaderSafe(
        rawName: String,
        rawValue: String,
    ): Boolean {
        val name = rawName.lowercase(Locale.US)
        val value = rawValue.lowercase(Locale.US)
        if (name in UNSAFE_HEADER_NAMES || name.startsWith("proxy-")) return false
        if (name == "connection" && value == "upgrade") return false
        return true
    }

    private companion object {
        val CHALLENGE_CODES = setOf(403, 503)
        const val BODY_PEEK_BYTES = 64_000L
        val UNSAFE_HEADER_NAMES =
            setOf(
                "content-length",
                "host",
                "trailer",
                "te",
                "upgrade",
                "cookie2",
                "keep-alive",
                "transfer-encoding",
                "set-cookie",
            )
    }
}
