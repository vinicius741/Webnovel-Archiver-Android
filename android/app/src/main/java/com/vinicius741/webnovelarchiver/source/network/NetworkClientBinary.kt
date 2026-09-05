package com.vinicius741.webnovelarchiver.source.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/*
 * Bounded binary (cover) fetchers, split out of [NetworkClient] to keep that class inside its
 * file-size budget. Same shared reliability lane as page fetches.
 */

/** Bounded binary payload plus the response's declared image content type (R25). */
data class FetchedImage(
    val bytes: ByteArray,
    val contentType: String?,
)

/**
 * Fetches a bounded binary body together with the response's declared content type (R25), so
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
            call.executeCancellable().use { response ->
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
                if (body.contentLength() > maxBytes) return@use null
                val source = body.source()
                source.request(maxBytes + 1)
                if (source.buffer.size > maxBytes) return@use null
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
 * Shares [com.vinicius741.webnovelarchiver.source.network.NetworkClient.fetch]'s per-host rate
 * limit so cover fetches can't stack 403s. Prefer [fetchImage], which also returns the declared
 * content type.
 */
suspend fun NetworkClient.fetchBytes(
    url: String,
    maxBytes: Long = NetworkClient.MAX_IMAGE_BYTES,
): ByteArray? = fetchImage(url, maxBytes)?.bytes
