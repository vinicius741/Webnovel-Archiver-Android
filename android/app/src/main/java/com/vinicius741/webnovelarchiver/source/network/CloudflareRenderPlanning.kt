package com.vinicius741.webnovelarchiver.source.network

import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.source.SourceUrlKind
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

internal enum class CloudflareRenderPollDecision {
    ACCEPT_PAGE,

    KEEP_POLLING,

    REJECT_PAGE,
}

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
            // The session WebView is persistent; never decide on the previous request's document,
            // or a same-URL retry would accept/reject the old page before the new navigation commits.
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

internal data class CloudflarePageState(
    val documentUrl: String,
    val readyState: String,
    val html: String,
    val stale: Boolean = false,
)

/**
 * Why a render ended without an accepted page. Only some mean Cloudflare needs a human; the rest
 * are per-request outcomes the ordinary error paths already handle. [note] is a fixed snake_case
 * vocabulary for logs and diagnostics.
 */
internal sealed interface CloudflareRenderFailure {
    val note: String

    data object ChallengeActive : CloudflareRenderFailure {
        override val note = "challenge_still_active"
    }

    data object StaleDocumentPersisted : CloudflareRenderFailure {
        override val note = "stale_document_persisted"
    }

    data object NavigationNeverCommitted : CloudflareRenderFailure {
        override val note = "navigation_never_committed"
    }

    data object NeverSettled : CloudflareRenderFailure {
        override val note = "never_settled"
    }

    data class PageContentUnexpected(
        val page: CloudflareRenderedPage,
    ) : CloudflareRenderFailure {
        override val note = "page_content_unexpected"
    }

    data class MainFrameHttpError(
        val statusCode: Int,
    ) : CloudflareRenderFailure {
        override val note = "main_frame_http_error"
    }

    data object MainFrameTransportError : CloudflareRenderFailure {
        override val note = "main_frame_transport_error"
    }

    data object RenderProcessGone : CloudflareRenderFailure {
        override val note = "render_process_gone"
    }

    data object UnsupportedMethod : CloudflareRenderFailure {
        override val note = "unsupported_method"
    }

    data object TimedOut : CloudflareRenderFailure {
        override val note = "timed_out"
    }
}

internal sealed interface CloudflareRenderOutcome {
    data class Rendered(
        val page: CloudflareRenderedPage,
    ) : CloudflareRenderOutcome

    data class PageContentUnexpected(
        val page: CloudflareRenderedPage,
    ) : CloudflareRenderOutcome

    data class OriginHttpError(
        val statusCode: Int,
    ) : CloudflareRenderOutcome

    data object TransportError : CloudflareRenderOutcome

    data class NeedsManualVerification(
        val failure: CloudflareRenderFailure,
    ) : CloudflareRenderOutcome
}

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
            // Chapter ids are finer-grained than URL kind (chapter URLs may classify as STORY or
            // canonicalize to a thread URL), so kind equality over-accepts stale siblings and
            // over-rejects same-post redirects.
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
                // XenForo redirects the default /page-1 to the unpaginated canonical; equate them
                // or a loaded reader page is rejected and the render times out. /page-2+ keep
                // their distinguishing segment.
                .removeSuffix("/page-1")
                .ifBlank { "/" }
        }.getOrNull()
}
