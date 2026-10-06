package com.vinicius741.webnovelarchiver.source.network

import android.content.Context
import com.vinicius741.webnovelarchiver.data.diagnostics.BypassEventCategory
import com.vinicius741.webnovelarchiver.data.diagnostics.BypassEventLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Dispatcher
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

/** Per-request gate around the shared source-safety claim; must invoke [claimSourcePermission] exactly once. */
fun interface NetworkRequestGate {
    suspend fun awaitRequest(claimSourcePermission: suspend () -> Unit)
}

@Suppress("TooManyFunctions")
class NetworkClient(
    /** Shared OkHttp client built by [buildDefault]; WebView-earned cookies replay here via [AndroidCookieJar]. */
    val client: OkHttpClient = defaultClient,
    internal val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    internal val policyResolver: NetworkPolicyResolver = DefaultNetworkPolicyResolver,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    internal val nowMillis: () -> Long = System::currentTimeMillis,
    private val jitterMillis: (Long) -> Long = { maximum ->
        if (maximum <= 0L) 0L else Random.nextLong(maximum + 1L)
    },
    reliabilityCoordinator: SourceReliabilityCoordinator? = null,
) {
    private sealed interface AttemptResult<out T> {
        data class Success<T>(
            val value: T,
            val browserRendered: Boolean,
        ) : AttemptResult<T>

        data class HttpFailure(
            val statusCode: Int,
            val retryAfterHeader: String?,
        ) : AttemptResult<Nothing>
    }

    internal data class PreparedPage(
        val html: String,
        val expiresAt: Long,
    )

    internal val reliability =
        reliabilityCoordinator
            ?: SourceReliabilityCoordinator(
                nowMillis = nowMillis,
                sleep = sleep,
            )
    internal val retryBackoff = RetryBackoff(nowMillis, jitterMillis)
    internal val preparedPages = ConcurrentHashMap<String, PreparedPage>()
    internal val reusablePages = ConcurrentHashMap<String, PreparedPage>()
    internal val reusablePageLocks = ConcurrentHashMap<String, Mutex>()

    // R29: callers registered between lock get-or-create and completed withLock; eviction must
    // never drop their mutex, so concurrent same-key callers keep sharing one fetch.
    internal val acquiringPageLockCounts = ConcurrentHashMap<String, Int>()

    suspend fun fetch(
        url: String,
        callTimeoutMillis: Long? = null,
        maximumAttemptsOverride: Int? = null,
        allowPreparedPage: Boolean = true,
        requestGate: NetworkRequestGate? = null,
    ): String {
        if (allowPreparedPage) {
            preparedPages.remove(url)?.takeIf { it.expiresAt > nowMillis() }?.let { return it.html }
        }
        val request = NetworkRequests.pageRequest(url)
        val policy = policyResolver.policyFor(request.url)
        return executeWithRetries(url, request, policy, callTimeoutMillis, maximumAttemptsOverride, requestGate) { response ->
            val body = response.bodyStringCapped(url, MAX_TEXT_RESPONSE_BYTES)
            if (SourceAccessBlockDetector.isChallengeResponse(response.headers, body)) {
                throw SourceAccessBlockedException(url)
            }
            body
        }
    }

    /**
     * Fetches and caches a page shared by several chapter jobs; the per-key mutex coalesces concurrent
     * misses. [cacheValidator] gates cache admission so invalid HTML can't poison later jobs.
     */
    suspend fun fetchReusablePage(
        url: String,
        cacheKey: String = url,
        ttlMillis: Long = REUSABLE_PAGE_TTL_MILLIS,
        callTimeoutMillis: Long? = null,
        maximumAttemptsOverride: Int? = null,
        requestGate: NetworkRequestGate? = null,
        cacheValidator: (String) -> Boolean = { it.isNotBlank() },
    ): String {
        val now = nowMillis()
        reusablePages[cacheKey]?.let { cached ->
            if (cached.expiresAt > now && cacheValidator(cached.html)) return cached.html
            reusablePages.remove(cacheKey, cached)
        }
        val lock = acquirePageLock(cacheKey)
        try {
            return lock.withLock {
                val lockedNow = nowMillis()
                reusablePages[cacheKey]?.let { cached ->
                    if (cached.expiresAt > lockedNow && cacheValidator(cached.html)) return@withLock cached.html
                    reusablePages.remove(cacheKey, cached)
                }
                fetch(
                    url = url,
                    callTimeoutMillis = callTimeoutMillis,
                    maximumAttemptsOverride = maximumAttemptsOverride,
                    requestGate = requestGate,
                ).also { html ->
                    if (cacheValidator(html)) {
                        val cachedAt = nowMillis()
                        reusablePages.entries
                            .filter { (_, page) -> page.expiresAt <= cachedAt }
                            .forEach { (key, page) -> reusablePages.remove(key, page) }
                        if (reusablePages.size >= MAX_REUSABLE_PAGES) {
                            reusablePages.entries.minByOrNull { it.value.expiresAt }?.let { oldest ->
                                reusablePages.remove(oldest.key, oldest.value)
                            }
                        }
                        reusablePages[cacheKey] = PreparedPage(html, cachedAt + ttlMillis.coerceAtLeast(0L))
                    }
                }
            }
        } finally {
            releasePageLock(cacheKey)
        }
    }

    suspend fun postForm(
        url: String,
        fields: Map<String, Any>,
        headers: Map<String, String> = emptyMap(),
    ): String {
        val request = NetworkRequests.formRequest(url, fields, headers)
        val policy = policyResolver.policyFor(request.url)
        return executeWithRetries(url, request, policy, read = { response ->
            val body = response.bodyStringCapped(url, MAX_TEXT_RESPONSE_BYTES)
            if (SourceAccessBlockDetector.isChallengeResponse(response.headers, body)) {
                throw SourceAccessBlockedException(url)
            }
            body
        })
    }

    @Suppress("NestedBlockDepth", "ThrowsCount")
    private suspend fun <T> executeWithRetries(
        url: String,
        request: Request,
        policy: SourceNetworkPolicy,
        callTimeoutMillis: Long? = null,
        maximumAttemptsOverride: Int? = null,
        requestGate: NetworkRequestGate? = null,
        read: (Response) -> T,
    ): T {
        var attempt = 1
        val maximumAttempts = (maximumAttemptsOverride ?: policy.maximumAttempts).coerceAtLeast(1)
        while (attempt <= maximumAttempts) {
            val attemptId = BypassEventLog.nextId("a")
            val claimSourcePermission: suspend () -> Unit = {
                reliability.awaitPermission(url, request.url.host, policy)
            }
            if (requestGate == null) {
                claimSourcePermission()
            } else {
                requestGate.awaitRequest(claimSourcePermission)
            }
            val result =
                SourceRequestEvents.recording(request.url.host, attemptId, attempt, request.method, requestGate != null) {
                    executeAttempt(url, request, callTimeoutMillis, read)
                }
            when (result) {
                is AttemptResult.Success -> {
                    SourceRequestEvents.finished(
                        host = request.url.host,
                        attemptId = attemptId,
                        ok = true,
                        browserRendered = result.browserRendered,
                    )
                    reliability.recordSuccess(request.url.host, policy, result.browserRendered)
                    return result.value
                }
                is AttemptResult.HttpFailure -> {
                    SourceRequestEvents.finished(
                        host = request.url.host,
                        attemptId = attemptId,
                        ok = false,
                        code = result.statusCode,
                    )
                    val isRateLimited = result.statusCode in policy.retryableStatusCodes
                    if (isRateLimited) {
                        // R14: the accepted server deadline reaches the shared host coordinator as
                        // soon as the response arrives, so no other operation on this host can
                        // request inside the server-directed wait either.
                        val requestedRetryAfter = retryBackoff.retryAfterMillis(result.retryAfterHeader, policy)
                        val cooldown = reliability.recordRateLimit(request.url.host, policy, requestedRetryAfter)
                        val serverDeadlineExceedsBudget =
                            requestedRetryAfter != null && requestedRetryAfter > policy.maximumRetryDelayMillis
                        if (attempt >= maximumAttempts || serverDeadlineExceedsBudget) {
                            // Defer the work: the shared cooldown now carries the server deadline,
                            // and callers observe a typed rate-limit failure instead of an early retry.
                            throw RateLimitNetworkException(
                                requestedUrl = url,
                                statusCode = result.statusCode,
                                retryAfterMillis = maxOf(requestedRetryAfter ?: 0L, cooldown),
                            )
                        }
                    } else {
                        throw HttpNetworkException(url, result.statusCode)
                    }
                    sleep(retryBackoff.delayFor(attempt, result.retryAfterHeader, policy))
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
                // R13: a total per-call deadline (overridable per request class) plus real
                // cancellation — cancelling the coroutine cancels the in-flight OkHttp call so a
                // dead UI operation stops occupying a worker.
                call.timeout().timeout(callTimeoutMillis ?: DEFAULT_CALL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                call.executeCancellable { response ->
                    if (response.isSuccessful) {
                        return@executeCancellable AttemptResult.Success(
                            read(response),
                            response.header(CloudflareBypassInterceptor.BROWSER_RENDERED_HEADER) == "1",
                        )
                    }
                    // Error bodies only feed challenge detection; a bounded prefix is enough.
                    val responseBody = response.bodyStringPrefix(url, MAX_ERROR_BODY_BYTES)
                    if (SourceAccessBlockDetector.isChallengeResponse(response.headers, responseBody)) {
                        throw SourceAccessBlockedException(url)
                    }
                    AttemptResult.HttpFailure(response.code, response.header("Retry-After"))
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: SourceAccessBlockedException) {
            reliability.requireManualVerification(request.url.host)
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

    /** Warms a large batch and caches its first page so preflight does not duplicate the download. */
    suspend fun prepareBulkDownload(
        url: String,
        requestGate: NetworkRequestGate? = null,
    ) {
        val html =
            fetch(
                url = url,
                maximumAttemptsOverride = 1,
                allowPreparedPage = false,
                requestGate = requestGate,
            )
        this.admitPreparedPage(url, PreparedPage(html, nowMillis() + PREPARED_PAGE_TTL_MILLIS))
    }

    fun clearSourceAccess(
        url: String,
        keepBrowserTransport: Boolean = true,
    ) {
        val parsed = url.toHttpUrlOrNull() ?: return
        reliability.clearAccessBlock(parsed.host, keepBrowserTransport)
    }

    /** True while [providerId]'s manual-verification circuit is open; the download loop pauses its lane. */
    fun isSourceBlocked(providerId: String): Boolean = reliability.isManualVerificationRequired(providerId)

    fun onNetworkChanged() {
        preparedPages.clear()
        reusablePages.clear()
        reusablePageLocks.clear()
        reliability.onNetworkChanged()
    }

    fun reliabilitySnapshots(): List<SourceReliabilitySnapshot> = reliability.snapshots()

    /**
     * Reads a text body with an application-level byte cap: content-length and chunked
     * bodies alike are bounded, so an oversized response fails instead of buffering unbounded.
     */
    private fun Response.bodyStringCapped(
        url: String,
        maxBytes: Long,
    ): String {
        val body = this.body ?: return ""
        if (body.contentLength() > maxBytes) {
            throw NetworkTransportException(url, IOException("Response body exceeded $maxBytes bytes"))
        }
        val source = body.source()
        // Request one byte past the cap: if the buffer holds more than maxBytes, the body is over.
        source.request(maxBytes + 1)
        if (source.buffer.size > maxBytes) {
            throw NetworkTransportException(url, IOException("Response body exceeded $maxBytes bytes"))
        }
        return body.string()
    }

    /** First [maxBytes] of a body, for detection-only reads that must not fail on size. */
    private fun Response.bodyStringPrefix(
        url: String,
        maxBytes: Long,
    ): String =
        if (body.contentLength() > maxBytes) {
            ""
        } else {
            runCatching { bodyStringCapped(url, maxBytes) }.getOrNull().orEmpty()
        }

    companion object {
        const val MAX_IMAGE_BYTES = 8_000_000L
        internal const val PREPARED_PAGE_TTL_MILLIS = 5L * 60L * 1_000L
        private const val REUSABLE_PAGE_TTL_MILLIS = 10L * 60L * 1_000L
        private const val MAX_REUSABLE_PAGES = 24
        internal const val MAX_PREPARED_PAGES = 24

        /**
         * Default total budget for one source request, sized to also cover a background
         * Cloudflare render inside the interceptor; callers with tighter classes pass
         * `callTimeoutMillis` explicitly.
         */
        const val DEFAULT_CALL_TIMEOUT_MILLIS = 180_000L

        /** Application-level caps for text/catalog bodies. */
        const val MAX_TEXT_RESPONSE_BYTES = 6_000_000L
        const val MAX_ERROR_BODY_BYTES = 64_000L

        /**
         * Legacy fallback with no cookie jar, used only for the parameter default; must never be the
         * process-wide client (Cloudflare clearance would be dropped on every response).
         */
        private val defaultClient: OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .build()

        /**
         * Production client: [AndroidCookieJar] so cookies persist and WebViews share the store,
         * plus [CloudflareBypassInterceptor] to solve challenges in a background WebView. Per-host
         * pacing belongs to SourceReliabilityCoordinator, so the dispatcher's host cap is raised:
         * OkHttp's default of 5 would queue same-host waits outside the call-timeout budget.
         */
        fun buildDefault(
            context: Context,
            reliabilityCoordinator: SourceReliabilityCoordinator,
        ): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .cookieJar(AndroidCookieJar())
                .addInterceptor(CloudflareBypassInterceptor(context.applicationContext, reliabilityCoordinator))
                .dispatcher(Dispatcher().apply { maxRequestsPerHost = 64 })
                .build()
    }
}

/** OkHttp request builders shared by page, form, and binary fetches. */
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

/**
 * Translates a retry attempt into the delay before the next one, honoring a server `Retry-After`
 * header (seconds or HTTP-date) when present.
 */
internal class RetryBackoff(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val jitterMillis: (Long) -> Long,
) {
    fun delayFor(
        attempt: Int,
        retryAfterHeader: String?,
        policy: SourceNetworkPolicy,
    ): Long {
        // An accepted server deadline is honored as-is (already sanity-capped by
        // [retryAfterMillis]); clamping it to the ordinary backoff cap would make this client
        // retry early against the server's explicit instruction.
        val serverRequested = retryAfterMillis(retryAfterHeader, policy)
        val maximumJitter = min(policy.maximumJitterMillis.coerceAtLeast(0L), (serverRequested ?: 0L) / 5L)
        if (serverRequested != null) {
            val jitter = jitterMillis(maximumJitter).coerceIn(0L, maximumJitter)
            // Jitter must not push a server-directed sleep past the sanity cap that already
            // bounded the server's own request.
            return (serverRequested + jitter).coerceAtMost(policy.maximumRetryAfterMillis.coerceAtLeast(0L))
        }
        val clientBackoff =
            (policy.baseRetryDelayMillis.coerceAtLeast(0L) * attempt)
                .coerceAtMost(policy.maximumRetryDelayMillis.coerceAtLeast(0L))
        val clientJitterMax = min(policy.maximumJitterMillis.coerceAtLeast(0L), clientBackoff / 5L)
        val jitter = jitterMillis(clientJitterMax).coerceIn(0L, clientJitterMax)
        return (clientBackoff + jitter).coerceAtMost(policy.maximumRetryDelayMillis.coerceAtLeast(0L))
    }

    fun retryAfterMillis(
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
}

/**
 * One-line call-site helpers for the request-lifecycle events [NetworkClient] records into
 * [BypassEventLog]; the fields mirror what an investigating agent needs per attempt (see
 * BypassLogExporter's instructions). Durations are derived from this object's own start stamps, so
 * no call site threads a timer through the retry loop.
 */
internal object SourceRequestEvents {
    private val attemptStarts = ConcurrentHashMap<String, Long>()

    /**
     * Records one attempt's start and guarantees a terminal event for every exit: a thrown attempt
     * (timeout, offline, transport, challenge block) records `finished(ok=false)` before
     * rethrowing — these are exactly the failures the log exists to diagnose, so an unmatched
     * `started` must be impossible. [CancellationException] is abandonment, not an outcome: no
     * terminal event, and the start stamp is dropped with the attempt.
     */
    suspend fun <T> recording(
        host: String,
        attemptId: String,
        attempt: Int,
        method: String,
        gated: Boolean,
        block: suspend () -> T,
    ): T {
        started(host, attemptId, attempt, method, gated)
        return try {
            block()
        } catch (error: CancellationException) {
            attemptStarts.remove(attemptId)
            throw error
        } catch (error: Exception) {
            finished(host = host, attemptId = attemptId, ok = false)
            throw error
        }
    }

    private fun started(
        host: String,
        attemptId: String,
        attempt: Int,
        method: String,
        gated: Boolean,
    ) {
        attemptStarts[attemptId] = System.currentTimeMillis()
        BypassEventLog.record(
            BypassEventCategory.NET,
            "net_request_start",
            host,
            "attemptId" to attemptId,
            "attempt" to attempt,
            "method" to method,
            "gated" to gated,
        )
    }

    fun finished(
        host: String,
        attemptId: String,
        ok: Boolean,
        code: Int? = null,
        browserRendered: Boolean = false,
    ) {
        val durationMillis =
            attemptStarts.remove(attemptId)?.let { startedAt ->
                System.currentTimeMillis() - startedAt
            }
        BypassEventLog.record(
            BypassEventCategory.NET,
            "net_request_finish",
            host,
            "attemptId" to attemptId,
            "ok" to ok,
            "code" to code,
            "browserRendered" to browserRendered,
            "durationMs" to durationMillis,
        )
    }
}

/** Bounded binary payload plus the response's declared image content type. */
data class FetchedImage(
    val bytes: ByteArray,
    val contentType: String?,
)

/**
 * Fetches a bounded binary body together with the response's declared content type, so
 * callers embed images with a validated media type instead of guessing from the URL extension.
 * Null on non-2xx, non-image, or oversize.
 */
suspend fun NetworkClient.fetchImage(
    url: String,
    maxBytes: Long = NetworkClient.MAX_IMAGE_BYTES,
): FetchedImage? {
    val request = NetworkRequests.binaryRequest(url)
    val policy = policyResolver.policyFor(request.url)
    reliability.awaitPermission(url, request.url.host, policy)
    return try {
        withContext(ioDispatcher) {
            val call = client.newCall(request)
            call.timeout().timeout(NetworkClient.DEFAULT_CALL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            call.executeCancellable { response ->
                if (!response.isSuccessful) {
                    if (response.code == 429) {
                        reliability.recordRateLimit(
                            request.url.host,
                            policy,
                            retryBackoff.retryAfterMillis(response.header("Retry-After"), policy),
                        )
                    }
                    return@executeCancellable null
                }
                val contentType = response.header("Content-Type").orEmpty()
                if (contentType.isNotBlank() && !contentType.startsWith("image/")) return@executeCancellable null
                val body = response.body ?: return@executeCancellable null
                if (body.contentLength() > maxBytes) return@executeCancellable null
                val source = body.source()
                source.request(maxBytes + 1)
                if (source.buffer.size > maxBytes) return@executeCancellable null
                val bytes = source.buffer.readByteArray()
                reliability.recordSuccess(request.url.host, policy)
                FetchedImage(bytes = bytes, contentType = contentType.takeIf { it.isNotBlank() })
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
}

/**
 * Fetches a binary body (covers) capped at [maxBytes]; null on non-2xx, non-image, or oversize.
 * Shares [NetworkClient.fetch]'s per-host rate limit so cover fetches can't stack 403s. Prefer
 * [fetchImage], which also returns the declared content type.
 */
suspend fun NetworkClient.fetchBytes(
    url: String,
    maxBytes: Long = NetworkClient.MAX_IMAGE_BYTES,
): ByteArray? = fetchImage(url, maxBytes)?.bytes

/**
 * Bounded admission: expired entries are dropped and the map is capped, so abandoned
 * preflights cannot accumulate unused HTML for a whole session.
 */
internal fun NetworkClient.admitPreparedPage(
    key: String,
    page: NetworkClient.PreparedPage,
) {
    val now = nowMillis()
    preparedPages.entries.filter { it.value.expiresAt <= now }.forEach { preparedPages.remove(it.key, it.value) }
    while (preparedPages.size >= NetworkClient.MAX_PREPARED_PAGES) {
        preparedPages.entries.minByOrNull { it.value.expiresAt }?.let { oldest ->
            preparedPages.remove(oldest.key, oldest.value)
        } ?: break
    }
    preparedPages[key] = page
}

/**
 * Registers the caller and fetches-or-creates the key's mutex under one monitor, so eviction can
 * never drop a mutex that is locked or about to be acquired.
 */
internal fun NetworkClient.acquirePageLock(cacheKey: String): Mutex =
    synchronized(reusablePageLocks) {
        acquiringPageLockCounts.merge(cacheKey, 1, Int::plus)
        reusablePageLocks.getOrPut(cacheKey) { Mutex() }
    }

/** Pairs with [acquirePageLock]: unregisters after the lock scope ends, then evicts idle locks. */
internal fun NetworkClient.releasePageLock(cacheKey: String) {
    synchronized(reusablePageLocks) {
        val remaining = acquiringPageLockCounts.computeIfPresent(cacheKey) { _, count -> count - 1 }
        if (remaining == null || remaining <= 0) acquiringPageLockCounts.remove(cacheKey)
    }
    evictIdlePageLocks()
}

/**
 * Drops per-key coalescing state whose page is gone. Runs under the lock-map monitor and skips
 * keys that are locked or still acquiring, so eviction can never orphan a mutex a caller holds
 * or is about to lock — duplicate concurrent fetches for the same key remain impossible.
 */
internal fun NetworkClient.evictIdlePageLocks() {
    synchronized(reusablePageLocks) {
        reusablePageLocks.entries.removeIf { (key, lock) ->
            !lock.isLocked && !acquiringPageLockCounts.containsKey(key) && !reusablePages.containsKey(key)
        }
    }
}
