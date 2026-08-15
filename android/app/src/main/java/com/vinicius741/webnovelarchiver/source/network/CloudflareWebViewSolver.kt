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
 *
 * Page inspection runs on its own timer starting when navigation is issued, not from WebView page
 * callbacks: some physical-device WebView builds omit both `onPageStarted` and `onPageFinished` for
 * a detached WebView even though the document loads. Waiting for either callback left the renderer
 * with no DOM poll at all. [CloudflareRenderedPageValidator] checks the requested resource as well
 * as the host and expected DOM shape, and the outgoing document is marked stale before each
 * navigation, so polling cannot decide on a previous session document before the fresh navigation
 * commits.
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
        // The 20s poll budget races the 20s await timeout, so the budget-exhaustion failure can lose;
        // the last observed document state carries the reason either way.
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
                            // The right page settled but failed the content rules. Return it: the
                            // source parser's own selector check fails the single job with a typed
                            // parse error instead of blocking the whole source.
                            finishWith(
                                CloudflareRenderFailure.PageContentUnexpected(
                                    CloudflareRenderedPage(state.html, documentUrl),
                                ),
                            )
                    }
                }
            }

            web.webViewClient = renderClient(session, ::finishWith)
            // Mark the outgoing document before navigating: the session WebView is persistent, so
            // it still shows the previous request's page, and the first polls run before this
            // navigation commits. The marker lives in the old document's window, so any freshly
            // loaded document starts clean. Navigation is issued from the marker's callback so the
            // fresh document can never be marked by mistake.
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
                    // Use the main Looper directly. View.post queues work until attachment on Samsung,
                    // and this renderer is deliberately detached, so a WebView-owned timer never runs.
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

    /** Typed failure reporting for main-frame events during one render. */
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

    /** Assembles the render's final outcome and records/logs the non-success reasons. */
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

    /** One structured log line per DOM poll — the diagnostic core of a render. */
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

    /** Drops persistent renderer processes; CookieManager/WebStorage reset is owned by Settings. */
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
