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
        }.getOrNull()
}
