package com.vinicius741.webnovelarchiver.source.network

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class NetworkClient(
    /** Shared OkHttp client (R6). Cover/image fetches go through the same client as page fetches. */
    val client: OkHttpClient = defaultClient,
) {
    /**
     * Per-host rate-limit gates (R6). Each host has its own [Mutex]; the Scribble Hub gap is enforced
     * inside the lock so concurrent downloads from the activity + foreground service cannot bypass it.
     * The "next allowed at" timestamp is stored alongside the mutex so the lock is held only briefly.
     */
    private data class HostGate(
        val mutex: Mutex,
        var nextAllowedAt: Long = 0L,
    )

    private val hostGates = ConcurrentHashMap<String, HostGate>()
    private val hostGatesLock = Mutex()

    private suspend fun gateFor(host: String): HostGate =
        hostGatesLock.withLock {
            hostGates.getOrPut(host) { HostGate(Mutex()) }
        }

    suspend fun fetch(
        url: String,
        callTimeoutMillis: Long? = null,
    ): String {
        waitForRateLimit(url)
        val request = NetworkRequests.pageRequest(url)
        return executeWithRetries(url, request, callTimeoutMillis) { response ->
            val body = response.body?.string().orEmpty()
            if (SourceAccessBlockDetector.isChallengeResponse(response.headers, body)) {
                throw SourceAccessBlockedException(url)
            }
            body
        }
    }

    suspend fun postForm(
        url: String,
        fields: Map<String, Any>,
        headers: Map<String, String> = emptyMap(),
    ): String {
        waitForRateLimit(url)
        val request = NetworkRequests.formRequest(url, fields, headers)
        return executeWithRetries(url, request) { response ->
            val body = response.body?.string().orEmpty()
            if (SourceAccessBlockDetector.isChallengeResponse(response.headers, body)) {
                throw SourceAccessBlockedException(url)
            }
            body
        }
    }

    /**
     * Fetches a binary response (cover images, R6) through the shared OkHttp client with an
     * optional [maxBytes] cap. Returns null on non-2xx, non-image responses, or oversize bodies.
     * Respects the same per-host rate limit as [fetch] (R6) so cover fetches on Scribble Hub can't
     * stack 403s alongside page fetches.
     */
    suspend fun fetchBytes(
        url: String,
        maxBytes: Long = MAX_IMAGE_BYTES,
    ): ByteArray? {
        waitForRateLimit(url)
        val request = NetworkRequests.binaryRequest(url)
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val contentType = response.header("Content-Type").orEmpty()
                if (contentType.isNotBlank() && !contentType.startsWith("image/")) return@use null
                val body = response.body ?: return@use null
                val length = body.contentLength()
                if (length > maxBytes) return@use null
                // Cap at the source so a chunked/unknown-length response can't be buffered in full
                // before the size check runs. Request one byte past the cap; if we get it, the body
                // is too large.
                val source = body.source()
                source.request(maxBytes + 1)
                if (source.buffer.size > maxBytes) return@use null
                source.buffer.readByteArray()
            }
        }.getOrNull()
    }

    private suspend fun <T> executeWithRetries(
        url: String,
        request: Request,
        callTimeoutMillis: Long? = null,
        read: (Response) -> T,
    ): T {
        var attempt = 1
        while (attempt <= 3) {
            val call = client.newCall(request)
            callTimeoutMillis?.let { timeout -> call.timeout().timeout(timeout, TimeUnit.MILLISECONDS) }
            val response = call.execute()
            response.use {
                if (it.isSuccessful) return read(it)
                val responseBody = it.body?.string().orEmpty()
                if (SourceAccessBlockDetector.isChallengeResponse(it.headers, responseBody)) {
                    throw SourceAccessBlockedException(url)
                }
                val host = runCatching { URL(url).host }.getOrNull()
                val shouldRetry = host == "www.scribblehub.com" && (it.code == 403 || it.code == 429) && attempt < 3
                if (!shouldRetry) throw IllegalStateException("HTTP ${it.code} for $url")
            }
            delay(2500L * attempt)
            attempt += 1
        }
        error("Failed to fetch $url")
    }

    private suspend fun waitForRateLimit(url: String) {
        val host = runCatching { URL(url).host }.getOrNull() ?: return
        val gap = if (host == "www.scribblehub.com") 1500L else 0L
        if (gap <= 0) return
        val gate = gateFor(host)
        gate.mutex.withLock {
            val now = System.currentTimeMillis()
            val next = gate.nextAllowedAt
            if (next > now) delay(next - now)
            gate.nextAllowedAt = System.currentTimeMillis() + gap
        }
    }

    companion object {
        /** Maximum bytes accepted for a cover/image download (R6 size cap). */
        const val MAX_IMAGE_BYTES = 8_000_000L

        val defaultClient: OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .build()
    }
}

object NetworkRequests {
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"
    const val DEFAULT_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
    const val FORM_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded; charset=UTF-8"

    fun pageRequest(url: String): Request =
        Request
            .Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
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
                .header("User-Agent", USER_AGENT)
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
            .header("User-Agent", USER_AGENT)
            .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
}
