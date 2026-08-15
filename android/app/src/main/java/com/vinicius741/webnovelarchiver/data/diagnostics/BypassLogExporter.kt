package com.vinicius741.webnovelarchiver.data.diagnostics

import com.google.gson.Gson
import com.vinicius741.webnovelarchiver.BuildConfig
import com.vinicius741.webnovelarchiver.data.storage.AtomicFileWrites
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import com.vinicius741.webnovelarchiver.source.network.SourceReliabilitySnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Builds and writes the shareable source-access log: one JSON header line describing the format,
 * then one JSON object per recorded bypass event (JSON Lines). Pure assembly lives in
 * [BypassLogExportPlanning] so size-trimming and line validity are unit-testable.
 */
object BypassLogExportPlanning {
    const val MAX_EXPORT_BYTES = 256 * 1024

    fun header(
        appInfo: DiagnosticAppInfo,
        reliability: List<SourceReliabilitySnapshot>,
        queueSummary: DiagnosticQueueSummary,
        droppedCount: Long,
        eventCount: Int,
        generatedAtMillis: Long,
    ): Map<String, Any?> =
        linkedMapOf(
            "format" to "wna-source-access-log",
            "version" to 1,
            "generatedAt" to generatedAtMillis,
            "droppedCount" to droppedCount,
            "eventCount" to eventCount,
            "app" to appInfo,
            "context" to
                linkedMapOf(
                    "reliability" to reliability,
                    "queue" to queueSummary,
                ),
            "agentInstructions" to AGENT_INSTRUCTIONS,
        )

    /**
     * Serializes the header and events, dropping oldest events until the document fits
     * [MAX_EXPORT_BYTES]. Returns the finished lines plus how many extra events were dropped.
     * Each line is serialized once and sizes are accounted incrementally, so trimming a full
     * ring is one pass instead of re-joining the whole document per dropped event.
     */
    fun documentLines(
        header: Map<String, Any?>,
        events: List<BypassEvent>,
        serialize: (Any?) -> String,
    ): Pair<List<String>, Int> {
        val headerLine = serialize(header)
        val eventLines = events.map { event -> serialize(event.toJsonMap()) }
        val eventLineBytes = eventLines.map { line -> line.toByteArray().size }
        // The document is lines joined with "\n": total bytes = Σ line bytes + one per separator,
        // so dropping the oldest event removes its bytes plus its separator.
        var totalBytes = headerLine.toByteArray().size + eventLineBytes.sum() + eventLineBytes.size
        var droppedPrefix = 0
        while (droppedPrefix < eventLineBytes.size && totalBytes > MAX_EXPORT_BYTES) {
            totalBytes -= eventLineBytes[droppedPrefix] + 1
            droppedPrefix += 1
        }
        return listOf(headerLine) + eventLines.drop(droppedPrefix) to droppedPrefix
    }

    const val AGENT_INSTRUCTIONS =
        "You are reading a Webnovel Archiver source-access log. Each following line is one JSON " +
            "event: ts is epoch ms; seq is monotonic per app process (gaps mean the bounded ring " +
            "dropped oldest events; droppedCount in this header counts them). cat 'net' is the " +
            "OkHttp request lifecycle (correlate by attemptId), 'cf' is the Cloudflare bypass " +
            "(sticky Chromium transport, detached-WebView renders polling the DOM every 500ms; " +
            "correlate by renderId), 'dl' is the download queue. Host names are the only location " +
            "data present: no URLs, paths, story/chapter titles, cookies, or bodies by design. " +
            "Vocabulary: challenge_detected means the Cloudflare challenge signal matched; " +
            "transport_sticky_enter/refresh open or extend the 30-minute Chromium-transport " +
            "window; render_poll decision ACCEPT/KEEP_POLLING/REJECT, where stale=true polls " +
            "describe the previous request's document and are never decisive; render_finished " +
            "outcome values: rendered, page_content_unexpected (page loaded but failed content " +
            "rules, so the parser fails that single job), origin_http_<n> (the origin's own " +
            "status), transport_error (retryable), or needs_manual:<note> where " +
            "challenge_still_active needs the in-app verify screen and " +
            "stale_document_persisted/navigation_never_committed mean the WebView navigation " +
            "never committed. circuit_opened pauses the whole source queue pending one " +
            "interactive verification (in-flight jobs fail as source_blocked, pending jobs wait " +
            "and resume after access_cleared); rate_limit_recorded shows cooldowns. To diagnose a " +
            "failing source, find its last challenge_detected or render_finished event and read " +
            "the render_poll decisions before it."
}

object BypassLogExporter {
    private const val KEEP_DUMPS = 3
    private val gson = Gson()

    /**
     * Writes the dump into [directory] (the app's backups folder, already share-whitelisted).
     * Suspends and shifts to IO: the snapshot, size-trim, and fsync'd write must never run on
     * the caller's thread (Settings invokes this from the main scope).
     */
    suspend fun export(
        directory: File,
        network: NetworkClient,
        queue: List<DownloadJob>,
        generatedAtMillis: Long = System.currentTimeMillis(),
    ): File =
        withContext(Dispatchers.IO) {
            val events = BypassEventLog.snapshot()

            fun headerWith(
                droppedCount: Long,
                eventCount: Int,
            ): Map<String, Any?> =
                BypassLogExportPlanning.header(
                    appInfo =
                        DiagnosticAppInfo(
                            versionName = BuildConfig.VERSION_NAME,
                            versionCode = BuildConfig.VERSION_CODE.toLong(),
                            buildType = BuildConfig.BUILD_TYPE,
                            sdkInt = android.os.Build.VERSION.SDK_INT,
                        ),
                    reliability = network.reliabilitySnapshots(),
                    queueSummary = DiagnosticExportPlanning.queueSummary(queue),
                    droppedCount = droppedCount,
                    eventCount = eventCount,
                    generatedAtMillis = generatedAtMillis,
                )

            val (provisionalLines, extraDropped) =
                BypassLogExportPlanning.documentLines(
                    header = headerWith(BypassEventLog.droppedCount(), events.size),
                    events = events,
                    serialize = gson::toJson,
                )
            val lines =
                if (extraDropped == 0) {
                    provisionalLines
                } else {
                    listOf(
                        gson.toJson(
                            headerWith(BypassEventLog.droppedCount() + extraDropped, events.size - extraDropped),
                        ),
                    ) + provisionalLines.drop(1)
                }
            val file = File(directory, "webnovel_source_access_log_$generatedAtMillis.json")
            AtomicFileWrites.writeText(file, lines.joinToString("\n") + "\n")
            pruneOldDumps(directory)
            file
        }

    private fun pruneOldDumps(directory: File) {
        directory
            .listFiles { file -> file.name.startsWith("webnovel_source_access_log_") }
            ?.sortedByDescending { it.name }
            ?.drop(KEEP_DUMPS)
            ?.forEach { it.delete() }
    }
}
