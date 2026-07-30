package com.vinicius741.webnovelarchiver.source.network

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

/**
 * Optional per-request gate layered around the shared source-safety claim.
 *
 * Download code uses this to combine its user-configured delay with the process-wide cooldown and
 * rolling request budget at one actual request boundary. Sync and other callers omit the gate, so
 * they never inherit download preferences. Implementations must invoke [claimSourcePermission]
 * exactly once before returning.
 */
fun interface NetworkRequestGate {
    suspend fun awaitRequest(claimSourcePermission: suspend () -> Unit)
}

@Suppress("TooManyFunctions")
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

    private data class PreparedPage(
        val html: String,
        val expiresAt: Long,
    )

    private val reliability =
        reliabilityCoordinator
            ?: SourceReliabilityCoordinator(
                nowMillis = nowMillis,
                sleep = sleep,
            )
    private val retryBackoff = RetryBackoff(nowMillis, jitterMillis)
    private val preparedPages = ConcurrentHashMap<String, PreparedPage>()
    private val reusablePages = ConcurrentHashMap<String, PreparedPage>()
    private val reusablePageLocks = ConcurrentHashMap<String, Mutex>()

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
            val body = response.body?.string().orEmpty()
            if (SourceAccessBlockDetector.isChallengeResponse(response.headers, body)) {
                throw SourceAccessBlockedException(url)
            }
            body
        }
    }

    /**
     * Fetches a page that several chapter jobs may share and retains it briefly. The per-key mutex
     * coalesces concurrent misses, so a Reader page is requested only once even when parallel
     * workers ask for different chapters on that page at the same time. [cacheValidator] controls
     * cache admission and prevents successful-but-invalid HTML from poisoning later jobs.
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
    }

    suspend fun postForm(
        url: String,
        fields: Map<String, Any>,
        headers: Map<String, String> = emptyMap(),
    ): String {
        val request = NetworkRequests.formRequest(url, fields, headers)
        val policy = policyResolver.policyFor(request.url)
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
        reliability.awaitPermission(url, request.url.host, policy)
        return try {
            withContext(ioDispatcher) {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        if (response.code == 429) {
                            reliability.recordRateLimit(
                                request.url.host,
                                policy,
                                retryBackoff.retryAfterMillis(response.header("Retry-After"), policy),
                            )
                        }
                        return@use null
                    }
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
                    source.buffer.readByteArray().also { reliability.recordSuccess(request.url.host, policy) }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
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
            val claimSourcePermission: suspend () -> Unit = {
                reliability.awaitPermission(url, request.url.host, policy)
            }
            if (requestGate == null) {
                claimSourcePermission()
            } else {
                requestGate.awaitRequest(claimSourcePermission)
            }
            val result = executeAttempt(url, request, callTimeoutMillis, read)
            when (result) {
                is AttemptResult.Success -> {
                    reliability.recordSuccess(request.url.host, policy, result.browserRendered)
                    return result.value
                }
                is AttemptResult.HttpFailure -> {
                    val isRateLimited = result.statusCode in policy.retryableStatusCodes
                    if (!isRateLimited || attempt >= maximumAttempts) {
                        if (isRateLimited) {
                            val requestedRetryAfter = retryBackoff.retryAfterMillis(result.retryAfterHeader, policy)
                            val cooldown = reliability.recordRateLimit(request.url.host, policy, requestedRetryAfter)
                            throw RateLimitNetworkException(
                                requestedUrl = url,
                                statusCode = result.statusCode,
                                retryAfterMillis = maxOf(requestedRetryAfter ?: 0L, cooldown),
                            )
                        }
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
                callTimeoutMillis?.let { timeout -> call.timeout().timeout(timeout, TimeUnit.MILLISECONDS) }
                call.execute().use { response ->
                    if (response.isSuccessful) {
                        return@withContext AttemptResult.Success(
                            read(response),
                            response.header(CloudflareBypassInterceptor.BROWSER_RENDERED_HEADER) == "1",
                        )
                    }
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
        preparedPages[url] = PreparedPage(html, nowMillis() + PREPARED_PAGE_TTL_MILLIS)
    }

    fun clearSourceAccess(
        url: String,
        keepBrowserTransport: Boolean = true,
    ) {
        val parsed = url.toHttpUrlOrNull() ?: return
        reliability.clearAccessBlock(parsed.host, keepBrowserTransport)
    }

    fun onNetworkChanged() {
        preparedPages.clear()
        reusablePages.clear()
        reusablePageLocks.clear()
        reliability.onNetworkChanged()
    }

    fun reliabilitySnapshots(): List<SourceReliabilitySnapshot> = reliability.snapshots()

    companion object {
        /** Maximum bytes accepted for a cover/image download (R6 size cap). */
        const val MAX_IMAGE_BYTES = 8_000_000L
        private const val PREPARED_PAGE_TTL_MILLIS = 5L * 60L * 1_000L
        private const val REUSABLE_PAGE_TTL_MILLIS = 10L * 60L * 1_000L
        private const val MAX_REUSABLE_PAGES = 24

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
                .build()
    }
}
