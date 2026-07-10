package com.vinicius741.webnovelarchiver.source.network

/** Typed failures at the network/source boundary. Messages retain the app's existing user text. */
sealed class NetworkException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class HttpNetworkException(
    val requestedUrl: String,
    val statusCode: Int,
) : NetworkException("HTTP $statusCode for $requestedUrl")

class RateLimitNetworkException(
    val requestedUrl: String,
    val statusCode: Int,
    val retryAfterMillis: Long?,
) : NetworkException("HTTP $statusCode for $requestedUrl")

class NetworkTimeoutException(
    val requestedUrl: String,
    cause: Throwable,
) : NetworkException(cause.message ?: "Network timeout for $requestedUrl", cause)

class NetworkOfflineException(
    val requestedUrl: String,
    cause: Throwable,
) : NetworkException(cause.message ?: "Network unavailable for $requestedUrl", cause)

class NetworkTransportException(
    val requestedUrl: String,
    cause: Throwable,
) : NetworkException(cause.message ?: "Failed to fetch $requestedUrl", cause)

class NetworkParseException(
    message: String,
    cause: Throwable? = null,
) : NetworkException(message, cause)
