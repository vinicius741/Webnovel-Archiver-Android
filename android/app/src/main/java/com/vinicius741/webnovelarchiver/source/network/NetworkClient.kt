package com.vinicius741.webnovelarchiver.source.network

import android.content.Context
import com.vinicius741.webnovelarchiver.data.diagnostics.BypassEventLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Dispatcher
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
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
        val lock = reusablePageLocks.getOrPut(cacheKey) { Mutex() }
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
        evictIdlePageLocks()
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
                call.executeCancellable().use { response ->
                    if (response.isSuccessful) {
                        return@withContext AttemptResult.Success(
                            read(response),
                            response.header(CloudflareBypassInterceptor.BROWSER_RENDERED_HEADER) == "1",
                        )
                    }
                    // Error bodies only feed challenge detection; a bounded prefix is enough (R24).
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
     * Reads a text body with an application-level byte cap (R24): content-length and chunked
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
        return source.buffer.readUtf8()
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
         * Default total budget for one source request (R13), sized to also cover a background
         * Cloudflare render inside the interceptor; callers with tighter classes pass
         * `callTimeoutMillis` explicitly.
         */
        const val DEFAULT_CALL_TIMEOUT_MILLIS = 180_000L

        /** Application-level caps for text/catalog bodies (R24). */
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
         * OkHttp's default of 5 would queue same-host waits outside the call-timeout budget (R13).
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
