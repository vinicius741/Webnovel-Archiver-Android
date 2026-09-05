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
import com.vinicius741.webnovelarchiver.data.diagnostics.BypassEventCategory
import com.vinicius741.webnovelarchiver.data.diagnostics.BypassEventLog
import com.vinicius741.webnovelarchiver.platform.WebViewSafety
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Renders a Cloudflare-blocked request in a detached WebView and returns the DOM to OkHttp. A
 * cf_clearance cookie replayed through OkHttp's different network fingerprint can be re-challenged,
 * so the response WebView actually loaded is kept instead of retried. Interactive challenges still
 * fall through to [com.vinicius741.webnovelarchiver.feature.browser.CloudflareSolveActivity].
 *
 * Polling runs on its own timer, not WebView page callbacks: some device WebView builds omit
 * onPageStarted/onPageFinished for a detached WebView even though the document loads.
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
    ): CloudflareRenderOutcome {
        CloudflareSolverThreadGuard.requireBackgroundThread(
            Looper.myLooper() == Looper.getMainLooper(),
        )
        val mainHandler = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        val renderedPage = AtomicReference<CloudflareRenderedPage?>(null)
        val requestClosed = AtomicBoolean(false)
        val failure = AtomicReference<CloudflareRenderFailure?>(null)
        // The poll budget races the await timeout; the last observed document state carries the reason either way.
        val lastPollNote = AtomicReference("no poll completed")
        val session = sessions.getOrPut(hostKey(request.url)) { BrowserSession() }
        val renderId = BypassEventLog.nextId("r")
        BypassEventLog.record(
            BypassEventCategory.CF,
            "render_start",
            hostKey(request.url),
            "renderId" to renderId,
            "method" to request.method,
        )

        fun finishWith(failed: CloudflareRenderFailure) {
            failure.compareAndSet(null, failed)
            latch.countDown()
        }

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
                view.evaluateJavascript(PAGE_STATE_SCRIPT) { json ->
                    if (requestClosed.get()) return@evaluateJavascript
                    val state = CloudflarePageStateDecoder.decode(json)
                    val documentUrl = state.documentUrl.ifBlank { effectiveUrl }
                    val isChallenge = SourceAccessBlockDetector.isChallengeHtml(state.html)
                    lastPollNote.set(
                        "url=${state.documentUrl} readyState=${state.readyState} stale=${state.stale} " +
                            "challenge=$isChallenge htmlLen=${state.html.length}",
                    )
                    val isRequestedResource =
                        CloudflareRenderedPageValidator.matchesRequestedResource(request, documentUrl)
                    val decision =
                        CloudflareRenderPollPlanning.decide(
                            isStaleDocument = state.stale,
                            documentUrl = documentUrl,
                            readyState = state.readyState,
                            isChallenge = isChallenge,
                            isRequestedResource = isRequestedResource,
                            isExpected = {
                                CloudflareRenderedPageValidator.isExpectedPage(request, documentUrl, state.html)
                            },
                        )
                    recordPoll(renderId, hostKey(request.url), pollsRemaining, state, isChallenge, isRequestedResource, decision)
                    when (decision) {
                        CloudflareRenderPollDecision.ACCEPT_PAGE ->
                            if (renderedPage.compareAndSet(null, CloudflareRenderedPage(state.html, documentUrl))) {
                                CloudflareCookies.flush()
                                latch.countDown()
                            }

                        CloudflareRenderPollDecision.KEEP_POLLING ->
                            if (pollsRemaining > 0) {
                                mainHandler.postDelayed(
                                    { inspectPage(view, view.url ?: effectiveUrl, pollsRemaining - 1) },
                                    DOM_POLL_INTERVAL_MILLIS,
                                )
                            } else {
                                when {
                                    isChallenge -> finishWith(CloudflareRenderFailure.ChallengeActive)
                                    state.stale -> finishWith(CloudflareRenderFailure.StaleDocumentPersisted)
                                    !isRequestedResource -> finishWith(CloudflareRenderFailure.NavigationNeverCommitted)
                                    else -> finishWith(CloudflareRenderFailure.NeverSettled)
                                }
                            }

                        CloudflareRenderPollDecision.REJECT_PAGE ->
                            // Return the settled-but-unexpected page: the parser's selector check
                            // fails one job with a typed error instead of blocking the source.
                            finishWith(
                                CloudflareRenderFailure.PageContentUnexpected(
                                    CloudflareRenderedPage(state.html, documentUrl),
                                ),
                            )
                    }
                }
            }

            web.webViewClient = renderClient(session, ::finishWith)
            // The persistent session WebView still shows the previous request's page; mark it stale
            // before navigating (from the marker's own callback) so a fresh document is never marked.
            web.evaluateJavascript(STALE_MARKER_SCRIPT) {
                val navigationIssued =
                    when (request.method) {
                        "GET" -> {
                            web.loadUrl(request.url, request.headers)
                            true
                        }
                        "POST" -> {
                            web.postUrl(request.url, request.postData ?: byteArrayOf())
                            true
                        }
                        else -> false
                    }
                if (navigationIssued) {
                    // View.post queues work until attachment on Samsung; this WebView is deliberately detached, so use the main Looper.
                    mainHandler.post { inspectPage(web, request.url) }
                } else {
                    finishWith(CloudflareRenderFailure.UnsupportedMethod)
                }
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
        return completedOutcome(request, renderId, renderedPage, failure, lastPollNote)
    }

    private fun renderClient(
        session: BrowserSession,
        finishWith: (CloudflareRenderFailure) -> Unit,
    ): WebViewClient =
        object : WebViewClient() {
            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?,
            ): Boolean {
                finishWith(CloudflareRenderFailure.RenderProcessGone)
                // A gone renderer leaves the WebView unusable; destroy it (R18) so a clean
                // replacement can be created, instead of only dropping the session reference.
                view?.let(WebViewSafety::destroy)
                session.webView = null
                return true
            }

            override fun onReceivedError(
                view: WebView?,
                failingRequest: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (failingRequest?.isForMainFrame == true) {
                    finishWith(CloudflareRenderFailure.MainFrameTransportError)
                }
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
                    finishWith(
                        CloudflareRenderFailure.MainFrameHttpError(errorResponse?.statusCode ?: 500),
                    )
                }
            }
        }

    private fun completedOutcome(
        request: CloudflareWebViewRequest,
        renderId: String,
        renderedPage: AtomicReference<CloudflareRenderedPage?>,
        failure: AtomicReference<CloudflareRenderFailure?>,
        lastPollNote: AtomicReference<String>,
    ): CloudflareRenderOutcome {
        val outcome =
            renderedPage.get()?.let(CloudflareRenderOutcome::Rendered)
                ?: CloudflareRenderOutcomePlanning.outcome(failure.get())
        val failed = failure.get() ?: CloudflareRenderFailure.TimedOut
        val outcomeName =
            when (outcome) {
                is CloudflareRenderOutcome.Rendered -> "rendered"
                is CloudflareRenderOutcome.PageContentUnexpected -> "page_content_unexpected"
                is CloudflareRenderOutcome.OriginHttpError -> "origin_http_${outcome.statusCode}"
                CloudflareRenderOutcome.TransportError -> "transport_error"
                is CloudflareRenderOutcome.NeedsManualVerification -> "needs_manual:${failed.note}"
            }
        BypassEventLog.record(
            BypassEventCategory.CF,
            "render_finished",
            hostKey(request.url),
            "renderId" to renderId,
            "outcome" to outcomeName,
        )
        if (outcome !is CloudflareRenderOutcome.Rendered) {
            val reason =
                if (failed is CloudflareRenderFailure.MainFrameHttpError) {
                    "main_frame_http_${failed.statusCode}"
                } else {
                    failed.note
                }
            Timber.w(
                "Cloudflare WebView render gave up: url=%s note=%s lastPoll=[%s]",
                request.url,
                reason,
                lastPollNote.get(),
            )
        }
        return outcome
    }

    private fun recordPoll(
        renderId: String,
        host: String,
        pollsRemaining: Int,
        state: CloudflarePageState,
        isChallenge: Boolean,
        isRequestedResource: Boolean,
        decision: CloudflareRenderPollDecision,
    ) {
        BypassEventLog.record(
            BypassEventCategory.CF,
            "render_poll",
            host,
            "renderId" to renderId,
            "pollN" to (MAX_DOM_POLLS - pollsRemaining),
            "readyState" to state.readyState,
            "stale" to state.stale,
            "challenge" to isChallenge,
            "resource" to isRequestedResource,
            "decision" to decision.name.substringBefore("_").lowercase(),
        )
    }

    /** Destroys renderer processes; CookieManager/WebStorage reset is owned by Settings. */
    fun destroySessions() {
        val existing = sessions.values.toList()
        sessions.clear()
        val destroy = {
            existing.forEach { session ->
                session.webView?.let(WebViewSafety::destroy)
                session.webView = null
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            destroy()
        } else {
            Handler(Looper.getMainLooper()).post(destroy)
        }
    }

    private fun hostKey(url: String): String =
        runCatching {
            java.net
                .URI(url)
                .host
                .orEmpty()
                .lowercase()
                .removePrefix("www.")
        }.getOrDefault(url)

    private const val STALE_MARKER_SCRIPT = "window.__wnaRenderStale=true"

    private const val PAGE_STATE_SCRIPT =
        "(function(){try{return JSON.stringify({documentUrl:location.href,readyState:document.readyState," +
            "stale:window.__wnaRenderStale===true," +
            "html:document.documentElement?document.documentElement.outerHTML:''})}" +
            "catch(e){return '{\"documentUrl\":\"\",\"readyState\":\"\",\"stale\":false,\"html\":\"\"}'}})()"

    private const val DEFAULT_TIMEOUT_MS = 20_000L
    private const val DOM_POLL_INTERVAL_MILLIS = 500L
    private const val MAX_DOM_POLLS = 40
}

/** Pure guard kept separately so the main-thread fail-fast behavior has a JVM test. */
internal object CloudflareSolverThreadGuard {
    fun requireBackgroundThread(isMainThread: Boolean) {
        check(!isMainThread) {
            "The hidden Cloudflare WebView solver cannot block the main thread"
        }
    }
}
