package com.vinicius741.webnovelarchiver.source.network

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.vinicius741.webnovelarchiver.ui.WebViewSafety
import org.json.JSONArray
import org.jsoup.Jsoup
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Renders a Cloudflare-blocked request with Chromium and returns the resulting DOM to OkHttp.
 *
 * A `cf_clearance` cookie is no longer sufficient evidence that an OkHttp retry will pass. Modern
 * Cloudflare clearance is tied to the browser/device session and is continuously re-evaluated, so
 * replaying a cookie minted by WebView through OkHttp's different network fingerprint can be
 * challenged again. The old implementation discarded the page WebView had successfully loaded and
 * retried through OkHttp, which caused the visible verification loop.
 *
 * This renderer keeps the successful browser response instead. It loads the original GET or form
 * POST in a detached WebView, waits until the main page is no longer a challenge, serializes the
 * rendered DOM, and hands it back to [CloudflareBypassInterceptor]. Interactive challenges still
 * time out and fall through to
 * [com.vinicius741.webnovelarchiver.feature.browser.CloudflareSolveActivity].
 */
object CloudflareWebViewSolver {
    private data class BrowserSession(
        var webView: WebView? = null,
    )

    private val sessions = ConcurrentHashMap<String, BrowserSession>()

    internal fun render(
        context: Context,
        request: CloudflareWebViewRequest,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): CloudflareRenderedPage? {
        CloudflareSolverThreadGuard.requireBackgroundThread(
            Looper.myLooper() == Looper.getMainLooper(),
        )
        val mainHandler = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        val renderedPage = AtomicReference<CloudflareRenderedPage?>(null)
        val requestClosed = AtomicBoolean(false)
        val session = sessions.getOrPut(hostKey(request.url)) { BrowserSession() }

        mainHandler.post {
            val web =
                session.webView
                    ?: WebView(context).also { created ->
                        WebViewSafety.applyBrowserSettings(created)
                        session.webView = created
                    }
            web.stopLoading()
            if (request.userAgent.isNotBlank()) web.settings.userAgentString = request.userAgent

            fun inspectPage(
                view: WebView,
                effectiveUrl: String,
                pollsRemaining: Int = MAX_DOM_POLLS,
            ) {
                if (requestClosed.get()) return
                view.evaluateJavascript("document.documentElement.outerHTML") { htmlJson ->
                    if (requestClosed.get()) return@evaluateJavascript
                    val html = decodeJavascriptString(htmlJson)
                    when {
                        SourceAccessBlockDetector.isChallengeHtml(html) && pollsRemaining > 0 ->
                            view.postDelayed(
                                { inspectPage(view, view.url ?: effectiveUrl, pollsRemaining - 1) },
                                DOM_POLL_INTERVAL_MILLIS,
                            )
                        CloudflareRenderedPageValidator.isExpectedPage(request, effectiveUrl, html) -> {
                            if (renderedPage.compareAndSet(null, CloudflareRenderedPage(html, effectiveUrl))) {
                                CloudflareCookies.flush()
                                latch.countDown()
                            }
                        }
                        html.isNotBlank() -> latch.countDown()
                    }
                }
            }

            web.webViewClient =
                object : WebViewClient() {
                    override fun onPageFinished(
                        view: WebView?,
                        pageUrl: String?,
                    ) {
                        val effectiveUrl = pageUrl ?: request.url
                        view?.let { inspectPage(it, effectiveUrl) }
                    }

                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: RenderProcessGoneDetail?,
                    ): Boolean {
                        session.webView = null
                        latch.countDown()
                        return true
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        failingRequest: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        if (failingRequest?.isForMainFrame == true) latch.countDown()
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        failingRequest: WebResourceRequest?,
                        errorResponse: WebResourceResponse?,
                    ) {
                        if (
                            failingRequest?.isForMainFrame == true &&
                            errorResponse?.statusCode !in setOf(403, 429, 503)
                        ) {
                            latch.countDown()
                        }
                    }
                }
            when (request.method) {
                "GET" -> web.loadUrl(request.url, request.headers)
                "POST" -> web.postUrl(request.url, request.postData ?: byteArrayOf())
                else -> latch.countDown()
            }
        }

        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        requestClosed.set(true)

        mainHandler.post {
            session.webView?.stopLoading()
        }
        return renderedPage.get()
    }

    /** Drops persistent renderer processes; CookieManager/WebStorage reset is owned by Settings. */
    fun destroySessions() {
        val existing = sessions.values.toList()
        sessions.clear()
        Handler(Looper.getMainLooper()).post {
            existing.forEach { session ->
                session.webView?.let(WebViewSafety::destroy)
                session.webView = null
            }
        }
    }

    private fun decodeJavascriptString(value: String?): String = runCatching { JSONArray("[$value]").getString(0) }.getOrDefault("")

    private fun hostKey(url: String): String =
        runCatching {
            java.net
                .URI(url)
                .host
                .orEmpty()
                .lowercase()
                .removePrefix("www.")
        }.getOrDefault(url)

    private const val DEFAULT_TIMEOUT_MS = 20_000L
    private const val DOM_POLL_INTERVAL_MILLIS = 500L
    private const val MAX_DOM_POLLS = 40
}

/** Pure validation keeps intermediate, error, and wrong-origin DOMs out of the source parsers. */
internal object CloudflareRenderedPageValidator {
    fun isExpectedPage(
        request: CloudflareWebViewRequest,
        finalUrl: String,
        html: String,
    ): Boolean {
        if (html.isBlank() || SourceAccessBlockDetector.isChallengeHtml(html)) return false
        val requestedHost = normalizedHost(request.url) ?: return false
        val finalHost = normalizedHost(finalUrl) ?: return false
        if (requestedHost != finalHost) return false

        val requestedPath =
            runCatching {
                java.net
                    .URI(request.url)
                    .path
                    .orEmpty()
                    .lowercase()
            }.getOrDefault("")
        val document = runCatching { Jsoup.parse(html) }.getOrNull() ?: return false
        return when {
            requestedPath.contains("/chapter/") && requestedHost == "scribblehub.com" -> document.selectFirst("#chp_raw") != null
            requestedPath.contains("/chapter/") && requestedHost == "royalroad.com" -> document.selectFirst(".chapter-inner") != null
            requestedPath.contains("/series/") ->
                document.selectFirst("#mypostid, .fic_title, .wi_fic_title") != null
            // An empty AJAX body is a valid "no more TOC pages" response.
            requestedPath.endsWith("/wp-admin/admin-ajax.php") -> true
            else -> document.body().html().isNotBlank()
        }
    }

    private fun normalizedHost(url: String): String? =
        runCatching {
            java.net
                .URI(url)
                .host
                ?.lowercase()
                ?.removePrefix("www.")
        }.getOrNull()
}

/** Pure guard kept separately so the main-thread fail-fast behavior has a JVM test. */
internal object CloudflareSolverThreadGuard {
    fun requireBackgroundThread(isMainThread: Boolean) {
        check(!isMainThread) {
            "The hidden Cloudflare WebView solver cannot block the main thread"
        }
    }
}
