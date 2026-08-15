package com.vinicius741.webnovelarchiver.source.network

import com.vinicius741.webnovelarchiver.data.diagnostics.BypassEventCategory
import com.vinicius741.webnovelarchiver.data.diagnostics.BypassEventLog
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * One-line call-site helpers for the request-lifecycle events [NetworkClient] records into
 * [BypassEventLog]. Kept here so the client itself stays within its file-size budget; the fields
 * mirror what an investigating agent needs per attempt (see BypassLogExporter's instructions).
 * Durations are derived from this object's own start stamps, so no call site threads a timer
 * through the retry loop.
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
