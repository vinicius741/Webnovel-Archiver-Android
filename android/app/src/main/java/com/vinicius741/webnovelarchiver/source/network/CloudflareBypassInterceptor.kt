package com.vinicius741.webnovelarchiver.source.network

import android.content.Context
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/** The browser-rendered result of a request that OkHttp could not pass through Cloudflare. */
internal data class CloudflareRenderedPage(
    val html: String,
    val finalUrl: String,
)

/** A request shape supported by WebView's top-level GET and form-POST APIs. */
internal data class CloudflareWebViewRequest(
    val url: String,
    val method: String,
    val userAgent: String,
    val headers: Map<String, String>,
    val postData: ByteArray?,
) {
    companion object {
        fun from(
            request: Request,
            headers: Map<String, String>,
        ): CloudflareWebViewRequest? {
            val postData =
                when (request.method) {
                    "GET" -> null
                    "POST" ->
                        runCatching {
                            val buffer = Buffer()
                            request.body?.writeTo(buffer)
                            if (buffer.size > MAX_POST_BYTES) return null
                            buffer.readByteArray()
                        }.getOrNull() ?: return null
                    else -> return null
                }
            return CloudflareWebViewRequest(
                url = request.url.toString(),
                method = request.method,
                userAgent = request.header("User-Agent").orEmpty(),
                headers = headers,
                postData = postData,
            )
        }

        private const val MAX_POST_BYTES = 1_000_000L
    }
}

internal fun interface CloudflarePageRenderer {
    fun render(request: CloudflareWebViewRequest): CloudflareRenderedPage?
}

private class AndroidCloudflarePageRenderer(
    private val context: Context,
) : CloudflarePageRenderer {
    override fun render(request: CloudflareWebViewRequest): CloudflareRenderedPage? = CloudflareWebViewSolver.render(context, request)
}

/**
 * Handles a detected Cloudflare challenge with Android WebView's Chromium network stack.
 *
 * The first request still uses OkHttp. If Cloudflare challenges it, the same GET or form POST is
 * rendered in a detached WebView. A successful rendered DOM is converted into an OkHttp response so
 * the existing source parsers remain unchanged. Crucially, the browser response is not discarded in
 * favor of another OkHttp retry; doing that caused a permanent verification loop when Cloudflare
 * accepted Chromium's session but rejected OkHttp's different browser/TLS fingerprint.
 */
class CloudflareBypassInterceptor internal constructor(
    private val renderer: CloudflarePageRenderer,
    private val reliability: SourceReliabilityCoordinator = SourceReliabilityCoordinator(),
) : Interceptor {
    constructor(
        context: Context,
        reliability: SourceReliabilityCoordinator,
    ) : this(AndroidCloudflarePageRenderer(context.applicationContext), reliability)

    private data class HostGate(
        val lock: ReentrantLock = ReentrantLock(),
    )

    private val hostGates = ConcurrentHashMap<String, HostGate>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val browserRequest =
            request
                .takeIf(::isBrowserPageRequest)
                ?.let { CloudflareWebViewRequest.from(it, safeWebViewHeaders(it)) }

        // Once this host has challenged OkHttp, stop presenting the same rejected native TLS/HTTP
        // fingerprint before every chapter. Keep all browser requests serialized through the host
        // gate and the persistent Chromium session for the remainder of the passage window.
        if (browserRequest != null && reliability.browserTransportActive(request.url.host)) {
            val gate = hostGates.getOrPut(hostKey(request)) { HostGate() }
            lockGate(gate)
            try {
                val rendered = renderer.render(browserRequest)
                if (rendered != null) return renderedResponse(request, rendered)
                reliability.requireManualVerification(request.url.host)
                throw SourceAccessBlockedException(request.url.toString())
            } finally {
                gate.lock.unlock()
            }
        }

        val response = chain.proceed(request)
        if (!isChallengeResponse(response)) return response

        reliability.recordChallengeDetected(request.url.host)
        val webViewRequest = browserRequest ?: return response
        val gate = hostGates.getOrPut(hostKey(request)) { HostGate() }
        lockGate(gate, response)
        try {
            val rendered =
                renderer.render(webViewRequest) ?: run {
                    reliability.requireManualVerification(request.url.host)
                    return response
                }
            response.close()
            return renderedResponse(request, rendered)
        } finally {
            gate.lock.unlock()
        }
    }

    private fun lockGate(
        gate: HostGate,
        responseToClose: Response? = null,
    ) {
        try {
            gate.lock.lockInterruptibly()
        } catch (error: InterruptedException) {
            responseToClose?.close()
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting for Cloudflare verification", error)
        }
    }

    private fun renderedResponse(
        request: Request,
        rendered: CloudflareRenderedPage,
    ): Response =
        Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Type", HTML_CONTENT_TYPE.toString())
            .header(BROWSER_RENDERED_HEADER, "1")
            .header(BROWSER_FINAL_URL_HEADER, rendered.finalUrl)
            .body(rendered.html.toResponseBody(HTML_CONTENT_TYPE))
            .build()

    private fun isChallengeResponse(response: Response): Boolean {
        val bodyString =
            runCatching { response.peekBody(BODY_PEEK_BYTES).string() }.getOrNull().orEmpty()
        return SourceAccessBlockDetector.isChallengeResponse(response.headers, bodyString)
    }

    private fun isBrowserPageRequest(request: Request): Boolean =
        request.method in setOf("GET", "POST") &&
            request.header("Accept").orEmpty().let { accept ->
                accept.isBlank() || accept.contains("text/html") || accept.contains("application/xhtml+xml")
            }

    private fun hostKey(request: Request): String =
        request.url.host
            .lowercase(Locale.US)
            .removePrefix("www.")

    private fun safeWebViewHeaders(request: Request): Map<String, String> =
        request.headers
            .filter { (name, value) -> isRequestHeaderSafe(name, value) }
            .groupBy(keySelector = { (name, _) -> name }) { (_, value) -> value }
            .mapValues { it.value.firstOrNull().orEmpty() }

    private fun isRequestHeaderSafe(
        rawName: String,
        rawValue: String,
    ): Boolean {
        val name = rawName.lowercase(Locale.US)
        val value = rawValue.lowercase(Locale.US)
        if (name in UNSAFE_HEADER_NAMES || name.startsWith("proxy-")) return false
        if (name == "connection" && value == "upgrade") return false
        return true
    }

    companion object {
        internal const val BROWSER_RENDERED_HEADER = "X-WNA-Browser-Rendered"
        internal const val BROWSER_FINAL_URL_HEADER = "X-WNA-Browser-Final-Url"
        const val BODY_PEEK_BYTES = 64_000L
        val HTML_CONTENT_TYPE = "text/html; charset=utf-8".toMediaType()
        val UNSAFE_HEADER_NAMES =
            setOf(
                "content-length",
                "host",
                "trailer",
                "te",
                "upgrade",
                "user-agent",
                "cookie",
                "cookie2",
                "keep-alive",
                "transfer-encoding",
                "set-cookie",
            )
    }
}
