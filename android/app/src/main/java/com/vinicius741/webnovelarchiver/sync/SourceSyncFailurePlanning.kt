package com.vinicius741.webnovelarchiver.sync

import com.vinicius741.webnovelarchiver.domain.model.SourceAvailability
import com.vinicius741.webnovelarchiver.domain.model.SourceFailureKind
import com.vinicius741.webnovelarchiver.domain.model.SourceSyncState
import com.vinicius741.webnovelarchiver.source.network.HttpNetworkException
import com.vinicius741.webnovelarchiver.source.network.NetworkOfflineException
import com.vinicius741.webnovelarchiver.source.network.NetworkParseException
import com.vinicius741.webnovelarchiver.source.network.NetworkTimeoutException
import com.vinicius741.webnovelarchiver.source.network.NetworkTransportException
import com.vinicius741.webnovelarchiver.source.network.RateLimitNetworkException
import com.vinicius741.webnovelarchiver.source.network.SourceAccessBlockedException

data class SourceSyncFailure(
    val kind: SourceFailureKind,
    val availability: SourceAvailability? = null,
    val httpStatus: Int? = null,
)

/** Pure classification and state transitions for source-check outcomes. */
object SourceSyncFailurePlanning {
    fun classify(error: Throwable): SourceSyncFailure =
        when (error) {
            is SourceAccessBlockedException ->
                SourceSyncFailure(SourceFailureKind.access_restricted, SourceAvailability.access_restricted)
            is RateLimitNetworkException ->
                SourceSyncFailure(SourceFailureKind.rate_limited, httpStatus = error.statusCode)
            is HttpNetworkException -> classifyHttp(error.statusCode)
            is NetworkOfflineException -> SourceSyncFailure(SourceFailureKind.offline)
            is NetworkTimeoutException -> SourceSyncFailure(SourceFailureKind.timeout)
            is NetworkParseException -> SourceSyncFailure(SourceFailureKind.parse_error)
            is NetworkTransportException -> SourceSyncFailure(SourceFailureKind.transport)
            else -> SourceSyncFailure(SourceFailureKind.unknown)
        }

    fun afterFailure(
        previous: SourceSyncState,
        failure: SourceSyncFailure,
        checkedAt: Long,
    ): SourceSyncState {
        val nextAvailability = failure.availability ?: previous.availability
        val startsUnavailablePeriod =
            failure.availability != null && previous.availability != nextAvailability
        return SourceSyncState(
            availability = nextAvailability,
            lastCheckedAt = checkedAt,
            unavailableSince =
                when {
                    nextAvailability == SourceAvailability.available -> null
                    startsUnavailablePeriod -> checkedAt
                    else -> previous.unavailableSince ?: checkedAt
                },
            consecutiveNotFoundCount =
                if (failure.kind == SourceFailureKind.not_found) {
                    previous.consecutiveNotFoundCount + 1
                } else {
                    0
                },
            lastFailure = failure.kind,
            lastHttpStatus = failure.httpStatus,
        )
    }

    fun afterSuccess(checkedAt: Long): SourceSyncState =
        SourceSyncState(
            availability = SourceAvailability.available,
            lastCheckedAt = checkedAt,
        )

    private fun classifyHttp(statusCode: Int): SourceSyncFailure =
        when (statusCode) {
            404, 410 ->
                SourceSyncFailure(SourceFailureKind.not_found, SourceAvailability.not_found, statusCode)
            401, 403 ->
                SourceSyncFailure(SourceFailureKind.access_restricted, SourceAvailability.access_restricted, statusCode)
            in 500..599 -> SourceSyncFailure(SourceFailureKind.server_error, httpStatus = statusCode)
            else -> SourceSyncFailure(SourceFailureKind.http_error, httpStatus = statusCode)
        }
}

class StorySourceUnavailableException(
    sourceName: String,
    val statusCode: Int,
    cause: Throwable,
) : IllegalStateException(
        "$sourceName did not return this fiction (HTTP $statusCode). Your downloaded chapters are preserved locally.",
        cause,
    )
