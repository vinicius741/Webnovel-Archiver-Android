package com.vinicius741.webnovelarchiver.source.network

import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.source.SourceUrlKind
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

internal enum class CloudflareRenderPollDecision {
    /** Serialize this DOM and finish the render successfully. */
    ACCEPT_PAGE,

    /** The document is still loading or solving; poll again. */
    KEEP_POLLING,

    /** A settled document that is neither the expected page nor a challenge; stop early. */
    REJECT_PAGE,
}

/** Pure per-poll decision for the solver's DOM polling loop. */
internal object CloudflareRenderPollPlanning {
    fun decide(
        isStaleDocument: Boolean,
        documentUrl: String,
        readyState: String,
        isChallenge: Boolean,
        isRequestedResource: Boolean,
        isExpected: () -> Boolean,
    ): CloudflareRenderPollDecision =
        when {
            // The document predates this request's navigation (the session WebView is persistent);
            // never decide on it, or a same-URL retry would instantly accept or reject last
            // request's page before the fresh navigation commits.
            isStaleDocument -> CloudflareRenderPollDecision.KEEP_POLLING
            documentUrl.isBlank() || documentUrl == "about:blank" -> CloudflareRenderPollDecision.KEEP_POLLING
            isChallenge -> CloudflareRenderPollDecision.KEEP_POLLING
            !isSettled(readyState) -> CloudflareRenderPollDecision.KEEP_POLLING
            !isRequestedResource -> CloudflareRenderPollDecision.KEEP_POLLING
            isExpected() -> CloudflareRenderPollDecision.ACCEPT_PAGE
            else -> CloudflareRenderPollDecision.REJECT_PAGE
        }

    private fun isSettled(readyState: String): Boolean = readyState == "interactive" || readyState == "complete"
}

/** One poll's view of the hidden WebView's current document. */
internal data class CloudflarePageState(
    val documentUrl: String,
    val readyState: String,
    val html: String,
    val stale: Boolean = false,
)

/**
 * Why a Chromium render ended without an accepted page. The distinction is escalation policy:
 * only some of these mean Cloudflare needs a human; the rest are per-request outcomes the
 * ordinary error paths (parser, HTTP status, retryable transport) already handle per job.
 * [note] is a fixed snake_case vocabulary for logs and diagnostics.
 */
internal sealed interface CloudflareRenderFailure {
    val note: String

    /** An interactive challenge stayed unsolved for the whole poll budget. */
    data object ChallengeActive : CloudflareRenderFailure {
        override val note = "challenge_still_active"
    }

    /** The previous request's document was still in place; navigation never committed. */
    data object StaleDocumentPersisted : CloudflareRenderFailure {
        override val note = "stale_document_persisted"
    }

    /** Navigation committed to a resource other than the one requested. */
    data object NavigationNeverCommitted : CloudflareRenderFailure {
        override val note = "navigation_never_committed"
    }

    /** The document never reached interactive/complete before the poll budget ran out. */
    data object NeverSettled : CloudflareRenderFailure {
        override val note = "never_settled"
    }

    /** The right page settled but failed the source's content rules; the parser owns the verdict. */
    data class PageContentUnexpected(
        val page: CloudflareRenderedPage,
    ) : CloudflareRenderFailure {
        override val note = "page_content_unexpected"
    }

    /** The origin answered a definitive non-challenge HTTP status on the main frame. */
    data class MainFrameHttpError(
        val statusCode: Int,
    ) : CloudflareRenderFailure {
        override val note = "main_frame_http_error"
    }

    /** The main frame failed at the transport level; a retry may succeed. */
    data object MainFrameTransportError : CloudflareRenderFailure {
        override val note = "main_frame_transport_error"
    }

    /** The Chromium renderer process died. */
    data object RenderProcessGone : CloudflareRenderFailure {
        override val note = "render_process_gone"
    }

    /** The request shape cannot be loaded through WebView's top-level APIs. */
    data object UnsupportedMethod : CloudflareRenderFailure {
        override val note = "unsupported_method"
    }

    /** The await timeout elapsed before any poll decided. */
    data object TimedOut : CloudflareRenderFailure {
        override val note = "timed_out"
    }
}

/** What [CloudflareBypassInterceptor] should do with a finished render. */
internal sealed interface CloudflareRenderOutcome {
    data class Rendered(
        val page: CloudflareRenderedPage,
    ) : CloudflareRenderOutcome

    /** Cloudflare passed but the page failed content rules: return it and let the parser fail the job. */
    data class PageContentUnexpected(
        val page: CloudflareRenderedPage,
    ) : CloudflareRenderOutcome

    /** The origin's own non-challenge status: existing per-source retry policy applies. */
    data class OriginHttpError(
        val statusCode: Int,
    ) : CloudflareRenderOutcome

    /** Transient render failure: surfaces as a retryable transport error. */
    data object TransportError : CloudflareRenderOutcome

    /** The challenge needs a human: open the manual-verification circuit. */
    data class NeedsManualVerification(
        val failure: CloudflareRenderFailure,
    ) : CloudflareRenderOutcome
}

/** Pure mapping from a render failure to the interceptor's escalation decision. */
internal object CloudflareRenderOutcomePlanning {
    fun outcome(failure: CloudflareRenderFailure?): CloudflareRenderOutcome =
        when (failure) {
            null -> CloudflareRenderOutcome.NeedsManualVerification(CloudflareRenderFailure.TimedOut)
            is CloudflareRenderFailure.PageContentUnexpected ->
                CloudflareRenderOutcome.PageContentUnexpected(failure.page)
            is CloudflareRenderFailure.MainFrameHttpError ->
                CloudflareRenderOutcome.OriginHttpError(failure.statusCode)
            CloudflareRenderFailure.MainFrameTransportError -> CloudflareRenderOutcome.TransportError
            CloudflareRenderFailure.ChallengeActive,
            CloudflareRenderFailure.StaleDocumentPersisted,
            CloudflareRenderFailure.NavigationNeverCommitted,
            CloudflareRenderFailure.NeverSettled,
            CloudflareRenderFailure.RenderProcessGone,
            CloudflareRenderFailure.UnsupportedMethod,
            CloudflareRenderFailure.TimedOut,
            -> CloudflareRenderOutcome.NeedsManualVerification(failure)
        }
}

/** Decodes the JSON string wrapper returned by `WebView.evaluateJavascript`. */
internal object CloudflarePageStateDecoder {
    fun decode(raw: String?): CloudflarePageState {
        val unwrapped = runCatching { JSONArray("[$raw]").getString(0) }.getOrDefault("")
        val candidate =
            unwrapped.takeIf { it.startsWith("{") }
                ?: raw.orEmpty().takeIf { it.startsWith("{") }
                ?: return CloudflarePageState("", "", "")
        return runCatching {
            val obj = JSONObject(candidate)
            CloudflarePageState(
                documentUrl = obj.optString("documentUrl"),
                readyState = obj.optString("readyState"),
                html = obj.optString("html"),
                stale = obj.optBoolean("stale"),
            )
        }.getOrDefault(CloudflarePageState("", "", ""))
    }
}

/** Pure validation keeps intermediate, error, and wrong-resource DOMs out of source parsers. */
internal object CloudflareRenderedPageValidator {
    fun isExpectedPage(
        request: CloudflareWebViewRequest,
        finalUrl: String,
        html: String,
    ): Boolean {
        if (SourceAccessBlockDetector.isChallengeHtml(html)) return false
        val requestedHost = normalizedHost(request.url) ?: return false
        val finalHost = normalizedHost(finalUrl) ?: return false
        if (requestedHost != finalHost || !matchesRequestedResource(request, finalUrl)) return false

        val requestedPath =
            runCatching {
                java.net
                    .URI(request.url)
                    .path
                    .orEmpty()
                    .lowercase()
            }.getOrDefault("")
        val rule =
            SourceRegistry
                .getProvider(request.url)
                ?.descriptor
                ?.renderedPageRules
                ?.firstOrNull { it.matches(requestedPath) }
        if (html.isBlank()) return rule?.allowEmptyBody == true
        val document = runCatching { Jsoup.parse(html) }.getOrNull() ?: return false
        return rule?.requiredSelector?.let { document.selectFirst(it) != null }
            ?: document.body().html().isNotBlank()
    }

    private fun normalizedHost(url: String): String? =
        runCatching {
            java.net
                .URI(url)
                .host
                ?.lowercase()
                ?.removePrefix("www.")
                ?.removePrefix("m.")
        }.getOrNull()

    /** Allows slug redirects while identifying a persistent session's previous resource as stale. */
    internal fun matchesRequestedResource(
        request: CloudflareWebViewRequest,
        finalUrl: String,
    ): Boolean {
        val requested = SourceRegistry.resolve(request.url)
        val final = SourceRegistry.resolve(finalUrl)
        if (requested != null || final != null) {
            if (requested == null || final == null || requested.provider !== final.provider) {
                return false
            }
            // Chapter ids are finer-grained than URL kind: chapter URLs may classify as STORY
            // (FanFiction) or canonicalize to a thread URL (SpaceBattles), so kind equality both
            // over-accepts a stale sibling chapter and over-rejects a same-post redirect.
            val requestedChapterId = requested.provider.getChapterId(requested.normalizedUrl)
            val finalChapterId = final.provider.getChapterId(final.normalizedUrl)
            if (requestedChapterId != null && finalChapterId != null) {
                return requestedChapterId == finalChapterId
            }
            if (requested.kind != final.kind) {
                return false
            }
            return when (requested.kind) {
                SourceUrlKind.CHAPTER ->
                    requestedChapterId != null && requestedChapterId == finalChapterId
                SourceUrlKind.STORY ->
                    requested.provider.getStoryId(requested.normalizedUrl) ==
                        final.provider.getStoryId(final.normalizedUrl)
            }
        }
        return normalizedPath(request.url) == normalizedPath(finalUrl)
    }

    private fun normalizedPath(url: String): String? =
        runCatching {
            java.net
                .URI(url)
                .path
                .orEmpty()
                .ifBlank { "/" }
                .removeSuffix("/")
                .ifBlank { "/" }
                .lowercase()
                // XenForo-style pagination redirects the default first page (/reader/page-1) to the
                // unpaginated canonical (/reader/). Equate the two, or a successfully loaded reader
                // page is rejected as a different resource and the render times out into the manual
                // circuit. Non-default pages (/page-2+) keep their distinguishing segment.
                .removeSuffix("/page-1")
                .ifBlank { "/" }
        }.getOrNull()
}
