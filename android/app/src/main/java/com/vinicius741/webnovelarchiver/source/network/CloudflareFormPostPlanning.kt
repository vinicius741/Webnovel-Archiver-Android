package com.vinicius741.webnovelarchiver.source.network

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Builds and decodes a same-origin browser fetch for form posts with custom request headers. */
internal object CloudflareFormPostPlanning {
    fun bootstrapUrl(url: String): String? =
        runCatching {
            val parsed = URI(url)
            if (parsed.scheme !in setOf("http", "https") || parsed.host == null) return null
            URI(parsed.scheme, null, parsed.host, parsed.port, "/", null, null).toString()
        }.getOrNull()

    fun sameOrigin(
        first: String,
        second: String,
    ): Boolean =
        runCatching {
            val a = URI(first)
            val b = URI(second)
            a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) && effectivePort(a) == effectivePort(b)
        }.getOrDefault(false)

    fun canReusePage(
        requestUrl: String,
        state: CloudflarePageState,
    ): Boolean =
        !state.stale &&
            state.readyState in setOf("interactive", "complete") &&
            sameOrigin(requestUrl, state.documentUrl) &&
            !SourceAccessBlockDetector.isChallengeHtml(state.html)

    fun fetchScript(request: CloudflareWebViewRequest): String {
        val headers = JSONObject()
        request.headers.forEach { (name, value) ->
            val normalized = name.lowercase()
            if (normalized !in FORBIDDEN_HEADERS && !normalized.startsWith("sec-") && !normalized.startsWith("proxy-")) {
                headers.put(name, value)
            }
        }
        val body = request.postData?.toString(Charsets.UTF_8).orEmpty()
        return buildString {
            // seq is captured per fetch so a previous request's late-resolving promise (the session
            // page outlives each render and its fetch cannot be cancelled) writes only its own seq.
            append("(function(){var seq=(window.__wnaFormPostSeq=(window.__wnaFormPostSeq||0)+1);")
            append("window.__wnaFormPostResult=null;fetch(")
            append(JSONObject.quote(request.url))
            append(",{method:'POST',credentials:'include',headers:")
            append(headers)
            append(",body:")
            append(JSONObject.quote(body))
            append("}).then(async function(response){window.__wnaFormPostResult={seq:seq,")
            append("status:response.status,url:response.url,")
            append("mitigated:response.headers.get('cf-mitigated')==='challenge',")
            append("retryAfter:response.headers.get('Retry-After'),body:await response.text()};")
            append("}).catch(function(){window.__wnaFormPostResult=")
            append("{seq:seq,status:0,url:'',mitigated:false,body:''};});return seq;})()")
        }
    }

    fun decodeResult(
        raw: String?,
        expectedSeq: Int,
    ): FormPostResult? {
        val unwrapped = runCatching { JSONArray("[$raw]").getString(0) }.getOrNull() ?: return null
        if (!unwrapped.startsWith("{")) return null
        return runCatching {
            val obj = JSONObject(unwrapped)
            // A seq mismatch is a previous request's stale result: ignore it and keep polling.
            if (obj.optInt("seq") != expectedSeq) return null
            FormPostResult(
                status = obj.optInt("status"),
                url = obj.optString("url"),
                mitigated = obj.optBoolean("mitigated"),
                retryAfter = obj.optString("retryAfter").takeUnless { it == "null" || it.isBlank() },
                body = obj.optString("body"),
            )
        }.getOrNull()
    }

    private fun effectivePort(uri: URI): Int = uri.port.takeIf { it >= 0 } ?: if (uri.scheme.equals("https", true)) 443 else 80

    private val FORBIDDEN_HEADERS =
        setOf(
            "accept-charset",
            "accept-encoding",
            "connection",
            "content-length",
            "cookie",
            "cookie2",
            "date",
            "dnt",
            "expect",
            "host",
            "origin",
            "referer",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
            "user-agent",
            "via",
        )
}

internal data class FormPostResult(
    val status: Int,
    val url: String,
    val mitigated: Boolean,
    val retryAfter: String?,
    val body: String,
)
