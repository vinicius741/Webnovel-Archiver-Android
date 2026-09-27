package com.vinicius741.webnovelarchiver.source.network

import android.os.Handler
import android.os.SystemClock
import android.webkit.WebView

/** Runs a form POST through the same Chromium session as challenge verification. */
internal class CloudflareWebViewFormPost(
    private val web: WebView,
    private val request: CloudflareWebViewRequest,
    private val mainHandler: Handler,
    private val isClosed: () -> Boolean,
    private val onPage: (CloudflareRenderedPage) -> Unit,
    private val onFailure: (CloudflareRenderFailure) -> Unit,
    private val onPoll: (String) -> Unit,
) {
    fun start() {
        val bootstrapUrl = CloudflareFormPostPlanning.bootstrapUrl(request.url)
        if (bootstrapUrl == null) {
            onFailure(CloudflareRenderFailure.UnsupportedMethod)
            return
        }
        web.evaluateJavascript(BOOTSTRAP_STATE_SCRIPT) { json ->
            if (isClosed()) return@evaluateJavascript
            val page = CloudflarePageStateDecoder.decode(json)
            if (CloudflareFormPostPlanning.canReusePage(request.url, page)) {
                onPoll("formBootstrapReused=true")
                startFetch()
            } else {
                web.loadUrl(bootstrapUrl)
                mainHandler.post { inspectBootstrap() }
            }
        }
    }

    private fun inspectBootstrap(
        pollsRemaining: Int = MAX_POLLS,
        readySince: Long = 0L,
    ) {
        if (isClosed()) return
        web.evaluateJavascript(BOOTSTRAP_STATE_SCRIPT) { json ->
            if (isClosed()) return@evaluateJavascript
            val state = CloudflarePageStateDecoder.decode(json)
            val sameOrigin = CloudflareFormPostPlanning.sameOrigin(request.url, state.documentUrl)
            val challenge = SourceAccessBlockDetector.isChallengeHtml(state.html)
            val ready = sameOrigin && !challenge && state.readyState in setOf("interactive", "complete")
            val readyAt = if (ready) readySince.takeIf { it != 0L } ?: SystemClock.elapsedRealtime() else 0L
            onPoll("formBootstrapReady=${state.readyState} sameOrigin=$sameOrigin challenge=$challenge")
            if (ready && SystemClock.elapsedRealtime() - readyAt >= BOOTSTRAP_WAIT_MILLIS) {
                startFetch()
            } else if (pollsRemaining > 0) {
                mainHandler.postDelayed({ inspectBootstrap(pollsRemaining - 1, readyAt) }, POLL_INTERVAL_MILLIS)
            } else {
                onFailure(if (challenge) CloudflareRenderFailure.ChallengeActive else CloudflareRenderFailure.NavigationNeverCommitted)
            }
        }
    }

    private fun startFetch() {
        web.evaluateJavascript(CloudflareFormPostPlanning.fetchScript(request)) { seqJson ->
            val expectedSeq = seqJson?.trim()?.toIntOrNull() ?: return@evaluateJavascript
            inspectResult(expectedSeq)
        }
    }

    private fun inspectResult(
        expectedSeq: Int,
        pollsRemaining: Int = MAX_POLLS,
    ) {
        if (isClosed()) return
        web.evaluateJavascript(POST_RESULT_SCRIPT) { json ->
            if (isClosed()) return@evaluateJavascript
            val result = CloudflareFormPostPlanning.decodeResult(json, expectedSeq)
            if (result == null) {
                if (pollsRemaining > 0) {
                    mainHandler.postDelayed({ inspectResult(expectedSeq, pollsRemaining - 1) }, POLL_INTERVAL_MILLIS)
                } else {
                    onFailure(CloudflareRenderFailure.NeverSettled)
                }
                return@evaluateJavascript
            }
            onPoll("formStatus=${result.status} challenge=${result.mitigated} bodyLen=${result.body.length}")
            when {
                result.status == 0 ||
                    !CloudflareFormPostPlanning.sameOrigin(request.url, result.url) ||
                    !CloudflareRenderedPageValidator.matchesRequestedResource(request, result.url) ->
                    onFailure(CloudflareRenderFailure.MainFrameTransportError)
                result.mitigated || SourceAccessBlockDetector.isChallengeHtml(result.body) ->
                    onFailure(CloudflareRenderFailure.ChallengeActive)
                result.status !in 200..299 ->
                    onFailure(CloudflareRenderFailure.MainFrameHttpError(result.status, result.retryAfter))
                else -> onPage(CloudflareRenderedPage(result.body, result.url))
            }
        }
    }

    private companion object {
        const val BOOTSTRAP_STATE_SCRIPT =
            "(function(){try{return JSON.stringify({documentUrl:location.href,readyState:document.readyState," +
                "stale:window.__wnaRenderStale===true," +
                "html:document.documentElement?document.documentElement.outerHTML.slice(0,12000):''})}" +
                "catch(e){return '{\"documentUrl\":\"\",\"readyState\":\"\",\"stale\":false,\"html\":\"\"}'}})()"
        const val POST_RESULT_SCRIPT = "JSON.stringify(window.__wnaFormPostResult)"
        const val POLL_INTERVAL_MILLIS = 500L
        const val BOOTSTRAP_WAIT_MILLIS = 1_000L
        const val MAX_POLLS = 40
    }
}
