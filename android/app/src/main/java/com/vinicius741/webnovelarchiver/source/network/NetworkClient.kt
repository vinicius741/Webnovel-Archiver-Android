package com.vinicius741.webnovelarchiver.source.network

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

class NetworkClient(
    /**
     * Shared OkHttp client (R6). Cover/image fetches go through the same client as page fetches.
     * Built by [buildDefault] with the [AndroidCookieJar] (and, in Phase 2, the Cloudflare
     * interceptor) attached, so cookies earned in an in-app WebView are replayed here automatically.
     */
    val client: OkHttpClient = defaultClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val policyResolver: NetworkPolicyResolver = DefaultNetworkPolicyResolver,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val jitterMillis: (Long) -> Long = { maximum ->
        if (maximum <= 0L) 0L else Random.nextLong(maximum + 1L)
    },
) {
    private sealed interface AttemptResult<out T> {
        data class Success<T>(
            val value: T,
        ) : AttemptResult<T>

        data class HttpFailure(
            val statusCode: Int,
            val retryAfterHeader: String?,
        ) : AttemptResult<Nothing>
    }

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
        val request = NetworkRequests.pageRequest(url)
        val policy = policyResolver.policyFor(request.url)
        waitForRateLimit(request.url.host, policy)
        return executeWithRetries(url, request, policy, callTimeoutMillis) { response ->
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
        val request = NetworkRequests.formRequest(url, fields, headers)
        val policy = policyResolver.policyFor(request.url)
        waitForRateLimit(request.url.host, policy)
        return executeWithRetries(url, request, policy) { response ->
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
        val request = NetworkRequests.binaryRequest(url)
        val policy = policyResolver.policyFor(request.url)
        waitForRateLimit(request.url.host, policy)
        return try {
            withContext(ioDispatcher) {
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
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun <T> executeWithRetries(
        url: String,
        request: Request,
        policy: SourceNetworkPolicy,
        callTimeoutMillis: Long? = null,
        read: (Response) -> T,
    ): T {
        var attempt = 1
        val maximumAttempts = policy.maximumAttempts.coerceAtLeast(1)
        while (attempt <= maximumAttempts) {
            val result = executeAttempt(url, request, callTimeoutMillis, read)
            when (result) {
                is AttemptResult.Success -> return result.value
                is AttemptResult.HttpFailure -> {
                    val isRateLimited = result.statusCode in policy.retryableStatusCodes
                    if (!isRateLimited || attempt >= maximumAttempts) {
                        if (isRateLimited) {
                            throw RateLimitNetworkException(
                                requestedUrl = url,
                                statusCode = result.statusCode,
                                retryAfterMillis = retryAfterMillis(result.retryAfterHeader, policy),
                            )
                        }
                        throw HttpNetworkException(url, result.statusCode)
                    }
                    sleep(retryDelayMillis(attempt, result.retryAfterHeader, policy))
                    attempt += 1
                }
            }
        }
        throw NetworkTransportException(url, IllegalStateException("Failed to fetch $url"))
    }

    private suspend fun <T> executeAttempt(
        url: String,
        request: Request,
        callTimeoutMillis: Long?,
        read: (Response) -> T,
    ): AttemptResult<T> =
        try {
            withContext(ioDispatcher) {
                val call = client.newCall(request)
                callTimeoutMillis?.let { timeout -> call.timeout().timeout(timeout, TimeUnit.MILLISECONDS) }
                call.execute().use { response ->
                    if (response.isSuccessful) return@withContext AttemptResult.Success(read(response))
                    val responseBody = response.body?.string().orEmpty()
                    if (SourceAccessBlockDetector.isChallengeResponse(response.headers, responseBody)) {
                        throw SourceAccessBlockedException(url)
                    }
                    AttemptResult.HttpFailure(response.code, response.header("Retry-After"))
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: SourceAccessBlockedException) {
            throw error
        } catch (error: SocketTimeoutException) {
            throw NetworkTimeoutException(url, error)
        } catch (error: InterruptedIOException) {
            throw NetworkTimeoutException(url, error)
        } catch (error: UnknownHostException) {
            throw NetworkOfflineException(url, error)
        } catch (error: NoRouteToHostException) {
            throw NetworkOfflineException(url, error)
        } catch (error: ConnectException) {
            throw NetworkOfflineException(url, error)
        } catch (error: IOException) {
            throw NetworkTransportException(url, error)
        }

    private suspend fun waitForRateLimit(
        host: String,
        policy: SourceNetworkPolicy,
    ) {
        val gap = policy.minimumRequestGapMillis.coerceAtLeast(0L)
        if (gap <= 0) return
        val gate = gateFor(host)
        // Claim or measure wait under the lock, then sleep outside it so concurrent callers for the
        // same host can schedule their own waits instead of serializing behind one sleeper.
        while (true) {
            val waitMillis =
                gate.mutex.withLock {
                    val now = nowMillis()
                    val next = gate.nextAllowedAt
                    if (next > now) {
                        next - now
                    } else {
                        gate.nextAllowedAt = now + gap
                        0L
                    }
                }
            if (waitMillis <= 0L) return
            sleep(waitMillis)
        }
    }

    private fun retryDelayMillis(
        attempt: Int,
        retryAfterHeader: String?,
        policy: SourceNetworkPolicy,
    ): Long {
        val requested =
            retryAfterMillis(retryAfterHeader, policy)
                ?: (policy.baseRetryDelayMillis.coerceAtLeast(0L) * attempt)
                    .coerceAtMost(policy.maximumRetryDelayMillis.coerceAtLeast(0L))
        val maximumJitter = min(policy.maximumJitterMillis.coerceAtLeast(0L), requested / 5L)
        val jitter = jitterMillis(maximumJitter).coerceIn(0L, maximumJitter)
        return (requested + jitter).coerceAtMost(policy.maximumRetryDelayMillis.coerceAtLeast(0L))
    }

    private fun retryAfterMillis(
        header: String?,
        policy: SourceNetworkPolicy,
    ): Long? {
        if (header.isNullOrBlank()) return null
        val rawMillis =
            header.trim().toLongOrNull()?.let { seconds ->
                seconds
                    .coerceIn(0L, Long.MAX_VALUE / 1_000L)
                    .times(1_000L)
            }
                ?: runCatching {
                    ZonedDateTime
                        .parse(header.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant()
                        .toEpochMilli()
                        .minus(nowMillis())
                        .coerceAtLeast(0L)
                }.getOrNull()
        return rawMillis?.coerceAtMost(policy.maximumRetryAfterMillis.coerceAtLeast(0L))
    }

    companion object {
        /** Maximum bytes accepted for a cover/image download (R6 size cap). */
        const val MAX_IMAGE_BYTES = 8_000_000L

        /**
         * Legacy fallback built without a [Context]. Kept for the parameter default only — the real
         * client used in production is built by [buildDefault], which attaches the shared
         * [AndroidCookieJar] and the Cloudflare bypass interceptor. This has no cookie jar and must
         * never be the process-wide client (Cloudflare clearance would be dropped on every response).
         */
        private val defaultClient: OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .build()

        /**
         * Builds the production OkHttp client: same timeouts as the legacy builder, plus the
         * [AndroidCookieJar] (so `Set-Cookie` responses persist and WebViews share the store) and
         * the [CloudflareBypassInterceptor] (so a detected challenge is solved by a background
         * WebView before the response reaches [executeWithRetries]).
         */
        fun buildDefault(context: Context): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .cookieJar(AndroidCookieJar())
                .addInterceptor(CloudflareBypassInterceptor(context.applicationContext))
                .build()
    }
}

object NetworkRequests {
    /**
     * The User-Agent sent on every OkHttp request. Reads [SourceUserAgent.resolved] so it stays
     * byte-identical to the UA the solving WebView used to mint `cf_clearance` (Cloudflare binds
     * the clearance cookie to the exact UA). Resolved once at Application startup.
     */
    val USER_AGENT: String get() = SourceUserAgent.resolved
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
