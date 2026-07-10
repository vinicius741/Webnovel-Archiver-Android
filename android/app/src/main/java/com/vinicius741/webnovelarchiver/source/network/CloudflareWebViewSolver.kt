package com.vinicius741.webnovelarchiver.source.network

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import com.vinicius741.webnovelarchiver.ui.WebViewSafety
import org.json.JSONArray
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Solves a Cloudflare challenge in an off-screen, detached [WebView] and lets the resulting
 * `cf_clearance` cookie land in the shared [android.webkit.CookieManager] (which [AndroidCookieJar]
 * then serves to OkHttp). This is the Mihon "helper WebView": never attached to a view hierarchy,
 * never seen by the user, destroyed as soon as a clearance cookie appears or the timeout elapses.
 *
 * Why a real WebView is required: Cloudflare 2026 gates on TLS/JA4 + HTTP/2 + a JS challenge that
 * only a genuine browser engine can pass. The system WebView is real Chromium, so it clears all
 * those layers natively; OkHttp alone cannot.
 *
 * Must be entered from a background thread — it posts WebView creation to the main [Looper] (WebViews
 * refuse to be touched off the main thread) and blocks the caller on a [CountDownLatch] until the
 * solve completes or [timeoutMs] expires. The single hard timeout protects callers from blocking
 * indefinitely on a challenge the WebView can't solve unattended (e.g. interactive Turnstile) — that
 * case is left to the visible [com.vinicius741.webnovelarchiver.feature.browser.CloudflareSolveActivity].
 *
 * @return true when a fresh `cf_clearance` was captured before the timeout.
 */
object CloudflareWebViewSolver {
    fun solve(
        context: Context,
        url: String,
        userAgent: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Boolean {
        CloudflareSolverThreadGuard.requireBackgroundThread(
            Looper.myLooper() == Looper.getMainLooper(),
        )
        val mainHandler = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        // Written on the main thread, read on the calling background thread — including the timeout
        // path where the latch did NOT count down, so there's no happens-before edge to rely on.
        // AtomicBoolean gives a safe cross-thread read regardless of how await returns.
        val solved = AtomicBoolean(false)
        var webView: WebView? = null

        mainHandler.post {
            // Drop any stale clearance first so a *new* grant is detectable in onPageFinished — a
            // leftover cookie from a previous solve would otherwise make every page load look solved.
            CloudflareCookies.clearClearance(url)
            val web =
                WebView(context).apply {
                    WebViewSafety.applyBrowserSettings(this)
                    // Match the OkHttp UA exactly: Cloudflare binds cf_clearance to the UA, so a
                    // mismatch between the solving WebView and the OkHttp replay invalidates the cookie.
                    if (userAgent.isNotBlank()) {
                        settings.userAgentString = userAgent
                    }
                    webViewClient =
                        object : WebViewClient() {
                            override fun onPageFinished(
                                view: WebView?,
                                pageUrl: String?,
                            ) {
                                val effectiveUrl = pageUrl ?: url
                                if (!hasClearance(effectiveUrl)) return
                                view?.evaluateJavascript("document.documentElement.outerHTML") { htmlJson ->
                                    val html = decodeJavascriptString(htmlJson)
                                    if (!SourceAccessBlockDetector.isChallengeHtml(html) && hasClearance(effectiveUrl)) {
                                        CloudflareCookies.flush()
                                        solved.set(true)
                                        latch.countDown()
                                    }
                                }
                            }

                            override fun onRenderProcessGone(
                                view: WebView?,
                                detail: RenderProcessGoneDetail?,
                            ): Boolean {
                                latch.countDown()
                                return true
                            }
                        }
                }
            webView = web
            web.loadUrl(url, headers)
        }

        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            // Treat interruption as a failed solve; the caller proceeds without a clearance cookie.
            Thread.currentThread().interrupt()
        }

        // Always tear the WebView down on the main thread, whether solved, timed out, or interrupted.
        mainHandler.post {
            webView?.let { web ->
                runCatching {
                    web.stopLoading()
                    web.destroy()
                }
            }
        }
        return solved.get()
    }

    private fun hasClearance(url: String): Boolean =
        CloudflareCookies.hasClearance(url) || CloudflareCookies.hasClearance(SCRIBBLE_HUB_BASE_URL)

    private fun decodeJavascriptString(value: String?): String = runCatching { JSONArray("[$value]").getString(0) }.getOrDefault("")

    private const val DEFAULT_TIMEOUT_MS = 20_000L
    private const val SCRIBBLE_HUB_BASE_URL = "https://www.scribblehub.com/"
}

/** Pure guard kept separately so the main-thread fail-fast behavior has a JVM test. */
internal object CloudflareSolverThreadGuard {
    fun requireBackgroundThread(isMainThread: Boolean) {
        check(!isMainThread) {
            "The hidden Cloudflare WebView solver cannot block the main thread"
        }
    }
}
