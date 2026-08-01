package com.vinicius741.webnovelarchiver.source.network

import okhttp3.FormBody
import okhttp3.Request

/**
 * OkHttp request builders shared by page, form, and binary fetches. Extracted from [NetworkClient]
 * so that file stays within its size budget.
 */
object NetworkRequests {
    /**
     * The default User-Agent sent on every OkHttp request. Reads [SourceUserAgent.resolved] so it
     * stays byte-identical to the UA the solving WebView uses for sources that share the default
     * mobile surface. FanFiction.net gets a source-specific desktop UA because its mobile page
     * omits the complete chapter selector.
     */
    val USER_AGENT: String get() = SourceUserAgent.resolved
    const val DEFAULT_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
    const val FORM_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded; charset=UTF-8"

    fun pageRequest(url: String): Request =
        Request
            .Builder()
            .url(url)
            .header("User-Agent", SourceUserAgent.forUrl(url))
            .header("Accept", DEFAULT_ACCEPT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

    fun formRequest(
        url: String,
        fields: Map<String, Any>,
        headers: Map<String, String> = emptyMap(),
    ): Request {
        val bodyBuilder = FormBody.Builder()
        fields.forEach { (key, value) -> bodyBuilder.add(key, value.toString()) }
        val builder =
            Request
                .Builder()
                .url(url)
                .post(bodyBuilder.build())
                .header("User-Agent", SourceUserAgent.forUrl(url))
                .header("Accept", FORM_ACCEPT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Content-Type", FORM_CONTENT_TYPE)
                .header("X-Requested-With", "XMLHttpRequest")
        headers.forEach { (key, value) -> builder.header(key, value) }
        return builder.build()
    }

    /** Request builder for binary downloads (cover images) — reuses the shared client (R6). */
    fun binaryRequest(url: String): Request =
        Request
            .Builder()
            .url(url)
            .header("User-Agent", SourceUserAgent.forUrl(url))
            .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
}
