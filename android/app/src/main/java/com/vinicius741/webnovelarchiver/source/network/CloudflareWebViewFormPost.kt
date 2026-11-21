package com.vinicius741.webnovelarchiver.source.network

import android.os.Handler
import android.os.SystemClock
import android.webkit.WebView

/** Runs a form POST or JSON GET as a same-origin `fetch` in the Chromium session that passed verification. */
internal class CloudflareWebViewFormPost(
    private val web: WebView,
    private val request: CloudflareWebViewRequest,
    private val mainHandler: Handler,
    private val isClosed: () -> Boolean,
    private val onPage: (CloudflareRenderedPage) -> Unit,
    private val onFailure: (CloudflareRenderFailure) -> Unit,
    private val onPoll: (note: String, challenge: Boolean) -> Unit,
    private val javascript: GuardedJavascript,
) {
    fun start() {
        val bootstrapUrl = CloudflareFormPostPlanning.bootstrapUrl(request.url)
        if (bootstrapUrl == null) {
            onFailure(CloudflareRenderFailure.UnsupportedMethod)
            return
        }
        val openBootstrap = {
            web.loadUrl(bootstrapUrl)
            mainHandler.post { inspectBootstrap() }
            Unit
        }
        javascript.evaluate(web, BOOTSTRAP_STATE_SCRIPT, onLost = { if (!isClosed()) openBootstrap() }) { json ->
            if (isClosed()) return@evaluate
            val page = CloudflarePageStateDecoder.decode(json)
            if (CloudflareFormPostPlanning.canReusePage(request.url, page)) {
                onPoll("formBootstrapReused=true", false)
                startFetch()
            } else {
                openBootstrap()
            }
        }
    }

    private fun inspectBootstrap(
        pollsRemaining: Int = MAX_POLLS,
        readySince: Long = 0L,
    ) {
        if (isClosed()) return
        val onLost = {
            when {
                isClosed() -> Unit
                pollsRemaining > 0 -> inspectBootstrap(pollsRemaining - 1, readySince)
                else -> onFailure(CloudflareRenderFailure.NavigationNeverCommitted)
            }
        }
        javascript.evaluate(web, BOOTSTRAP_STATE_SCRIPT, onLost) { json ->
            if (isClosed()) return@evaluate
            val state = CloudflarePageStateDecoder.decode(json)
            val sameOrigin = CloudflareFormPostPlanning.sameOrigin(request.url, state.documentUrl)
            val challenge = SourceAccessBlockDetector.isChallengeHtml(state.html)
            val ready = sameOrigin && !challenge && state.readyState in setOf("interactive", "complete")
            val readyAt = if (ready) readySince.takeIf { it != 0L } ?: SystemClock.elapsedRealtime() else 0L
            onPoll("formBootstrapReady=${state.readyState} sameOrigin=$sameOrigin challenge=$challenge", challenge)
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
        // Without the sequence number the fetch's result cannot be told from an older one.
        val onLost = { if (!isClosed()) onFailure(CloudflareRenderFailure.NeverSettled) }
        javascript.evaluate(web, CloudflareFormPostPlanning.fetchScript(request), onLost) { seqJson ->
            val expectedSeq = seqJson?.trim()?.toIntOrNull() ?: return@evaluate
            inspectResult(expectedSeq)
        }
    }

    private fun inspectResult(
        expectedSeq: Int,
        pollsRemaining: Int = MAX_POLLS,
    ) {
        if (isClosed()) return
        val onLost = {
            when {
                isClosed() -> Unit
                pollsRemaining > 0 -> inspectResult(expectedSeq, pollsRemaining - 1)
                else -> onFailure(CloudflareRenderFailure.NeverSettled)
            }
        }
        javascript.evaluate(web, POST_RESULT_SCRIPT, onLost) { json ->
            if (isClosed()) return@evaluate
            val result = CloudflareFormPostPlanning.decodeResult(json, expectedSeq)
            if (result == null) {
                if (pollsRemaining > 0) {
                    mainHandler.postDelayed({ inspectResult(expectedSeq, pollsRemaining - 1) }, POLL_INTERVAL_MILLIS)
                } else {
                    onFailure(CloudflareRenderFailure.NeverSettled)
                }
                return@evaluate
            }
            val mitigated = result.mitigated || SourceAccessBlockDetector.isChallengeHtml(result.body)
            onPoll("formStatus=${result.status} challenge=${result.mitigated} bodyLen=${result.body.length}", mitigated)
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
